// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import app.auriel.choir.data.lyrics.RawLyrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

private fun textIn(file: ByteArray): String? =
    (EmbeddedLyricsReader.read(ByteArrayInputStream(file)) as? RawLyrics.Text)?.value

/**
 * A stream that refuses to skip, and hands back one byte at a time.
 *
 * Real `InputStream`s are allowed to do both — a content-provider stream over a
 * pipe frequently does — and a parser that assumes otherwise silently reads the
 * wrong bytes. Nothing about this is pathological; it is just the contract.
 */
private class AwkwardStream(bytes: ByteArray) : InputStream() {
    private val source = ByteArrayInputStream(bytes)
    override fun read(): Int = source.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        if (len == 0) 0 else source.read(b, off, 1)
    override fun skip(n: Long): Long = 0
}

@DisplayName("MP4 lyrics")
class Mp4ReaderTest {

    @Test
    fun `reads a lyric from the (c)lyr atom`() {
        val file = mp4File(listOf(atom(LYRICS_ATOM, dataAtom("Words in an m4a"))))

        assertEquals("Words in an m4a", textIn(file))
    }

    @Test
    fun `keeps the line breaks that make it a lyric`() {
        val file = mp4File(listOf(atom(LYRICS_ATOM, dataAtom("First line\nSecond line"))))

        assertEquals("First line\nSecond line", textIn(file))
    }

    @Test
    fun `decodes UTF-8 rather than handing back bytes`() {
        val file = mp4File(listOf(atom(LYRICS_ATOM, dataAtom("Déjà vu — 日本語"))))

        assertEquals("Déjà vu — 日本語", textIn(file))
    }

    /**
     * The case that matters most in practice: most encoders write the audio
     * first and the metadata last, so the reader has to walk past a large mdat
     * rather than give up on it.
     */
    @Test
    fun `finds moov when it sits after the audio`() {
        val file = mp4File(
            listOf(atom(LYRICS_ATOM, dataAtom("Behind the audio"))),
            moovLast = true,
            mdatBytes = 512 * 1024,
        )

        assertEquals("Behind the audio", textIn(file))
    }

    @Test
    fun `walks past the audio without relying on skip`() {
        val file = mp4File(
            listOf(atom(LYRICS_ATOM, dataAtom("Read the hard way"))),
            moovLast = true,
            mdatBytes = 64 * 1024,
        )

        val lyrics = EmbeddedLyricsReader.read(AwkwardStream(file)) as? RawLyrics.Text
        assertEquals("Read the hard way", lyrics?.value)
    }

    /**
     * `meta` is a full box and should carry four bytes of version and flags,
     * but plenty of taggers omit them. Both layouts have to work.
     */
    @Test
    fun `handles a meta atom written without its version and flags`() {
        val file = mp4File(
            listOf(atom(LYRICS_ATOM, dataAtom("Nonstandard but common"))),
            metaHasVersionFlags = false,
        )

        assertEquals("Nonstandard but common", textIn(file))
    }

    @Test
    fun `reads the freeform iTunes LYRICS field`() {
        val file = mp4File(listOf(freeformAtom("LYRICS", "From a freeform atom")))

        assertEquals("From a freeform atom", textIn(file))
    }

    @Test
    fun `reads the UNSYNCEDLYRICS spelling too`() {
        val file = mp4File(listOf(freeformAtom("UNSYNCEDLYRICS", "Also these")))

        assertEquals("Also these", textIn(file))
    }

    @Test
    fun `ignores a freeform atom naming some other field`() {
        val file = mp4File(listOf(freeformAtom("REPLAYGAIN_TRACK_GAIN", "-7.5 dB")))

        assertNull(textIn(file))
    }

    @Test
    fun `skips other metadata to reach the lyric`() {
        val file = mp4File(
            listOf(
                atom("©nam", dataAtom("A title")),
                atom("©ART", dataAtom("An artist")),
                atom(LYRICS_ATOM, dataAtom("The words")),
                atom("©alb", dataAtom("An album")),
            ),
        )

        assertEquals("The words", textIn(file))
    }

    @Test
    fun `an m4a with no lyric atom yields nothing`() {
        val file = mp4File(listOf(atom("©nam", dataAtom("A title"))))

        assertNull(textIn(file))
    }

    @Test
    fun `an empty lyric is nothing, not an empty pane`() {
        val file = mp4File(listOf(atom(LYRICS_ATOM, dataAtom("   "))))

        assertNull(textIn(file))
    }

