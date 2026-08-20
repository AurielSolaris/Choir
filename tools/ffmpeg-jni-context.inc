// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Appended to Media3's ffmpeg_jni.cc by tools/build-ffmpeg.sh.
//
// Upstream's ffmpegInitialize sets sample_rate and ch_layout only for PCM
// mu-law and A-law, and never sets block_align, bits_per_coded_sample or
// bit_rate at all. That is a reasonable choice for the codecs Media3 has
// demuxers for: each of those reads its own parameters out of extradata, so an
// AVCodecContext with nothing filled in still opens.
//
// The codecs Choir reaches past Media3 to use are not like that.
//
//   - wmadec  rejects a context with no block_align outright, and derives its
//             coefficient tables from bit_rate. Given neither, a perfectly good
//             WMA stream fails to open, or opens and decodes to silence.
//   - apedec  chooses its output sample format from bits_per_coded_sample and
//             treats zero as an unsupported depth rather than as "unstated".
//
// So this is the same function as upstream's, with the parameters passed
// through instead of dropped. It is appended rather than patched in because a
// new symbol cannot break an existing caller, whereas editing ffmpegInitialize
// would silently change the meaning of a library that Media3 also ships.
//
// The Java side is app/src/main/java/androidx/media3/decoder/ffmpeg/
// FfmpegAudioDecoder.java, which calls this when a track supplies the fields
// and falls back to ffmpegInitialize when the library is older than this file.

extern "C" {
JNIEXPORT jlong JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegInitializeContext(
    JNIEnv *env, jobject thiz, jstring codecName, jbyteArray extraData,
    jboolean outputFloat, jint sampleRate, jint channelCount, jint blockAlign,
    jint bitsPerCodedSample, jint bitRate);
}

JNIEXPORT jlong JNICALL
Java_androidx_media3_decoder_ffmpeg_FfmpegAudioDecoder_ffmpegInitializeContext(
    JNIEnv *env, jobject thiz, jstring codecName, jbyteArray extraData,
    jboolean outputFloat, jint sampleRate, jint channelCount, jint blockAlign,
    jint bitsPerCodedSample, jint bitRate) {
  const char *codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec *codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }

  AVCodecContext *context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return 0L;
  }

  context->request_sample_fmt =
      outputFloat ? AV_SAMPLE_FMT_FLT : AV_SAMPLE_FMT_S16;

  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    context->extradata =
        (uint8_t *)av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      avcodec_free_context(&context);
      return 0L;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte *)context->extradata);
    memset(context->extradata + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
  }

  // Every one of these is left alone when the caller has nothing to say, so a
  // codec that would rather work the value out for itself still can.
  if (sampleRate > 0) {
    context->sample_rate = sampleRate;
  }
  if (channelCount > 0) {
    av_channel_layout_default(&context->ch_layout, channelCount);
  }
  if (blockAlign > 0) {
    context->block_align = blockAlign;
  }
  if (bitsPerCodedSample > 0) {
    context->bits_per_coded_sample = bitsPerCodedSample;
  }
  if (bitRate > 0) {
    context->bit_rate = bitRate;
  }

  context->err_recognition = AV_EF_IGNORE_ERR;

  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    LOGE("Failed to open context: %d", result);
    avcodec_free_context(&context);
    return 0L;
  }
  return (jlong)context;
}
