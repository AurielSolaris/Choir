// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import app.auriel.choir.data.lyrics.RawLyrics
import java.io.InputStream

/**
 * Finds whatever lyrics a file carries inside itself.
 *
 * Dispatches on the first four bytes rather than on a file extension, because
 * the extension is a claim and the magic number is evidence.
 *
 * Only as much of the file as the tag needs is ever read. For ID3, FLAC and Ogg
 * that is the head and nothing more. MP4 and the IFF formats are allowed to put
 * their metadata after the audio, so those walk the container and *skip* past
 * anything uninteresting — a lyric is never worth paging a hundred megabytes of
 * samples through memory.
 */
internal object EmbeddedLyricsReader {

    fun read(input: InputStream): RawLyrics? = runCatching { readOrThrow(input) }.getOrNull()

    private fun readOrThrow(input: InputStream): RawLyrics? {
        val magic = input.readUpTo(4)
        if (magic.size < 4) return null

        return when {
            magic.startsWith("ID3") -> readId3(magic, input)
            magic.startsWith("fLaC") -> Flac.lyrics(input.readUpTo(MAX_HEAD_BYTES))?.asText()
            magic.startsWith("OggS") -> readOgg(magic, input)
            // WAVE and AIFF both hide a whole ID3v2 tag in a chunk. The only
            // thing separating them here is which way round the lengths go.
            magic.startsWith("RIFF") -> readIff(input, littleEndian = true)
            magic.startsWith("FORM") -> readIff(input, littleEndian = false)
            // Everything else gets tried as MP4, which is the one container
            // whose signature is not at the start of the file — an MP4 opens
            // with the length of its first atom, and only then says `ftyp`.
            else -> Mp4Reader.lyrics(magic, input)?.asText()
        }
    }

    private fun readIff(input: InputStream, littleEndian: Boolean): RawLyrics? {
        val tag = IffId3.lyrics(input, littleEndian) ?: return null
        return Id3v2Reader.read(tag.header, tag.body)
    }

    private fun readId3(magic: ByteArray, input: InputStream): RawLyrics? {
        // The magic already took four of the ten header bytes.
        val header = magic + input.readUpTo(6)
        if (header.size < 10) return null

        val size = ByteReader(header, position = 6).syncsafe32()
        if (size <= 0 || size > Id3v2Reader.MAX_TAG_BYTES) return null

        val body = input.readUpTo(size.toInt())
        return Id3v2Reader.read(header, body)
    }

    private fun readOgg(magic: ByteArray, input: InputStream): RawLyrics? {
        val body = magic + input.readUpTo(MAX_HEAD_BYTES)
        return Ogg.lyrics(body)?.asText()
    }

    /**
     * A Vorbis comment is plain text, but the convention of storing an entire
     * LRC document in the `LYRICS` field is common enough that it goes back
     * through the LRC parser upstream — hence [RawLyrics.Text], not a decision.
     */
    private fun String.asText(): RawLyrics = RawLyrics.Text(this)

    private fun ByteArray.startsWith(prefix: String): Boolean {
        val expected = prefix.toByteArray(Charsets.US_ASCII)
        return size >= expected.size && expected.indices.all { this[it] == expected[it] }
    }

    /**
     * Enough for FLAC's metadata blocks (a picture block can be large) and for
     * the first Ogg pages, without ever being enough to hurt.
     */
    private const val MAX_HEAD_BYTES = 4 * 1024 * 1024
}
