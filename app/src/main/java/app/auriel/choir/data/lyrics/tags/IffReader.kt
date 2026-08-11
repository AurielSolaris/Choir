// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import java.io.InputStream

/**
 * Walks the chunked container that WAVE and AIFF are both built out of.
 *
 * Both descend from Electronic Arts' IFF: a four-character form type, then a
 * flat run of chunks, each a four-character id, a 32-bit length and that many
 * bytes, padded to an even boundary. The only difference between them is byte
 * order — Microsoft reversed it for RIFF — which is why one walker with an
 * endianness flag covers both instead of two nearly identical ones.
 *
 * Lyrics live in a chunk called `id3 `, holding an entire ID3v2 tag, so the
 * existing ID3 reader does the real work once the chunk is found.
 */
internal class IffReader(
    private val input: InputStream,
    private val littleEndian: Boolean,
) {

    /**
     * Reads the form header, having already consumed the leading `RIFF`/`FORM`.
     *
     * @return the form type — `WAVE`, `AIFF`, `AIFC` — or null if the header is
     *   too short to be one.
     */
    fun formType(): String? {
        val header = input.readUpTo(8)
        if (header.size < 8) return null
        // The declared size covers the rest of the file and is not worth
        // trusting; walking until the stream ends is both simpler and more
        // forgiving of files truncated in transit.
        return header.copyOfRange(4, 8).toString(Charsets.US_ASCII)
    }

    /**
     * Walks chunks until one whose id is in [ids] appears, skipping the rest
     * without reading them — a WAVE's `data` chunk is the entire song.
     *
     * @return the chunk's payload, or null if the stream ends first.
     */
    fun findChunk(ids: Set<String>, maxChunkBytes: Int): ByteArray? {
        var scanned = 0L

        while (scanned < MAX_SCAN_BYTES) {
            val header = input.readUpTo(HEADER_BYTES)
            if (header.size < HEADER_BYTES) return null

            val reader = ByteReader(header)
            val id = reader.ascii(4) ?: return null
            val size = if (littleEndian) reader.u32LE() else reader.u32BE()
            if (size < 0) return null

            // Odd-length chunks carry a pad byte that is not counted in the
            // length. Forgetting it is the classic way to misparse a WAVE.
            val padded = size + (size and 1L)

            if (id in ids) {
                if (size > maxChunkBytes) return null
                return input.readUpTo(size.toInt())
            }

            if (!input.skipExactly(padded)) return null
            scanned += padded + HEADER_BYTES
        }
        return null
    }

    private companion object {
        const val HEADER_BYTES = 8

        /** Far enough to pass a long song, short of reading a whole disc image. */
        const val MAX_SCAN_BYTES = 2L * 1024 * 1024 * 1024
    }
}

/**
 * The ID3 tag carried inside a WAVE or AIFF file, if it has one.
 *
 * Both formats agreed on the same answer to "where do the tags go" — a chunk
 * holding a complete ID3v2 tag, byte for byte what an MP3 would put at its
 * head. The id is conventionally lowercase `id3 ` in WAVE and uppercase `ID3 `
 * in AIFF, and enough files get that backwards that both are accepted.
 */
internal object IffId3 {

    private val CHUNK_IDS = setOf("id3 ", "ID3 ", "ID32")

    fun lyrics(input: InputStream, littleEndian: Boolean): RawTag? {
        val reader = IffReader(input, littleEndian)
        reader.formType() ?: return null

        val chunk = reader.findChunk(CHUNK_IDS, MAX_TAG_BYTES) ?: return null
        if (chunk.size < ID3_HEADER_BYTES) return null

        return RawTag(
            header = chunk.copyOfRange(0, ID3_HEADER_BYTES),
            body = chunk.copyOfRange(ID3_HEADER_BYTES, chunk.size),
        )
    }

    /** An ID3v2 tag split the way [Id3v2Reader] wants it. */
    data class RawTag(val header: ByteArray, val body: ByteArray) {
        // Data classes over arrays need these spelled out; identity comparison
        // of the arrays would make two equal tags unequal.
        override fun equals(other: Any?): Boolean =
            this === other || (other is RawTag &&
                header.contentEquals(other.header) && body.contentEquals(other.body))

        override fun hashCode(): Int = 31 * header.contentHashCode() + body.contentHashCode()
    }

    private const val ID3_HEADER_BYTES = 10
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024
}
