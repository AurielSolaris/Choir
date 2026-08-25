// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Which lyric wins.
 *
 * The rule has two halves and they are decided by different things. Between a
 * sidecar and a tag — both the file's own words — the more useful one wins, and
 * that means the timed one. Between anything local and anything fetched, the
 * local one wins outright, because a lookup keyed on a title and an artist is a
 * guess about which recording is playing and the file is not a guess.
 *
 * [betterOf] is only the first half. The second is in
 * [LyricsRepository.forTrack], which never calls it with a fetched result.
 */
class LyricsPrecedenceTest {

    private fun lyrics(source: LyricsSource, synced: Boolean, text: String = "words") = Lyrics(
        lines = listOf(LyricLine(if (synced) 0L else LyricLine.NO_TIME, text)),
        isSynced = synced,
        source = source,
    )

    @Nested
    @DisplayName("between two local sources")
    inner class Local {

        @Test
        fun `a timed tag beats a plain sidecar`() {
            val sidecar = lyrics(LyricsSource.SIDECAR, synced = false)
            val embedded = lyrics(LyricsSource.EMBEDDED, synced = true)

            assertSame(embedded, betterOf(sidecar, embedded))
        }

        @Test
        fun `a timed sidecar beats a plain tag`() {
            val sidecar = lyrics(LyricsSource.SIDECAR, synced = true)
            val embedded = lyrics(LyricsSource.EMBEDDED, synced = false)

            assertSame(sidecar, betterOf(sidecar, embedded))
        }

        /**
         * The sidecar is tried first, so it holds the tie. Someone putting a
         * `.lrc` next to a file is a more deliberate act than a tagger writing
         * a lyric frame.
         */
        @Test
        fun `the first source keeps a tie between two plain ones`() {
            val sidecar = lyrics(LyricsSource.SIDECAR, synced = false, text = "from the sidecar")
            val embedded = lyrics(LyricsSource.EMBEDDED, synced = false, text = "from the tag")

            assertEquals("from the sidecar", betterOf(sidecar, embedded)?.lines?.first()?.text)
        }

        @Test
        fun `the first source keeps a tie between two timed ones`() {
            val sidecar = lyrics(LyricsSource.SIDECAR, synced = true, text = "from the sidecar")
            val embedded = lyrics(LyricsSource.EMBEDDED, synced = true, text = "from the tag")

            assertEquals("from the sidecar", betterOf(sidecar, embedded)?.lines?.first()?.text)
        }
    }

    @Nested
    @DisplayName("when one side has nothing")
    inner class Missing {

        @Test
        fun `the one that exists wins, whichever side it is on`() {
            val found = lyrics(LyricsSource.EMBEDDED, synced = false)

            assertSame(found, betterOf(null, found))
            assertSame(found, betterOf(found, null))
        }

        @Test
        fun `two absences are still an absence`() {
            assertNull(betterOf(null, null))
        }
    }
}
