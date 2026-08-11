// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The sample rate in an AIFF header is an 80-bit IEEE 754 extended float, a
 * type nothing else in audio uses and no library decodes for you.
 *
 * Worth testing on its own because getting it wrong does not throw: the file
 * opens, the samples are read correctly, and the track plays at the wrong
 * speed. These are the actual byte sequences a real encoder writes.
 */
@DisplayName("the AIFF sample rate")
class ExtendedFloatTest {

    private fun extended(vararg bytes: Int): ByteArray =
        bytes.map(Int::toByte).toByteArray()

    @Test
    fun `reads the rate every CD-sourced file has`() {
        // 44100 = 0x400E AC44000000000000
        val header = extended(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)

        assertEquals(44100.0, header.extendedFloat(0))
    }

    @Test
    fun `reads the rates the rest of the world uses`() {
        assertEquals(48000.0, extended(0x40, 0x0E, 0xBB, 0x80, 0, 0, 0, 0, 0, 0).extendedFloat(0))
        assertEquals(22050.0, extended(0x40, 0x0D, 0xAC, 0x44, 0, 0, 0, 0, 0, 0).extendedFloat(0))
        assertEquals(8000.0, extended(0x40, 0x0B, 0xFA, 0x00, 0, 0, 0, 0, 0, 0).extendedFloat(0))
    }

    @Test
    fun `reads high-resolution rates without losing the low bits`() {
        assertEquals(96000.0, extended(0x40, 0x0F, 0xBB, 0x80, 0, 0, 0, 0, 0, 0).extendedFloat(0))
        assertEquals(192000.0, extended(0x40, 0x10, 0xBB, 0x80, 0, 0, 0, 0, 0, 0).extendedFloat(0))
    }

    @Test
    fun `a rate of zero is zero, not a denormal`() {
        assertEquals(0.0, extended(0, 0, 0, 0, 0, 0, 0, 0, 0, 0).extendedFloat(0))
    }

    @Test
    fun `reads from an offset, which is where it always sits in a COMM chunk`() {
        val comm = ByteArray(8) + extended(0x40, 0x0E, 0xAC, 0x44, 0, 0, 0, 0, 0, 0)

        assertEquals(44100.0, comm.extendedFloat(8))
    }

    @Test
    fun `a truncated header reads as zero rather than off the end`() {
        assertEquals(0.0, ByteArray(4).extendedFloat(0))
        assertEquals(0.0, ByteArray(10).extendedFloat(8))
    }
}

/**
 * The byte swap, which is the one thing a device cannot verify.
 *
 * Play an AIFF with the samples the wrong way round and it runs for exactly the
 * right duration, advances at exactly the right rate, seeks correctly, and is
 * noise. Every observable signal says it works. So the check has to be made
 * against known bytes, here.
 */
@DisplayName("sample byte order")
class SwapSampleBytesTest {

    private fun swapped(bits: Int, vararg bytes: Int): List<Int> {
        val data = bytes.map(Int::toByte).toByteArray()
        swapSampleBytes(data, data.size, bits)
        return data.map { it.toInt() and 0xFF }
    }

    @Test
    fun `sixteen-bit samples are reversed in pairs`() {
        assertEquals(listOf(0x34, 0x12, 0x78, 0x56), swapped(16, 0x12, 0x34, 0x56, 0x78))
    }

    @Test
    fun `twenty-four-bit samples reverse the outer bytes and leave the middle`() {
        assertEquals(listOf(0x56, 0x34, 0x12), swapped(24, 0x12, 0x34, 0x56))
    }

    @Test
    fun `thirty-two-bit samples reverse all four`() {
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), swapped(32, 0x12, 0x34, 0x56, 0x78))
    }

    /**
     * The asymmetry that makes eight-bit its own case: AIFF stores signed
     * samples, the platform expects unsigned, and there is no byte order in a
     * single byte to swap. Silence is -128..127 centred on 0 becoming 0..255
     * centred on 128.
     */
    @Test
    fun `eight-bit samples are re-centred rather than swapped`() {
        assertEquals(listOf(0x80, 0xFF, 0x00, 0x81), swapped(8, 0x00, 0x7F, 0x80, 0x01))
    }

    @Test
    fun `swapping twice gives back what went in`() {
        val original = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9A.toByte(), 0xBC.toByte())
        val data = original.copyOf()

        swapSampleBytes(data, data.size, 16)
        swapSampleBytes(data, data.size, 16)

        assertArrayEquals(original, data)
    }

    @Test
    fun `only the bytes it was told about are touched`() {
        val data = byteArrayOf(0x12, 0x34, 0x56, 0x78)

        swapSampleBytes(data, 2, 16)

        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x56, 0x78), data)
    }

    @Test
    fun `a trailing partial sample is left alone rather than half-swapped`() {
        val data = byteArrayOf(0x12, 0x34, 0x56)

        swapSampleBytes(data, 3, 16)

        assertArrayEquals(byteArrayOf(0x34, 0x12, 0x56), data)
    }
}

@DisplayName("AIFF header fields")
class AiffFieldTest {

    @Test
    fun `four-character chunk ids read as themselves`() {
        val header = "FORM....AIFF".toByteArray(Charsets.US_ASCII)

        assertEquals("FORM", header.string(0, 4))
        assertEquals("AIFF", header.string(8, 4))
    }

    @Test
    fun `sizes are big-endian, as everything in IFF is`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x01, 0x00)

        assertEquals(256L, bytes.u32(0))
    }

    @Test
    fun `a size with the top bit set is not read as negative`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertEquals(4_294_967_295L, bytes.u32(0))
    }

    @Test
    fun `channel counts and sample sizes are sixteen bits`() {
        val comm = byteArrayOf(0x00, 0x02, 0, 0, 0, 0, 0x00, 0x10)

        assertEquals(2, comm.u16(0))
        assertEquals(16, comm.u16(6))
    }

    @Test
    fun `reading past the end gives a harmless answer, not an exception`() {
        assertEquals("", ByteArray(2).string(0, 4))
        assertEquals(0, ByteArray(1).u16(0))
        assertEquals(-1L, ByteArray(2).u32(0))
    }
}
