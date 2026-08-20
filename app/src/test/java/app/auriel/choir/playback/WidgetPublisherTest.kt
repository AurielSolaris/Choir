// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import app.auriel.choir.data.lyrics.LyricLine
import app.auriel.choir.data.lyrics.Lyrics
import app.auriel.choir.data.lyrics.LyricsSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The arithmetic behind the Lyric Line widget not being a timer.
 *
 * A widget that shows the line being sung is the obvious place for a one-second
 * tick, and a one-second tick on a home screen is a battery complaint waiting
 * to be filed. It is avoidable because the `.lrc` states the time of every
 * line: the next change is a fact to be read, not a condition to be polled for.
 *
 * These are the two answers that make that work — how long until the line
 * changes, and when it will never change again.
 */
class WidgetPublisherTest {

    @Test
    fun `waits exactly as long as the next line is away`() {
        val lyrics = lyricsAt(0, 5_000, 12_000)

        assertEquals(5_000L, lyricWaitFrom(lyrics, positionMs = 0))
        assertEquals(2_000L, lyricWaitFrom(lyrics, positionMs = 3_000))
        assertEquals(7_000L, lyricWaitFrom(lyrics, positionMs = 5_000))
    }

    /**
     * The line the file names at the exact millisecond the reader is on has
     * already begun, so the wait is to the one after it. Otherwise a wake would
     * schedule itself for zero and immediately do it again.
     */
    @Test
    fun `a line starting exactly now is the one being sung, not the next one`() {
        val lyrics = lyricsAt(0, 5_000, 12_000)

        assertEquals(7_000L, lyricWaitFrom(lyrics, positionMs = 5_000))
    }

    /**
     * Past the last line there is nothing further to wake for, however long the
     * song runs on — an outro can be minutes, and the loop should end at the
     * words rather than sleep through it.
     */
    @Test
    fun `stops waiting once the words have run out`() {
        val lyrics = lyricsAt(0, 5_000, 12_000)

        assertNull(lyricWaitFrom(lyrics, positionMs = 12_000))
        assertNull(lyricWaitFrom(lyrics, positionMs = 300_000))
    }

    @Test
    fun `a lyric with no lines never asks to be woken`() {
        assertNull(lyricWaitFrom(Lyrics(emptyList(), isSynced = true, LyricsSource.SIDECAR), 0))
    }

    /**
     * Two lines a millisecond apart is a real thing in files written by hand,
     * and without a floor it turns the one mechanism written to avoid a busy
     * loop into a busy loop.
     */
    @Test
    fun `lines packed together still cannot become a busy loop`() {
        val lyrics = lyricsAt(0, 1, 2, 3)

        assertTrue(lyricWaitFrom(lyrics, positionMs = 0)!! >= MIN_LYRIC_WAIT_MS)
        assertEquals(MIN_LYRIC_WAIT_MS, lyricWaitFrom(lyrics, positionMs = 0))
    }

    /**
     * A `.lrc` may carry an `[offset:]` that pushes its first line past zero,
     * or simply start late. Before the first line there is no line being sung,
     * and the wait is until there is one.
     */
    @Test
    fun `waits for the first line on a track that starts with none`() {
        val lyrics = lyricsAt(8_000, 15_000)

        assertEquals(8_000L, lyricWaitFrom(lyrics, positionMs = 0))
    }

    private fun lyricsAt(vararg timesMs: Long): Lyrics = Lyrics(
        lines = timesMs.mapIndexed { index, time -> LyricLine(timeMs = time, text = "line $index") },
        isSynced = true,
        source = LyricsSource.SIDECAR,
    )
}
