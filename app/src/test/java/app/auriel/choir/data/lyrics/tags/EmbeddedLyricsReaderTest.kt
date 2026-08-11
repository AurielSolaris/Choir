// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.tags

import app.auriel.choir.data.lyrics.LyricLine
import app.auriel.choir.data.lyrics.RawLyrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

private fun read(file: ByteArray): RawLyrics? =
    EmbeddedLyricsReader.read(ByteArrayInputStream(file))

private fun textOf(file: ByteArray): String? = (read(file) as? RawLyrics.Text)?.value

private fun timedOf(file: ByteArray): List<LyricLine>? = (read(file) as? RawLyrics.Timed)?.lines

class Id3v2ReaderTest {

    @Test
    fun `a v2_3 USLT frame in Latin-1 reads back`() {
        val file = id3Tag(3, Id3Frame("USLT", uslt("Some words", encoding = 0)))

        assertEquals("Some words", textOf(file))
    }

    @Test
    fun `a v2_4 USLT frame in UTF-8 keeps its accents`() {
        val file = id3Tag(4, Id3Frame("USLT", uslt("Déjà vu — again", encoding = 3)))

        assertEquals("Déjà vu — again", textOf(file))
    }

    @Test
    fun `UTF-16 with a byte order mark is decoded, not read as bytes`() {
        val file = id3Tag(4, Id3Frame("USLT", uslt("Καλημέρα", encoding = 1)))

        assertEquals("Καλημέρα", textOf(file))
    }

    @Test
    fun `UTF-16BE without a mark is decoded too`() {
        val file = id3Tag(4, Id3Frame("USLT", uslt("Καλημέρα", encoding = 2)))

        assertEquals("Καλημέρα", textOf(file))
    }

    @Test
    fun `a descriptor before the lyric is skipped, not returned as the lyric`() {
        val file = id3Tag(4, Id3Frame("USLT", uslt("The words", descriptor = "Lyrics")))

        assertEquals("The words", textOf(file))
    }

    @Test
    fun `the v2_2 three-character frame id is understood`() {
        val file = id3Tag(2, Id3Frame("ULT", uslt("Old tag", encoding = 0)))

        assertEquals("Old tag", textOf(file))
    }

    @Test
    fun `a frame after a large one is still found`() {
        // The real shape of a tagged MP3: cover art first, lyrics behind it.
        val file = id3Tag(
            4,
            Id3Frame("APIC", ByteArray(50_000)),
            Id3Frame("USLT", uslt("Behind the artwork")),
        )

        assertEquals("Behind the artwork", textOf(file))
    }

    @Test
    fun `SYLT gives timed lines directly`() {
        val file = id3Tag(
            4,
            Id3Frame("SYLT", sylt(listOf(1_000L to "First", 5_500L to "Second"))),
        )

        assertEquals(
            listOf(LyricLine(1_000, "First"), LyricLine(5_500, "Second")),
            timedOf(file),
        )
    }

    @Test
    fun `SYLT wins over USLT, because it is the one that is timed`() {
        val file = id3Tag(
            4,
            Id3Frame("USLT", uslt("Untimed words")),
            Id3Frame("SYLT", sylt(listOf(2_000L to "Timed words"))),
        )

        assertEquals(listOf(LyricLine(2_000, "Timed words")), timedOf(file))
    }

    @Test
    fun `SYLT counted in MPEG frames is refused rather than guessed at`() {
        // Timestamp format 1 counts frames, which cannot become a time without
        // decoding the audio.
        val file = id3Tag(
            4,
            Id3Frame("SYLT", sylt(listOf(1_000L to "First"), timestampFormat = 1)),
        )

        assertNull(read(file))
    }

    @Test
    fun `SYLT carrying something other than lyrics is ignored`() {
        // Content type 2 is chords, not words.
        val file = id3Tag(4, Id3Frame("SYLT", sylt(listOf(1_000L to "Am"), contentType = 2)))

        assertNull(read(file))
    }

    @Test
    fun `TXXX LYRICS is read when nothing better exists`() {
        val file = id3Tag(4, Id3Frame("TXXX", txxx("LYRICS", "From a custom frame")))

        assertEquals("From a custom frame", textOf(file))
    }

    @Test
    fun `an unrelated TXXX frame is not mistaken for lyrics`() {
        val file = id3Tag(4, Id3Frame("TXXX", txxx("REPLAYGAIN_TRACK_GAIN", "-3.21 dB")))

        assertNull(read(file))
    }

    @Test
    fun `USLT is preferred to TXXX when a file carries both`() {
        val file = id3Tag(
            4,
            Id3Frame("TXXX", txxx("LYRICS", "The custom one")),
            Id3Frame("USLT", uslt("The standard one")),
        )

        assertEquals("The standard one", textOf(file))
    }

    @Test
    fun `an unsynchronised v2_3 tag is decoded back to its real bytes`() {
        // $FF $00 in the tag stands for a literal $FF.
        val frame = uslt("A", encoding = 0)
        val tag = id3Tag(3, Id3Frame("USLT", frame), tagFlags = 0x80)

        assertEquals("A", textOf(tag))
    }

