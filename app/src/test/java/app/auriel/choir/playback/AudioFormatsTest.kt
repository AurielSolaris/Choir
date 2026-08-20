// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import app.auriel.choir.playback.AudioFormats.Demuxer
import app.auriel.choir.playback.AudioFormats.Playability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class AudioFormatsTest {

    @Nested
    @DisplayName("reading the extension")
    inner class Extensions {

        @Test
        fun `lowercases what it finds`() {
            assertEquals("flac", AudioFormats.extensionOf("Song.FLAC"))
        }

        @Test
        fun `takes the last dot, not the first`() {
            assertEquals("m4a", AudioFormats.extensionOf("probe.alac.m4a"))
        }

        @Test
        fun `has nothing to say about a name without one`() {
            assertNull(AudioFormats.extensionOf("Song"))
            assertNull(AudioFormats.extensionOf(""))
            assertNull(AudioFormats.extensionOf(null))
        }

        @Test
        fun `a trailing dot is not an extension`() {
            assertNull(AudioFormats.extensionOf("Song."))
        }

        @Test
        fun `a leading dot is a hidden file, not an extension`() {
            assertNull(AudioFormats.extensionOf(".flac"))
        }
    }

    @Nested
    @DisplayName("identifying a file")
    inner class Identify {

        @Test
        fun `recognises a format by extension alone`() {
            assertEquals("WavPack", AudioFormats.identify("track.wv", null)?.label)
        }

        @Test
        fun `recognises a format by mime type alone`() {
            assertEquals("Windows Media Audio", AudioFormats.identify(null, "audio/x-ms-wma")?.label)
        }

        /**
         * The case that motivated preferring the extension: MediaStore types
         * anything its scanner could not read as a generic byte stream, and the
         * filename is the only clue left.
         */
        @Test
        fun `prefers the extension over a useless mime type`() {
            val format = AudioFormats.identify("probe.wv", "application/octet-stream")
            assertEquals("WavPack", format?.label)
        }

        @Test
        fun `ignores mime type parameters`() {
            assertNotNull(AudioFormats.identify(null, "audio/mpeg; charset=binary"))
        }

        @Test
        fun `is not case sensitive about mime types`() {
            assertEquals("FLAC", AudioFormats.identify(null, "Audio/FLAC")?.label)
        }

        @Test
        fun `admits when it does not know`() {
            assertNull(AudioFormats.identify("notes.txt", "text/plain"))
            assertEquals(Playability.UNKNOWN, AudioFormats.playabilityOf("notes.txt", "text/plain"))
        }
    }

    @Nested
    @DisplayName("what will actually play")
    inner class Playable {

        @Test
        fun `the formats every Android device must decode are native`() {
            listOf("song.mp3", "song.m4a", "song.flac", "song.ogg", "song.opus", "song.wav")
                .forEach { assertEquals(Playability.NATIVE, AudioFormats.playabilityOf(it, null), it) }
        }

        /**
         * The distinction the whole table exists for. A decoder is not enough
         * for these — Media3 has no extractor that can open the container, so
         * shipping every codec FFmpeg has would still not play them.
         */
        @Test
        fun `lossless formats in unreadable containers need a demuxer, not a decoder`() {
            listOf("song.tta", "song.mpc", "song.dsf", "song.tak", "song.shn")
                .forEach {
                    assertEquals(Playability.NEEDS_DEMUXER, AudioFormats.playabilityOf(it, null), it)
                }
        }

        /**
         * The three containers v0.4.0 closed. Each had a decoder in the FFmpeg
         * build from v0.3.0 and was unplayable anyway, which is the case the
         * two axes were separated to describe; supplying the demuxer moves them
         * from "nothing can open this" to "this plays where the decoder is".
         */
        @Test
        fun `the containers Choir now opens itself need only the decoder`() {
            listOf("song.ape", "song.wma", "song.wv").forEach {
                assertEquals(Demuxer.CHOIR, AudioFormats.identify(it, null)?.demuxer, it)
                assertEquals(Playability.NEEDS_DECODER, AudioFormats.playabilityOf(it, null), it)
            }
        }

        /**
         * AIFF was the one gap worth closing by hand — plain PCM behind a
         * container Media3 would not open — so it now plays on a build with no
         * FFmpeg in it at all.
         */
        @Test
        fun `AIFF plays through Choir's own reader, needing no decoder`() {
            val aiff = AudioFormats.identify("song.aiff", null)
            assertEquals(Demuxer.CHOIR, aiff?.demuxer)
            assertEquals(AudioFormats.Codec.PLATFORM, aiff?.codec)
            assertEquals(Playability.NATIVE, aiff?.playability)
        }

        /**
         * WavPack is the other half of the same story, and the one that shows
         * why the two axes are kept apart: the decoder arrived in v0.3.0 with
         * FFmpeg and changed nothing, because nothing could open the container.
         * v0.4.0 supplies the demuxer, and only then does a `.wv` play.
         */
        @Test
        fun `WavPack opens with Choir's own reader and still needs the decoder`() {
            val wavpack = AudioFormats.identify("song.wv", null)
            assertEquals(Demuxer.CHOIR, wavpack?.demuxer)
            assertEquals(AudioFormats.Codec.FFMPEG, wavpack?.codec)
            assertEquals(Playability.NEEDS_DECODER, wavpack?.playability)
        }

        @Test
        fun `Dolby streams are a maybe, because the decoder is the device's choice`() {
            assertEquals(Playability.LIKELY, AudioFormats.playabilityOf("movie.ac3", null))
            assertEquals(Playability.LIKELY, AudioFormats.playabilityOf("movie.eac3", null))
        }
    }

    @Nested
    @DisplayName("trusting the scanner")
    inner class ScannerBlind {

        @Test
        fun `the scanner reads what the platform can decode`() {
            assertFalse(AudioFormats.isScannerBlind("song.mp3", "audio/mpeg"))
            assertFalse(AudioFormats.isScannerBlind("song.flac", "audio/flac"))
        }

        /**
         * Verified on a Samsung SM-M315F running Android 16: pushing these and
         * rescanning produced rows with a null duration in every case.
         */
        @Test
        fun `the scanner does not read the formats it cannot decode`() {
            assertTrue(AudioFormats.isScannerBlind("probe.aiff", "audio/x-aiff"))
            assertTrue(AudioFormats.isScannerBlind("probe.wma", "audio/x-ms-wma"))
            assertTrue(AudioFormats.isScannerBlind("probe.wv", "application/octet-stream"))
        }

        @Test
        fun `an unrecognised file is treated as one the scanner may have missed`() {
            assertTrue(AudioFormats.isScannerBlind("mystery.zzz", null))
        }

        /**
         * The two questions are unrelated, and conflating them was a real bug:
         * Choir can now play an AIFF, and Android's scanner still cannot read
         * one, so its duration and title are still not to be trusted.
         */
        @Test
        fun `playing a format is not the same as the scanner understanding it`() {
            assertEquals(Playability.NATIVE, AudioFormats.playabilityOf("song.aiff", null))
            assertTrue(AudioFormats.isScannerBlind("song.aiff", null))
        }

        @Test
        fun `a Dolby stream plays but is still filed without a duration`() {
            assertEquals(Playability.LIKELY, AudioFormats.playabilityOf("movie.ac3", null))
            assertTrue(AudioFormats.isScannerBlind("movie.ac3", null))
        }
    }

    @Nested
    @DisplayName("the table itself")
    inner class Table {

        @Test
        fun `no extension is claimed by two formats`() {
            val extensions = AudioFormats.all.flatMap { it.extensions }
            assertEquals(extensions.size, extensions.toSet().size, "duplicate extension: $extensions")
        }

        @Test
        fun `no mime type is claimed by two formats`() {
            val types = AudioFormats.all.flatMap { it.mimeTypes }
            assertEquals(types.size, types.toSet().size, "duplicate mime type: $types")
        }

        @Test
        fun `every extension and mime type is already lowercase`() {
            AudioFormats.all.forEach { format ->
                format.extensions.forEach { assertEquals(it.lowercase(), it) }
                format.mimeTypes.forEach { assertEquals(it.lowercase(), it) }
            }
        }

        @Test
        fun `every format identifies itself by each of its own clues`() {
            AudioFormats.all.forEach { format ->
                format.extensions.forEach {
                    assertEquals(format, AudioFormats.identify("song.$it", null))
                }
                format.mimeTypes.forEach {
                    assertEquals(format, AudioFormats.identify(null, it))
                }
            }
        }
    }
}
