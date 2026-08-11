// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics

/**
 * Reads LRC, in the several shapes it exists in the wild.
 *
 * Handled:
 *  - simple `[mm:ss.xx] line`
 *  - enhanced, where one line carries several timestamps because it repeats
 *  - A2 word-level, where `<mm:ss.xx>` markers sit between words
 *  - metadata tags (`[ti:]`, `[ar:]`, `[offset:]`) — read for offset, else dropped
 *  - files with no timestamps at all, which come back as plain lyrics
 *
 * Word timings are kept as character ranges into the line's own text, so the
 * view highlights a prefix of the string it is already drawing rather than
 * re-splitting it and hoping the pieces line up.
 *
 * There is no LRC standard, only convention, so anything unrecognised is
 * dropped rather than rejected — half a lyric beats an error message.
 */
object LrcParser {

    private val LINE_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TIMESTAMP = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val METADATA_TAG = Regex("""^\[([a-zA-Z#]+):(.*)]$""")

    fun parse(text: String, source: LyricsSource): Lyrics? {
        if (text.isBlank()) return null

        var offsetMs = 0L
        val timed = mutableListOf<LyricLine>()
        val untimed = mutableListOf<String>()

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            val tag = METADATA_TAG.matchEntire(line)
            if (tag != null) {
                // Only offset changes what is shown; the rest describes the
                // file, which the library already knows better than the tag does.
                if (tag.groupValues[1].equals("offset", ignoreCase = true)) {
                    offsetMs = tag.groupValues[2].trim().removePrefix("+").toLongOrNull() ?: 0L
                }
                continue
            }

            val stamps = leadingTimestamps(line)
            val body = line.substring(stamps.consumedChars)
            val (cleaned, words) = splitWordTimings(body)

            when {
                // Enhanced LRC repeats a line under several timestamps. Word
                // timings are absolute, so they describe one of those
                // occurrences and would be wrong against the others — they are
                // kept only where there is nothing to be ambiguous about.
                stamps.timesMs.size == 1 -> timed += LyricLine(stamps.timesMs[0], cleaned, words)

                stamps.timesMs.size > 1 ->
                    stamps.timesMs.forEach { timed += LyricLine(it, cleaned) }

                // An A2 line can carry no line-level stamp at all; the first
                // word marker is then the only timing it has.
                words.isNotEmpty() -> timed += LyricLine(words.first().timeMs, cleaned, words)

                cleaned.isNotEmpty() -> untimed += cleaned
            }
        }

        return when {
            timed.isNotEmpty() -> Lyrics(
                // Enhanced LRC lists repeats out of order on purpose, and a
                // positive offset means "show these sooner" by convention.
                lines = timed
                    .map { it.shiftedBy(offsetMs) }
                    .sortedBy(LyricLine::timeMs),
                isSynced = true,
                source = source,
            )

            untimed.isNotEmpty() -> Lyrics(
                lines = untimed.map { LyricLine(LyricLine.NO_TIME, it) },
                isSynced = false,
                source = source,
            )

            else -> null
        }
    }

    /**
     * Moves a line, and any word timings on it, by the file's `[offset:]`.
     *
     * Shifting the line but not its words would put the highlight out of step
     * with the line it is highlighting, which is worse than not highlighting.
     */
    private fun LyricLine.shiftedBy(offsetMs: Long): LyricLine {
        if (offsetMs == 0L) return this
        return copy(
            timeMs = (timeMs - offsetMs).coerceAtLeast(0L),
            words = words.map { it.copy(timeMs = (it.timeMs - offsetMs).coerceAtLeast(0L)) },
        )
    }

    private class LeadingTimestamps(val timesMs: List<Long>, val consumedChars: Int)

    /**
     * Strips `<mm:ss.xx>` markers out of a line and records where each one left
     * off, as an index into the text that remains.
     *
     * Building the text once while noting the offsets is the whole trick: doing
     * it as a regex replace and then searching for the words again would have
     * to guess at whitespace, and would break on any line that repeats a word.
     */
    private fun splitWordTimings(body: String): Pair<String, List<LyricWord>> {
        val markers = WORD_TIMESTAMP.findAll(body).toList()
        if (markers.isEmpty()) return body.trim() to emptyList()

        val builder = StringBuilder(body.length)
        val starts = ArrayList<Pair<Long, Int>>(markers.size)
        var cursor = 0

        for (marker in markers) {
            builder.append(body, cursor, marker.range.first)
            starts += marker.toMillis() to builder.length
            cursor = marker.range.last + 1
        }
        builder.append(body, cursor, body.length)

        // The text is trimmed, so every recorded offset moves with it.
        val untrimmed = builder.toString()
        val from = untrimmed.indexOfFirst { !it.isWhitespace() }
        if (from < 0) return "" to emptyList()
        val until = untrimmed.indexOfLast { !it.isWhitespace() } + 1
        val text = untrimmed.substring(from, until)

        val words = ArrayList<LyricWord>(starts.size)
        for (index in starts.indices) {
            val start = (starts[index].second - from).coerceIn(0, text.length)
            val end = if (index + 1 < starts.size) {
                (starts[index + 1].second - from).coerceIn(0, text.length)
            } else {
                text.length
            }
            // A marker with no word after it times nothing; two markers in a
            // row, or one at the very end, are both common in hand-made files.
            if (end > start) words += LyricWord(starts[index].first, start, end)
        }
        return text to words
    }

    /**
     * Peels timestamps off the front of a line. Stops at the first thing that is
     * not one, so a `[bracketed]` word inside the lyric is left in the text.
     */
    private fun leadingTimestamps(line: String): LeadingTimestamps {
        val times = mutableListOf<Long>()
        var cursor = 0

        while (cursor < line.length) {
            val match = LINE_TIMESTAMP.matchAt(line, cursor) ?: break
            times += match.toMillis()
            cursor = match.range.last + 1
        }
        return LeadingTimestamps(times, cursor)
    }

    /**
     * `mm:ss.xx`. The fraction is centiseconds at two digits and milliseconds at
     * three, which is the one place LRC files genuinely disagree with each other.
     */
    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLong()
        val seconds = groupValues[2].toLong()
        val fraction = groupValues[3]

        val fractionMs = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.toLong()
        }
        return minutes * 60_000 + seconds * 1_000 + fractionMs
    }
}
