// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

/**
 * Vorbis comments — the tag format FLAC, Ogg Vorbis and Opus all share.
 *
 * The block itself is the same everywhere; only the container around it
 * differs, which is why [Flac] and [Ogg] below exist purely to find it.
 */
internal object VorbisCommentReader {

    /** Field names taggers use for lyrics, best first. */
    private val LYRIC_FIELDS = listOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS")

    /**
     * `vendor length, vendor, count, count × "KEY=value"` — all lengths
     * little-endian, unlike everything in ID3.
     */
    fun lyricsIn(block: ByteArray): String? {
        val reader = ByteReader(block)

        val vendorLength = reader.u32LE()
        if (vendorLength < 0 || !reader.canRead(vendorLength.toInt())) return null
        reader.skip(vendorLength.toInt())

        val count = reader.u32LE()
        // A corrupt count would otherwise ask us to loop four billion times.
        if (count < 0 || count > MAX_COMMENTS) return null

        val fields = mutableMapOf<String, String>()
        repeat(count.toInt()) {
            val length = reader.u32LE()
            if (length < 0 || length > MAX_COMMENT_BYTES) return@repeat
            val raw = reader.bytes(length.toInt()) ?: return@repeat

            val comment = raw.toString(Charsets.UTF_8)
            val separator = comment.indexOf('=')
            if (separator <= 0) return@repeat

            val key = comment.substring(0, separator).uppercase()
            // First occurrence wins: a repeated field is a tagger's mistake,
            // and the first one is what most players show.
            fields.putIfAbsent(key, comment.substring(separator + 1))
        }

        return LYRIC_FIELDS.firstNotNullOfOrNull { fields[it] }?.takeIf { it.isNotBlank() }
    }

    private const val MAX_COMMENTS = 4096L
    private const val MAX_COMMENT_BYTES = 4L * 1024 * 1024
}

/**
 * FLAC: the magic `fLaC`, then a chain of metadata blocks. Block type 4 is the
 * Vorbis comment; the top bit of the type byte marks the last block.
 */
internal object Flac {

    fun lyrics(body: ByteArray): String? {
        val reader = ByteReader(body)

        while (reader.remaining > 4) {
            val header = reader.u8()
            if (header < 0) return null
            val isLast = header and 0x80 != 0
            val type = header and 0x7F

            val length = reader.u24BE()
            if (length < 0 || !reader.canRead(length)) return null

            if (type == VORBIS_COMMENT) {
                val block = reader.bytes(length) ?: return null
                return VorbisCommentReader.lyricsIn(block)
            }
            reader.skip(length)

            if (isLast) return null
        }
        return null
    }

    private const val VORBIS_COMMENT = 4
}

/**
 * Ogg: the tags live in the second packet of the logical stream, which may be
 * spread across several pages and may share a page with its neighbours.
 *
 * So the packets have to be reassembled properly rather than scanned for: a
 * lyric long enough to be worth having is also long enough to cross a page
 * boundary, and a naive search would return half of one.
 */
internal object Ogg {

    fun lyrics(body: ByteArray): String? {
        val packet = secondPacket(body) ?: return null

        // Vorbis marks its comment header `vorbis`; Opus uses `OpusTags`.
        val block = when {
            packet.startsWith(VORBIS_COMMENT_SIGNATURE) ->
                packet.copyOfRange(VORBIS_COMMENT_SIGNATURE.size, packet.size)

            packet.startsWith(OPUS_TAGS_SIGNATURE) ->
                packet.copyOfRange(OPUS_TAGS_SIGNATURE.size, packet.size)

            else -> return null
        }
        return VorbisCommentReader.lyricsIn(block)
    }

    /**
     * Walks pages, splitting their segments into packets. A segment of exactly
     * 255 bytes means the packet continues into the next one; anything shorter
     * ends it.
     */
    private fun secondPacket(body: ByteArray): ByteArray? {
        val reader = ByteReader(body)
        val packet = java.io.ByteArrayOutputStream()
        var packetsCompleted = 0

        while (reader.canRead(HEADER_BYTES)) {
            if (reader.ascii(4) != "OggS") return null
            reader.skip(HEADER_BYTES - 4 - 1)

            val segmentCount = reader.u8()
            if (segmentCount < 0) return null
            val segments = reader.bytes(segmentCount) ?: return null

            for (segment in segments) {
                val length = segment.toInt() and 0xFF
                val data = reader.bytes(length) ?: return null

                if (packetsCompleted == 1) packet.write(data)

                if (length < 255) {
                    packetsCompleted++
                    // The second packet has just ended, whole.
                    if (packetsCompleted == 2) return packet.toByteArray()
                }
            }
        }

        // Ran out of file mid-packet: better a truncated tag than none, as long
        // as the signature made it in.
        return packet.toByteArray().takeIf { it.isNotEmpty() }
    }

    /** `capture, version, type, granule, serial, sequence, checksum, segments`. */
    private const val HEADER_BYTES = 27

    private val VORBIS_COMMENT_SIGNATURE = byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte())
    private val OPUS_TAGS_SIGNATURE = "OpusTags".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