    @Test
    fun `a tag with no lyric frames gives nothing`() {
        val file = id3Tag(4, Id3Frame("TIT2", bytes(3, "A title")))

        assertNull(read(file))
    }

    @Test
    fun `a truncated tag ends the parse instead of throwing`() {
        val full = id3Tag(4, Id3Frame("USLT", uslt("Some words")))

        for (cut in 1..full.size) {
            // Every prefix of a real tag must be survivable.
            read(full.copyOf(cut))
        }
    }

    @Test
    fun `a file that is not a tag at all gives nothing`() {
        assertNull(read(ByteArray(0)))
        assertNull(read("not a tag, just bytes".toByteArray()))
        assertNull(read(bytes(0xFF, 0xFB, 0x90, 0x00))) // a bare MP3 frame header
    }
}

class VorbisCommentReaderTest {

    @Test
    fun `a FLAC LYRICS field is found`() {
        val file = flacFile(
            0 to ByteArray(34), // STREAMINFO
            4 to vorbisCommentBlock("ARTIST=Someone", "LYRICS=The words"),
        )

        assertEquals("The words", textOf(file))
    }

    @Test
    fun `the comment block is found behind a picture block`() {
        val file = flacFile(
            0 to ByteArray(34),
            6 to ByteArray(20_000), // PICTURE
            4 to vorbisCommentBlock("LYRICS=Behind the artwork"),
        )

        assertEquals("Behind the artwork", textOf(file))
    }

    @Test
    fun `UNSYNCEDLYRICS is accepted as the field name too`() {
        val file = flacFile(4 to vorbisCommentBlock("UNSYNCEDLYRICS=Alternate field"))

        assertEquals("Alternate field", textOf(file))
    }

    @Test
    fun `field names are matched without regard to case`() {
        val file = flacFile(4 to vorbisCommentBlock("lyrics=Lower case key"))

        assertEquals("Lower case key", textOf(file))
    }

    @Test
    fun `a FLAC with tags but no lyrics gives nothing`() {
        val file = flacFile(4 to vorbisCommentBlock("ARTIST=Someone", "ALBUM=Something"))

        assertNull(read(file))
    }

    @Test
    fun `a value containing an equals sign survives intact`() {
        val file = flacFile(4 to vorbisCommentBlock("LYRICS=x = y, and then some"))

        assertEquals("x = y, and then some", textOf(file))
    }

    @Test
    fun `an Ogg Vorbis comment header is read from the second packet`() {
        val file = oggPage(
            listOf(
                VORBIS_IDENTIFICATION + ByteArray(23),
                VORBIS_COMMENT_HEADER + vorbisCommentBlock("LYRICS=Ogg words"),
            ),
        )

        assertEquals("Ogg words", textOf(file))
    }

    @Test
    fun `an Opus tags packet is read the same way`() {
        val file = oggPage(
            listOf(
                "OpusHead".toByteArray() + ByteArray(11),
                OPUS_TAGS_HEADER + vorbisCommentBlock("LYRICS=Opus words"),
            ),
        )

        assertEquals("Opus words", textOf(file))
    }

    @Test
    fun `a comment packet split across two pages is reassembled`() {
        // A lyric worth having is long enough to cross a page boundary, so the
        // segments have to be joined rather than scanned.
        val long = "LYRICS=" + "a lyric line\n".repeat(60)
        val packet = VORBIS_COMMENT_HEADER + vorbisCommentBlock(long)
        val identification = VORBIS_IDENTIFICATION + ByteArray(23)

        // The first page ends on a full 255-byte segment, which is exactly how
        // Ogg says "there is more of this in the next page".
        val head = packet.copyOfRange(0, 255)
        val tail = packet.copyOfRange(255, packet.size)

        val file = oggRawPage(
            segments = segmentsFor(identification) + listOf(255),
            data = identification + head,
            sequence = 0,
        ) + oggRawPage(
            segments = segmentsFor(tail),
            data = tail,
            sequence = 1,
        )

        assertEquals(long.removePrefix("LYRICS="), textOf(file))
    }

    @Test
    fun `an Ogg file whose second packet is not a comment header gives nothing`() {
        val file = oggPage(
            listOf(VORBIS_IDENTIFICATION + ByteArray(23), byteArrayOf(5) + "vorbis".toByteArray()),
        )

        assertNull(read(file))
    }

    @Test
    fun `a truncated container ends the parse instead of throwing`() {
        val flac = flacFile(4 to vorbisCommentBlock("LYRICS=Words"))
        val ogg = oggPage(
            listOf(
                VORBIS_IDENTIFICATION + ByteArray(23),
                VORBIS_COMMENT_HEADER + vorbisCommentBlock("LYRICS=Words"),
            ),
        )

        for (cut in 1..flac.size) read(flac.copyOf(cut))
        for (cut in 1..ogg.size) read(ogg.copyOf(cut))
        assertTrue(true, "every prefix parsed without throwing")
    }
}