    @Test
    fun `a truncated file ends the parse instead of throwing`() {
        val whole = mp4File(listOf(atom(LYRICS_ATOM, dataAtom("Never arrives"))))

        for (length in 1 until whole.size) {
            // The only requirement is that it returns; a partial file may
            // legitimately yield either the lyric or nothing.
            textIn(whole.copyOfRange(0, length))
        }
    }

    @Test
    fun `an atom claiming a nonsense size does not loop forever`() {
        val file = bytes(
            atom("ftyp", bytes("M4A ", u32be(0))),
            u32be(0), "moov", // a zero size would be an infinite loop
        )

        assertNull(textIn(file))
    }

    @Test
    fun `something that is not an MP4 at all is declined`() {
        assertNull(textIn(bytes(u32be(24), "junk", ByteArray(16))))
    }
}

@DisplayName("WAVE and AIFF lyrics")
class IffReaderTest {

    @Test
    fun `reads an ID3 tag out of a WAVE id3 chunk`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("Words in a wav")))
        val file = waveFile(
            iffChunk("fmt ", ByteArray(16), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        assertEquals("Words in a wav", textIn(file))
    }

    /**
     * A WAVE's `data` chunk is the entire song. Finding a tag behind it is the
     * normal case, not an edge one — taggers append rather than rewrite.
     */
    @Test
    fun `finds a tag written after the audio data`() {
        val tag = id3Tag(4, Id3Frame("USLT", uslt("After the samples")))
        val file = waveFile(
            iffChunk("fmt ", ByteArray(16), littleEndian = true),
            iffChunk("data", ByteArray(256 * 1024), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        assertEquals("After the samples", textIn(file))
    }

    /**
     * An odd-length chunk is followed by a pad byte that its declared size does
     * not count. Miss it and every later chunk is read one byte out of step.
     */
    @Test
    fun `accounts for the pad byte after an odd-length chunk`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("Still aligned")))
        val file = waveFile(
            iffChunk("fmt ", ByteArray(17), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        assertEquals("Still aligned", textIn(file))
    }

    @Test
    fun `reads the uppercase spelling, which AIFF prefers`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("Words in an aiff")))
        val file = aiffFile(
            iffChunk("COMM", ByteArray(18), littleEndian = false),
            iffChunk("ID3 ", tag, littleEndian = false),
        )

        assertEquals("Words in an aiff", textIn(file))
    }

    @Test
    fun `reads a synced SYLT frame through the chunk, not only plain text`() {
        val tag = id3Tag(4, Id3Frame("SYLT", sylt(listOf(0L to "First", 2_000L to "Second"))))
        val file = waveFile(iffChunk("id3 ", tag, littleEndian = true))

        val lines = (EmbeddedLyricsReader.read(ByteArrayInputStream(file)) as? RawLyrics.Timed)?.lines
        assertEquals(listOf(0L, 2_000L), lines?.map { it.timeMs })
    }

    @Test
    fun `walks a WAVE without relying on skip`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("One byte at a time")))
        val file = waveFile(
            iffChunk("data", ByteArray(32 * 1024), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        val lyrics = EmbeddedLyricsReader.read(AwkwardStream(file)) as? RawLyrics.Text
        assertEquals("One byte at a time", lyrics?.value)
    }

    @Test
    fun `a WAVE with no tag chunk yields nothing`() {
        val file = waveFile(
            iffChunk("fmt ", ByteArray(16), littleEndian = true),
            iffChunk("data", ByteArray(64), littleEndian = true),
        )

        assertNull(textIn(file))
    }

    @Test
    fun `a truncated WAVE ends the parse instead of throwing`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("Never arrives")))
        val whole = waveFile(
            iffChunk("data", ByteArray(64), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        for (length in 1 until whole.size) {
            textIn(whole.copyOfRange(0, length))
        }
    }

    /**
     * Reading a little-endian length as big-endian turns 16 into 268435456, so
     * getting this backwards is not a subtle failure — but it is a silent one,
     * because the parse simply finds nothing.
     */
    @Test
    fun `does not read a WAVE as though it were big-endian`() {
        val tag = id3Tag(3, Id3Frame("USLT", uslt("Correct byte order")))
        val file = waveFile(
            iffChunk("fmt ", ByteArray(16), littleEndian = true),
            iffChunk("id3 ", tag, littleEndian = true),
        )

        // The same bytes with a FORM header would be walked big-endian and find
        // nothing, which is what proves the flag is doing the work.
        val asAiff = bytes("FORM") + file.copyOfRange(4, file.size)
        assertEquals("Correct byte order", textIn(file))
        assertNull(textIn(asAiff))
    }
}
