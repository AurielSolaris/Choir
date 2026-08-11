// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun parse(text: String) = LrcParser.parse(text.trimIndent(), LyricsSource.SIDECAR)

class LrcParserTest {

    @Test
    fun `simple timestamps become timed lines`() {
        val lyrics = parse(
            """
            [00:12.00]First line
            [00:17.20]Second line
            [01:05.50]Third line
            """,
        )

        assertTrue(lyrics!!.isSynced)
        assertEquals(
            listOf(12_000L, 17_200L, 65_500L),
            lyrics.lines.map(LyricLine::timeMs),
        )
        assertEquals("First line", lyrics.lines.first().text)
    }

    @Test
    fun `two-digit fractions are centiseconds and three-digit are milliseconds`() {
        val lyrics = parse(
            """
            [00:01.5]Tenths
            [00:02.05]Centiseconds
            [00:03.005]Milliseconds
            """,
        )

        assertEquals(listOf(1_500L, 2_050L, 3_005L), lyrics!!.lines.map(LyricLine::timeMs))
    }

    @Test
    fun `a repeated line carries one entry per timestamp`() {
        val lyrics = parse("[00:10.00][01:10.00][02:10.00]Chorus")

        assertEquals(3, lyrics!!.lines.size)
        assertTrue(lyrics.lines.all { it.text == "Chorus" })
        assertEquals(listOf(10_000L, 70_000L, 130_000L), lyrics.lines.map(LyricLine::timeMs))
    }

    @Test
    fun `lines come out in time order however the file listed them`() {
        val lyrics = parse(
            """
            [00:30.00]Later
            [00:10.00]Earlier
            """,
        )

        assertEquals(listOf(10_000L, 30_000L), lyrics!!.lines.map(LyricLine::timeMs))
    }

    @Test
    fun `word-level markers are stripped and the line keeps its own timing`() {
        val lyrics = parse("[00:12.00]<00:12.00>Hello <00:12.50>there <00:13.00>world")

        assertEquals(1, lyrics!!.lines.size)
        assertEquals(12_000L, lyrics.lines.single().timeMs)
        assertEquals("Hello there world", lyrics.lines.single().text)
    }

    @Test
    fun `a word-level line with no line timestamp falls back to its first word`() {
        val lyrics = parse("<00:25.00>Only <00:25.40>words")

        assertTrue(lyrics!!.isSynced)
        assertEquals(25_000L, lyrics.lines.single().timeMs)
        assertEquals("Only words", lyrics.lines.single().text)
    }

    @Test
    fun `metadata tags are dropped, not shown as lyrics`() {
        val lyrics = parse(
            """
            [ti:Song]
            [ar:Artist]
            [al:Album]
            [by:Someone]
            [00:05.00]The only real line
            """,
        )

        assertEquals(1, lyrics!!.lines.size)
        assertEquals("The only real line", lyrics.lines.single().text)
    }

    @Test
    fun `a positive offset shows the lines sooner`() {
        val lyrics = parse(
            """
            [offset:+500]
            [00:10.00]Line
            """,
        )

        assertEquals(9_500L, lyrics!!.lines.single().timeMs)
    }

    @Test
    fun `a negative offset shows them later`() {
        val lyrics = parse(
            """
            [offset:-500]
            [00:10.00]Line
            """,
        )

        assertEquals(10_500L, lyrics!!.lines.single().timeMs)
    }

    @Test
    fun `an offset can never push a line before the start of the track`() {
        val lyrics = parse(
            """
            [offset:+5000]
            [00:01.00]Line
            """,
        )

        assertEquals(0L, lyrics!!.lines.single().timeMs)
    }

    @Test
    fun `a file with no timestamps comes back as plain lyrics`() {
        val lyrics = parse(
            """
            Verse one, unadorned
            Verse two, likewise
            """,
        )

        assertFalse(lyrics!!.isSynced)
        assertEquals(2, lyrics.lines.size)
        assertTrue(lyrics.lines.all { it.timeMs == LyricLine.NO_TIME })
    }

    @Test
    fun `an empty timed line is kept, because a pause is part of the timing`() {
        val lyrics = parse(
            """
            [00:05.00]Words
            [00:09.00]
            [00:14.00]More words
            """,
        )

        assertEquals(3, lyrics!!.lines.size)
        assertEquals("", lyrics.lines[1].text)
    }

    @Test
    fun `brackets inside a lyric are left alone`() {
        val lyrics = parse("[00:05.00]Shout [twice] and wait")

        assertEquals("Shout [twice] and wait", lyrics!!.lines.single().text)
    }

    @Test
    fun `nothing usable gives nothing rather than an empty lyric`() {
        assertNull(parse(""))
        assertNull(parse("   \n  \n "))
        assertNull(parse("[ti:Only metadata]"))
    }

    @Test
    fun `a byte order mark does not swallow the first line`() {
        val lyrics = LrcParser.parse("﻿[00:01.00]First", LyricsSource.SIDECAR)

        assertEquals("First", lyrics!!.lines.single().text)
    }

    @Test
    fun `windows line endings do not leave carriage returns in the text`() {
        val lyrics = LrcParser.parse("[00:01.00]One\r\n[00:02.00]Two", LyricsSource.SIDECAR)

        assertEquals(listOf("One", "Two"), lyrics!!.lines.map(LyricLine::text))
    }
}

class LyricsIndexTest {

    private val lyrics = Lyrics(
        lines = listOf(
            LyricLine(10_000, "one"),
            LyricLine(20_000, "two"),
            LyricLine(30_000, "three"),
        ),
        isSynced = true,
        source = LyricsSource.SIDECAR,
    )

