// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import java.io.InputStream

/**
 * Reads iTunes-style lyrics out of an MP4 container — `.m4a`, `.m4b`, `.mp4`.
 *
 * MP4 stores metadata in a tree of atoms, and lyrics sit at
 * `moov/udta/meta/ilst/©lyr/data`. That `©` is a literal 0xA9 byte, a
 * convention inherited from QuickTime, and it is the reason this cannot reuse
 * anything the ID3 reader does.
 *
 * The awkward part is where `moov` lives. A file written for streaming puts it
 * first; a file written by most encoders puts it *after* the audio, which can be
 * a hundred megabytes away. So the top level is walked off the stream rather
 * than buffered: uninteresting atoms — `mdat` above all — are skipped without
 * being read into memory, and only `moov` is materialised.
 */
internal object Mp4Reader {

    /**
     * @param magic the first four bytes, already consumed by the dispatcher —
     *   for an MP4 these are the size of the leading `ftyp` atom.
     */
    fun lyrics(magic: ByteArray, input: InputStream): String? {
        val firstSize = ByteReader(magic).u32BE()
        if (input.readUpTo(4).toString(Charsets.US_ASCII) != "ftyp") return null

        // Past the rest of ftyp, whose contents say nothing about lyrics.
        if (firstSize < HEADER_BYTES || !input.skipExactly(firstSize - HEADER_BYTES)) return null

        val moov = findTopLevel("moov", input) ?: return null
        return lyricsIn(moov)
    }

    /** Walks sibling atoms off the stream until one of [type] turns up. */
    private fun findTopLevel(type: String, input: InputStream): ByteArray? {
        var scanned = 0L

        while (scanned < MAX_SCAN_BYTES) {
            val header = input.readUpTo(HEADER_BYTES.toInt())
            if (header.size < HEADER_BYTES) return null

            val reader = ByteReader(header)
            var size = reader.u32BE()
            val name = reader.latin1(4) ?: return null
            var headerSize = HEADER_BYTES

            when (size) {
                // 1 means the real size is a 64-bit value after the type.
                1L -> {
                    val extended = input.readUpTo(8)
                    if (extended.size < 8) return null
                    size = ByteReader(extended).let { (it.u32BE() shl 32) or it.u32BE() }
                    headerSize = HEADER_BYTES + 8
                }
                // 0 means "to the end of the file".
                0L -> size = Long.MAX_VALUE
            }

            val payload = size - headerSize
            if (payload < 0) return null

            if (name == type) {
                if (payload > MAX_MOOV_BYTES) return null
                return input.readUpTo(payload.toInt())
            }

            if (payload == Long.MAX_VALUE - headerSize) return null
            if (!input.skipExactly(payload)) return null
            scanned += size
        }
        return null
    }

    /** Descends `udta/meta/ilst` inside a buffered `moov`. */
    private fun lyricsIn(moov: ByteArray): String? {
        val udta = childNamed("udta", moov, 0, moov.size) ?: return null
        val meta = childNamed("meta", udta, 0, udta.size) ?: return null
        val ilst = ilstIn(meta) ?: return null
        return lyricAtom(ilst)
    }

    /**
     * `meta` is a full box: four bytes of version and flags before its
     * children. Enough writers omit them that both layouts have to be tried,
     * and trying is cheap — the wrong guess simply finds no child.
     */
    private fun ilstIn(meta: ByteArray): ByteArray? =
        childNamed("ilst", meta, 4, meta.size) ?: childNamed("ilst", meta, 0, meta.size)

    /** The lyric text under an `ilst`, from either place iTunes puts it. */
    private fun lyricAtom(ilst: ByteArray): String? {
        childNamed(LYRICS_ATOM, ilst, 0, ilst.size)
            ?.let { return textIn(it) }

        // The freeform form: ---- carrying `mean` (the vendor), `name` (the
        // field) and `data`. Used by taggers that avoid the ©-prefixed set.
        var cursor = 0
        while (true) {
            val (freeform, next) = childAt("----", ilst, cursor) ?: return null
            if (freeform != null && namesLyrics(freeform)) {
                textIn(freeform)?.let { return it }
            }
            if (next <= cursor) return null
            cursor = next
        }
    }

