// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The blob that carries three numbers across a boundary neither side owns.
 *
 * One end of this is Kotlin in `ChoirCodecContext`; the other is Java in
 * Choir's copy of `FfmpegAudioDecoder`, which reads the same sixteen bytes
 * without importing anything from the app. Nothing but agreement holds the two
 * together, and a disagreement does not fail loudly — Windows Media and
 * Monkey's Audio simply stop opening.
 *
 * So the layout is asserted here byte by byte, rather than only round-tripped.
 * A round trip through one implementation would keep passing while both halves
 * drifted together.
 */
class ChoirCodecContextTest {

    @Test
    fun `writes the magic and three little-endian fields`() {
        val bytes = ChoirCodecContext.encode(
            blockAlign = 0x0BAD,
            bitsPerCodedSample = 24,
            bitRate = 128_000,
        )

        assertEquals(ChoirCodecContext.BYTES, bytes.size)
        assertArrayEquals(
            byteArrayOf(
                0x31, 0x58, 0x43, 0x43, // the magic, little-endian
                0xAD.toByte(), 0x0B, 0x00, 0x00, // block align
                0x18, 0x00, 0x00, 0x00, // bits per coded sample
                0x00, 0xF4.toByte(), 0x01, 0x00, // bit rate
            ),
            bytes,
        )
    }

    @Test
    fun `reads back what it wrote`() {
        val values = requireNotNull(
            ChoirCodecContext.decode(
                ChoirCodecContext.encode(
                    blockAlign = 2_973,
                    bitsPerCodedSample = 16,
                    bitRate = 128_000,
                ),
            ),
        )

        assertEquals(2_973, values.blockAlign)
        assertEquals(16, values.bitsPerCodedSample)
        assertEquals(128_000, values.bitRate)
    }

    @Test
    fun `omitted fields read back as zero, meaning ask the codec`() {
        val values = requireNotNull(
            ChoirCodecContext.decode(ChoirCodecContext.encode(bitsPerCodedSample = 16)),
        )

        assertEquals(0, values.blockAlign)
        assertEquals(0, values.bitRate)
    }

    /**
     * The reason there is a magic at all: `initializationData` is a list of
     * opaque byte arrays, and a future extractor putting something else in the
     * second slot must not have it read as a codec context.
     */
    @Test
    fun `declines bytes that are not one of these`() {
        assertNull(ChoirCodecContext.decode(ByteArray(ChoirCodecContext.BYTES)))
        assertNull(ChoirCodecContext.decode(ByteArray(0)))
        assertNull(ChoirCodecContext.decode(ByteArray(ChoirCodecContext.BYTES - 1)))
    }

    /**
     * Duplicated in `FfmpegAudioDecoder.CHOIR_CODEC_CONTEXT_MAGIC`, because
     * vendored Media3 sources importing app code would be the worse coupling.
     * If this constant changes, that one has to change with it.
     */
    @Test
    fun `states the magic the decoder half also hard-codes`() {
        assertEquals(0x43435831, ChoirCodecContext.MAGIC)
    }
}