    @Test
    fun `nothing is lit before the first line`() {
        assertEquals(-1, lyrics.indexAt(0))
        assertEquals(-1, lyrics.indexAt(9_999))
    }

    @Test
    fun `a line lights the moment it starts and stays lit until the next`() {
        assertEquals(0, lyrics.indexAt(10_000))
        assertEquals(0, lyrics.indexAt(19_999))
        assertEquals(1, lyrics.indexAt(20_000))
    }

    @Test
    fun `the last line stays lit to the end of the track`() {
        assertEquals(2, lyrics.indexAt(30_000))
        assertEquals(2, lyrics.indexAt(9_999_999))
    }

    @Test
    fun `plain lyrics never light anything`() {
        val plain = lyrics.copy(isSynced = false)

        assertEquals(-1, plain.indexAt(25_000))
    }
}

/**
 * Word timings are recorded as character ranges into the line's own text, so
 * these tests check the ranges against the string the view will actually draw
 * rather than against a list of words nobody keeps.
 */
class WordTimingTest {

    private fun wordsOf(text: String) = parse(text)!!.lines.single().words

    private fun sliced(text: String): List<Pair<Long, String>> {
        val line = parse(text)!!.lines.single()
        return line.words.map { it.timeMs to line.text.substring(it.start, it.end) }
    }

    @Test
    fun `word markers are stripped out of the text`() {
        val lyrics = parse("[00:10.00]<00:10.00>Never <00:10.50>gonna <00:11.00>give")

        assertEquals("Never gonna give", lyrics!!.lines.single().text)
    }

    @Test
    fun `each marker times the word that follows it`() {
        assertEquals(
            listOf(10_000L to "Never ", 10_500L to "gonna ", 11_000L to "give"),
            sliced("[00:10.00]<00:10.00>Never <00:10.50>gonna <00:11.00>give"),
        )
    }

    @Test
    fun `ranges are still correct when the line needed trimming`() {
        assertEquals(
            listOf(10_000L to "Never ", 10_500L to "gonna"),
            sliced("[00:10.00]  <00:10.00>Never <00:10.50>gonna   "),
        )
    }

    /**
     * The reason ranges beat a list of words: a line that repeats a word cannot
     * be re-split by searching for it afterwards.
     */
    @Test
    fun `a repeated word is timed twice, at the right two places`() {
        assertEquals(
            listOf(0L to "Go ", 1_000L to "go ", 2_000L to "go"),
            sliced("[00:00.00]<00:00.00>Go <00:01.00>go <00:02.00>go"),
        )
    }

    @Test
    fun `a line with no markers carries no word timings`() {
        assertTrue(wordsOf("[00:10.00]An ordinary line").isEmpty())
    }

    @Test
    fun `an offset moves the words along with the line`() {
        val line = parse(
            """
            [offset:+500]
            [00:10.00]<00:10.00>Never <00:11.00>gonna
            """,
        )!!.lines.single()

        assertEquals(9_500L, line.timeMs)
        assertEquals(listOf(9_500L, 10_500L), line.words.map { it.timeMs })
    }

    /**
     * Word times are absolute, so on an enhanced line that repeats under
     * several timestamps they can only be right for one of them. Dropping them
     * is better than highlighting the wrong words on every repeat.
     */
    @Test
    fun `a line repeated under several timestamps keeps no word timings`() {
        val lyrics = parse("[00:10.00][00:40.00]<00:10.00>Chorus <00:10.50>again")

        assertEquals(2, lyrics!!.lines.size)
        assertTrue(lyrics.lines.all { it.words.isEmpty() })
        assertEquals("Chorus again", lyrics.lines.first().text)
    }

    @Test
    fun `a marker with nothing after it times nothing`() {
        assertEquals(
            listOf(10_000L to "Word"),
            sliced("[00:10.00]<00:10.00>Word<00:11.00>"),
        )
    }

    @Test
    fun `a line with only word markers is timed by the first of them`() {
        val line = parse("<00:05.00>No <00:05.50>line <00:06.00>stamp")!!.lines.single()

        assertEquals(5_000L, line.timeMs)
        assertEquals(3, line.words.size)
    }

    @Test
    fun `every range lies inside the text it indexes`() {
        val line = parse("[00:10.00]<00:10.00>Alpha <00:10.50>beta <00:11.00>gamma")!!
            .lines.single()

        line.words.forEach {
            assertTrue(it.start in 0..line.text.length, "start ${it.start}")
            assertTrue(it.end in it.start..line.text.length, "end ${it.end}")
        }
    }
}

class SungUpToTest {

    private val line = LyricLine(
        timeMs = 10_000,
        text = "Never gonna give",
        words = listOf(
            LyricWord(10_000, 0, 6),
            LyricWord(10_500, 6, 12),
            LyricWord(11_000, 12, 16),
        ),
    )

    @Test
    fun `nothing is sung before the first word`() {
        assertEquals(0, line.sungUpTo(9_999))
    }

    @Test
    fun `the highlight advances a word at a time`() {
        assertEquals(6, line.sungUpTo(10_000))
        assertEquals(6, line.sungUpTo(10_499))
        assertEquals(12, line.sungUpTo(10_500))
        assertEquals(16, line.sungUpTo(11_000))
    }

    @Test
    fun `the whole line stays sung once it is past`() {
        assertEquals(16, line.sungUpTo(99_999))
    }

    /**
     * A line with no word timings highlights as a whole, which the view draws
     * as one span — so the sensible answer here is nothing, not everything.
     */
    @Test
    fun `a line without word timings reports none sung`() {
        assertEquals(0, line.copy(words = emptyList()).sungUpTo(11_000))
    }
}
