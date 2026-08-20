/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.decoder.ffmpeg;

import static androidx.media3.common.util.Assertions.checkNotNull;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;

/** FFmpeg audio decoder. */
/* package */ final class FfmpegAudioDecoder
    extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException> {

  private static final int INITIAL_OUTPUT_BUFFER_SIZE_16BIT = 65535;
  private static final int INITIAL_OUTPUT_BUFFER_SIZE_32BIT = INITIAL_OUTPUT_BUFFER_SIZE_16BIT * 2;

  private static final int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int AUDIO_DECODER_ERROR_OTHER = -2;

  /** Choir's addition. See {@code app.auriel.choir.playback.ChoirCodecContext}. */
  private static final int CHOIR_CODEC_CONTEXT_MAGIC = 0x43435831;

  private static final int CHOIR_CODEC_CONTEXT_BYTES = 16;

  /**
   * Set once the native library turns out to predate {@code ffmpegInitializeContext}, so the
   * failure is discovered at most once per process rather than per track.
   */
  private static volatile boolean choirInitializeUnavailable;

  private final String codecName;
  @Nullable private final byte[] extraData;
  private final @C.PcmEncoding int encoding;
  private int outputBufferSize;

  private long nativeContext; // May be reassigned on resetting the codec.
  private boolean hasOutputFormat;
  private volatile int channelCount;
  private volatile int sampleRate;

  public FfmpegAudioDecoder(
      Format format,
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      boolean outputFloat)
      throws FfmpegDecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new SimpleDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    checkNotNull(format.sampleMimeType);
    codecName = checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    extraData = getExtraData(format.sampleMimeType, format.initializationData);
    encoding = outputFloat ? C.ENCODING_PCM_FLOAT : C.ENCODING_PCM_16BIT;
    outputBufferSize =
        outputFloat ? INITIAL_OUTPUT_BUFFER_SIZE_32BIT : INITIAL_OUTPUT_BUFFER_SIZE_16BIT;
    nativeContext = initializeDecoder(codecName, extraData, outputFloat, format);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Initialization failed.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected SimpleDecoderOutputBuffer createOutputBuffer() {
    return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      nativeContext = ffmpegReset(nativeContext, extraData);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize);
    int result =
        ffmpegDecode(
            nativeContext, inputData, inputSize, outputBuffer, outputData, outputBufferSize);
    if (result == AUDIO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("Error decoding (see logcat).");
    } else if (result == AUDIO_DECODER_ERROR_INVALID_DATA) {
      // Treat invalid data errors as non-fatal to match the behavior of MediaCodec. No output will
      // be produced for this buffer, so mark it as skipped to ensure that the audio sink's
      // position is reset when more audio is produced.
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (result == 0) {
      // There's no need to output empty buffers.
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    if (!hasOutputFormat) {
      channelCount = ffmpegGetChannelCount(nativeContext);
      sampleRate = ffmpegGetSampleRate(nativeContext);
      if (sampleRate == 0 && "alac".equals(codecName)) {
        checkNotNull(extraData);
        // ALAC decoder did not set the sample rate in earlier versions of FFmpeg. See
        // https://trac.ffmpeg.org/ticket/6096.
        ParsableByteArray parsableExtraData = new ParsableByteArray(extraData);
        parsableExtraData.setPosition(extraData.length - 4);
        sampleRate = parsableExtraData.readUnsignedIntToInt();
      }
      hasOutputFormat = true;
    }
    // Get a new reference to the output ByteBuffer in case the native decode method reallocated the
    // buffer to grow its size.
    outputData = checkNotNull(outputBuffer.data);
    outputData.position(0);
    outputData.limit(result);
    return null;
  }

  // Called from native code
  @SuppressWarnings("unused")
  private ByteBuffer growOutputBuffer(SimpleDecoderOutputBuffer outputBuffer, int requiredSize) {
    // Use it for new buffer so that hopefully we won't need to reallocate again
    outputBufferSize = requiredSize;
    return outputBuffer.grow(requiredSize);
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  /** Returns the channel count of output audio. */
  public int getChannelCount() {
    return channelCount;
  }

  /** Returns the sample rate of output audio. */
  public int getSampleRate() {
    return sampleRate;
  }

  /** Returns the encoding of output audio. */
  public @C.PcmEncoding int getEncoding() {
    return encoding;
  }

  /**
   * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
   * not required.
   */
  @Nullable
  private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    switch (mimeType) {
      case MimeTypes.AUDIO_AAC:
      case MimeTypes.AUDIO_OPUS:
        return initializationData.get(0);
      case MimeTypes.AUDIO_ALAC:
        return getAlacExtraData(initializationData);
      case MimeTypes.AUDIO_VORBIS:
        return getVorbisExtraData(initializationData);
        // Choir's additions, for the containers it demuxes itself. Each of
        // these extractors publishes the codec's extradata exactly as FFmpeg
        // wants it, so there is nothing to repackage: six bytes of header for
        // Monkey's Audio, and the tail of the WAVEFORMATEX for Windows Media.
        // The strings are ChoirMimeTypes, and the two must agree.
      case "audio/x-ape":
      case "audio/x-ms-wma":
      case "audio/x-ms-wmapro":
      case "audio/x-ms-wmalossless":
      case "audio/x-ms-wmavoice":
        return initializationData.isEmpty() ? null : initializationData.get(0);
      default:
        // Other codecs do not require extra data.
        return null;
    }
  }

  /**
   * Choir's addition: the codec context fields that {@link Format} has nowhere to put.
   *
   * <p>Windows Media will not open without a block alignment and a bitrate, and Monkey's Audio will
   * not open without a bit depth. None of the three is expressible on a {@code Format}, so the
   * extractor appends them as a second entry in {@code initializationData}, in the layout written
   * by {@code app.auriel.choir.playback.ChoirCodecContext} — magic, block align, bits per coded
   * sample, bitrate, each a little-endian 32-bit value. The two must agree, and this is the half
   * that reads it; app code is not imported here so that this file stays a vendored Media3 source
   * with additions rather than a fork entangled with the app.
   *
   * @return the three values in that order, or {@code null} where this stream did not supply them.
   */
  @Nullable
  private static int[] getChoirCodecContext(List<byte[]> initializationData) {
    if (initializationData.size() < 2) {
      return null;
    }
    byte[] data = initializationData.get(1);
    if (data.length != CHOIR_CODEC_CONTEXT_BYTES
        || readLittleEndianInt(data, 0) != CHOIR_CODEC_CONTEXT_MAGIC) {
      return null;
    }
    return new int[] {
      readLittleEndianInt(data, 4), readLittleEndianInt(data, 8), readLittleEndianInt(data, 12)
    };
  }

  private static int readLittleEndianInt(byte[] data, int offset) {
    return (data[offset] & 0xFF)
        | ((data[offset + 1] & 0xFF) << 8)
        | ((data[offset + 2] & 0xFF) << 16)
        | ((data[offset + 3] & 0xFF) << 24);
  }

  /**
   * Choir's addition: opens the native decoder, using the richer entry point where there is one.
   *
   * <p>{@code ffmpegInitializeContext} is not part of upstream Media3 — it is appended to the JNI
   * by {@code tools/ffmpeg-jni-context.inc} when {@code tools/build-ffmpeg.sh} runs. A
   * {@code libffmpegJNI.so} built before that, or by anyone following upstream's own instructions,
   * does not export it, and calling it raises {@link UnsatisfiedLinkError} on the first attempt.
   *
   * <p>That is caught rather than allowed to propagate, because the alternative is that adding
   * Monkey's Audio and Windows Media breaks ALAC and Dolby on every installation carrying an older
   * library. Falling back costs those two formats and nothing else: they fail to open, which is
   * what they did before any of this existed.
   */
  private long initializeDecoder(
      String codecName, @Nullable byte[] extraData, boolean outputFloat, Format format) {
    int[] codecContext = getChoirCodecContext(format.initializationData);
    if (codecContext != null && !choirInitializeUnavailable) {
      try {
        return ffmpegInitializeContext(
            codecName,
            extraData,
            outputFloat,
            format.sampleRate,
            format.channelCount,
            /* blockAlign= */ codecContext[0],
            /* bitsPerCodedSample= */ codecContext[1],
            /* bitRate= */ codecContext[2]);
      } catch (UnsatisfiedLinkError e) {
        choirInitializeUnavailable = true;
      }
    }
    return ffmpegInitialize(
        codecName, extraData, outputFloat, format.sampleRate, format.channelCount);
  }

  private static byte[] getAlacExtraData(List<byte[]> initializationData) {
    // FFmpeg's ALAC decoder expects an ALAC atom, which contains the ALAC "magic cookie", as extra
    // data. initializationData[0] contains only the magic cookie, and so we need to package it into
    // an ALAC atom. See:
    // https://ffmpeg.org/doxygen/0.6/alac_8c.html
    // https://github.com/macosforge/alac/blob/master/ALACMagicCookieDescription.txt
    byte[] magicCookie = initializationData.get(0);
    int alacAtomLength = 12 + magicCookie.length;
    ByteBuffer alacAtom = ByteBuffer.allocate(alacAtomLength);
    alacAtom.putInt(alacAtomLength);
    alacAtom.putInt(0x616c6163); // type=alac
    alacAtom.putInt(0); // version=0, flags=0
    alacAtom.put(magicCookie, /* offset= */ 0, magicCookie.length);
    return alacAtom.array();
  }

  private static byte[] getVorbisExtraData(List<byte[]> initializationData) {
    byte[] header0 = initializationData.get(0);
    byte[] header1 = initializationData.get(1);
    byte[] extraData = new byte[header0.length + header1.length + 6];
    extraData[0] = (byte) (header0.length >> 8);
    extraData[1] = (byte) (header0.length & 0xFF);
    System.arraycopy(header0, 0, extraData, 2, header0.length);
    extraData[header0.length + 2] = 0;
    extraData[header0.length + 3] = 0;
    extraData[header0.length + 4] = (byte) (header1.length >> 8);
    extraData[header0.length + 5] = (byte) (header1.length & 0xFF);
    System.arraycopy(header1, 0, extraData, header0.length + 6, header1.length);
    return extraData;
  }

  private native long ffmpegInitialize(
      String codecName,
      @Nullable byte[] extraData,
      boolean outputFloat,
      int rawSampleRate,
      int rawChannelCount);

  /**
   * Choir's addition, appended to the JNI by {@code tools/ffmpeg-jni-context.inc}.
   *
   * <p>Upstream's {@code ffmpegInitialize} applies its sample rate and channel count only to raw
   * PCM, because every codec it was written for reads those out of its own extradata. The ones
   * Choir demuxes itself do not: they are told, or they decline to open. This carries the same
   * arguments plus the three fields those codecs read off {@code AVCodecContext} directly.
   *
   * <p>Additive on purpose. Changing the signature of {@code ffmpegInitialize} would have made
   * every existing {@code libffmpegJNI.so} unusable rather than merely incomplete.
   */
  private native long ffmpegInitializeContext(
      String codecName,
      @Nullable byte[] extraData,
      boolean outputFloat,
      int sampleRate,
      int channelCount,
      int blockAlign,
      int bitsPerCodedSample,
      int bitRate);

  private native int ffmpegDecode(
      long context,
      ByteBuffer inputData,
      int inputSize,
      SimpleDecoderOutputBuffer decoderOutputBuffer,
      ByteBuffer outputData,
      int outputSize);

  private native int ffmpegGetChannelCount(long context);

  private native int ffmpegGetSampleRate(long context);

  private native long ffmpegReset(long context, @Nullable byte[] extraData);

  private native void ffmpegRelease(long context);
}