    private fun namesLyrics(freeform: ByteArray): Boolean {
        val name = childNamed("name", freeform, 0, freeform.size) ?: return false
        // Past the version and flags that precede the name itself.
        if (name.size <= 4) return false
        val field = name.copyOfRange(4, name.size).toString(Charsets.UTF_8).trim()
        return field.equals("LYRICS", ignoreCase = true) ||
            field.equals("UNSYNCEDLYRICS", ignoreCase = true)
    }

    /**
     * The text inside an item's `data` atom, past its type indicator and
     * locale. A type of 1 means UTF-8; 0 is used by writers that leave it
     * unset, and in practice also means UTF-8.
     */
    private fun textIn(item: ByteArray): String? {
        val data = childNamed("data", item, 0, item.size) ?: return null
        if (data.size <= DATA_PREFIX_BYTES) return null

        val reader = ByteReader(data)
        val type = reader.u32BE() and 0xFFFFFF
        if (type != 0L && type != 1L) return null
        reader.skip(4) // locale

        return reader.rest().toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
    }

    /** The payload of the first child atom called [type], or null. */
    private fun childNamed(type: String, parent: ByteArray, from: Int, until: Int): ByteArray? {
        var cursor = from
        while (cursor < until) {
            val (payload, next) = childAt(type, parent, cursor) ?: return null
            if (payload != null) return payload
            if (next <= cursor) return null
            cursor = next
        }
        return null
    }

    /**
     * Reads one atom header at [cursor].
     *
     * @return the payload if this atom is a [type], paired with where the next
     *   atom starts; or null when the header does not parse.
     */
    private fun childAt(type: String, parent: ByteArray, cursor: Int): Pair<ByteArray?, Int>? {
        val reader = ByteReader(parent, cursor)
        if (!reader.canRead(HEADER_BYTES.toInt())) return null

        val size = reader.u32BE()
        val name = reader.latin1(4) ?: return null

        // A size of zero would loop forever, and one below the header is
        // nonsense; either way the atom chain has stopped making sense.
        if (size < HEADER_BYTES) return null

        val end = cursor + size.toInt()
        if (size > Int.MAX_VALUE || end > parent.size || end < cursor) return null

        val payload = if (name == type) {
            parent.copyOfRange(cursor + HEADER_BYTES.toInt(), end)
        } else {
            null
        }
        return payload to end
    }

    /** The `©lyr` atom, whose first byte is 0xA9 rather than an ASCII letter. */
    private val LYRICS_ATOM = String(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(),
        'y'.code.toByte(), 'r'.code.toByte()), Charsets.ISO_8859_1)

    private const val HEADER_BYTES = 8L

    /** Type indicator plus locale, ahead of the text in a `data` atom. */
    private const val DATA_PREFIX_BYTES = 8

    /** Tags are not this big; a file claiming otherwise is not worth reading. */
    private const val MAX_MOOV_BYTES = 8L * 1024 * 1024

    /**
     * How far into a file to look for `moov`. Generous, because it is legal to
     * put it last, and the audio in between is skipped rather than read.
     */
    private const val MAX_SCAN_BYTES = 2L * 1024 * 1024 * 1024
}

/**
 * Skips exactly [count] bytes, or gives up.
 *
 * `InputStream.skip` is allowed to skip fewer bytes than asked for any reason it
 * likes, including none at all, so a single call cannot be trusted. Returns
 * false at end of stream, which for these callers means the file ended before
 * the atom it promised.
 */
internal fun InputStream.skipExactly(count: Long): Boolean {
    var left = count
    while (left > 0) {
        val skipped = skip(left)
        if (skipped > 0) {
            left -= skipped
            continue
        }
        // Some streams return 0 rather than blocking; a single read tells us
        // whether that meant "not yet" or "never".
        if (read() < 0) return false
        left--
    }
    return true
}
