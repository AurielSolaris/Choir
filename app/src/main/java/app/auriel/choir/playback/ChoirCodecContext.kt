// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

/**
 * The three numbers some FFmpeg decoders need and Media3's [androidx.media3.common.Format]
 * cannot say.
 *
 * A `Format` describes what a stream *is* — its MIME type, sample rate, channel
 * count, and the codec-private extradata. That covers every decoder Media3 was
 * built to drive. It does not cover the decoders Choir reaches past Media3 to
 * use, which read fields off FFmpeg's own `AVCodecContext` before they will
 * open at all:
 *
 *  - **`block_align`** — Windows Media splits its audio into fixed-size blocks
 *    and the decoder sizes its buffers from that number. Without it `wmadec`
 *    refuses to open, with an `Invalid block align` and nothing playing.
 *  - **`bits_per_coded_sample`** — Monkey's Audio chooses its output sample
 *    format from the bit depth. A zero here is not read as "unknown" but as an
 *    unsupported depth, and the file is declined.
 *  - **`bit_rate`** — Windows Media derives its coefficient tables from the
 *    bitrate, which is why an otherwise perfect stream decodes to silence
 *    without it.
 *
 * None of the three has anywhere to live on a `Format`, so they travel as a
 * second entry in `initializationData`, behind the codec extradata that is
 * always the first. The one place that reads them back is Choir's copy of
 * `FfmpegAudioDecoder`, and the layout below is duplicated in that file's
 * `getChoirCodecContext` — the two must agree, and a comment in each says so,
 * because vendored Media3 sources importing app code would be worse.
 *
 * The magic is there so the decoder can tell this blob from whatever a future
 * extractor might want to put in the same slot, and pass it by if it is not
 * ours.
 */
object ChoirCodecContext {

    /** `"CCX1"`, and the reason a second initialization entry is never guessed at. */
    const val MAGIC = 0x43_43_58_31

    /** Magic, then the three fields, all little-endian. */
    const val BYTES = 16

    fun encode(
        blockAlign: Int = 0,
        bitsPerCodedSample: Int = 0,
        bitRate: Int = 0,
    ): ByteArray {
        val bytes = ByteArray(BYTES)
        bytes.putU32le(0, MAGIC.toLong())
        bytes.putU32le(4, blockAlign.toLong())
        bytes.putU32le(8, bitsPerCodedSample.toLong())
        bytes.putU32le(12, bitRate.toLong())
        return bytes
    }

    /** What [encode] wrote, or null where these bytes are something else. */
    fun decode(bytes: ByteArray): Values? {
        if (bytes.size != BYTES || bytes.u32le(0) != MAGIC.toLong()) return null
        return Values(
            blockAlign = bytes.u32le(4).toInt(),
            bitsPerCodedSample = bytes.u32le(8).toInt(),
            bitRate = bytes.u32le(12).toInt(),
        )
    }

    data class Values(
        val blockAlign: Int,
        val bitsPerCodedSample: Int,
        val bitRate: Int,
    )
}
