// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * A cursor over a byte array that runs out of road instead of throwing.
 *
 * Every one of these readers is parsing bytes written by some other program,
 * often years ago and sometimes wrongly. Truncated or nonsense input has to end
 * the parse and give back whatever was understood so far, never crash the app.
 */
internal class ByteReader(private val bytes: ByteArray, var position: Int = 0) {

    val remaining: Int get() = (bytes.size - position).coerceAtLeast(0)

    fun canRead(count: Int): Boolean = count >= 0 && remaining >= count

    fun skip(count: Int) {
        position = (position + count).coerceIn(0, bytes.size)
    }

    fun u8(): Int {
        if (!canRead(1)) return -1
        return bytes[position++].toInt() and 0xFF
    }

    /** Big-endian, as everything in ID3 is. */
    fun u32BE(): Long {
        if (!canRead(4)) return -1
        var value = 0L
        repeat(4) { value = (value shl 8) or (bytes[position++].toLong() and 0xFF) }
        return value
    }

    /** Little-endian, as everything in a Vorbis comment is. */
    fun u32LE(): Long {
        if (!canRead(4)) return -1
        var value = 0L
        repeat(4) { shift -> value = value or ((bytes[position++].toLong() and 0xFF) shl (8 * shift)) }
        return value
    }

    fun u24BE(): Int {
        if (!canRead(3)) return -1
        var value = 0
        repeat(3) { value = (value shl 8) or (bytes[position++].toInt() and 0xFF) }
        return value
    }

    /**
     * ID3's synchsafe integer: seven bits per byte, so the value can never
     * contain a byte that looks like the start of an MPEG frame sync.
     */
    fun syncsafe32(): Long {
        if (!canRead(4)) return -1
        var value = 0L
        repeat(4) { value = (value shl 7) or (bytes[position++].toLong() and 0x7F) }
        return value
    }

    fun bytes(count: Int): ByteArray? {
        if (!canRead(count)) return null
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun ascii(count: Int): String? = bytes(count)?.toString(Charsets.US_ASCII)

    /**
     * Latin-1, which round-trips every byte to a character and back.
     *
     * MP4 atom names are four *bytes*, not four ASCII characters — the iTunes
     * set is prefixed with 0xA9, which US-ASCII decodes to a replacement
     * character, quietly making `©lyr` unequal to itself.
     */
    fun latin1(count: Int): String? = bytes(count)?.toString(Charsets.ISO_8859_1)

    fun rest(): ByteArray = bytes(remaining) ?: ByteArray(0)

    /**
     * Reads up to a run of [width] zero bytes and steps past it, returning what
     * came before. Null when there is no terminator at all, leaving the cursor
     * untouched so the caller can decide what the remainder means.
     *
     * [width] is 2 for the UTF-16 encodings, where a single zero byte is half
     * of an ordinary character.
     */
    fun readUntilTerminator(width: Int): ByteArray? {
        var cursor = position
        while (cursor + width <= bytes.size) {
            if ((0 until width).all { bytes[cursor + it] == 0.toByte() }) {
                val value = bytes.copyOfRange(position, cursor)
                position = cursor + width
                return value
            }
            cursor += width
        }
        return null
    }
}

/**
 * Reads at most [limit] bytes, stopping early at end of stream.
 *
 * Grows as it reads rather than allocating [limit] up front: the limits here
 * are guesses at how far into a file a tag might sit, and a file claiming an
 * eight-megabyte tag header must not be able to make Choir allocate eight
 * megabytes to find out it was lying.
 */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(CHUNK_BYTES)

    while (out.size() < limit) {
        val wanted = minOf(buffer.size, limit - out.size())
        val read = read(buffer, 0, wanted)
        if (read <= 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val CHUNK_BYTES = 64 * 1024
