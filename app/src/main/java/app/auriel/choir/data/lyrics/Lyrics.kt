// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics

/** Where a set of lyrics came from, which decides which set wins. */
enum class LyricsSource {
    /** A `.lrc` file next to the audio — someone put it there deliberately. */
    SIDECAR,

    /** A tag inside the file itself. */
    EMBEDDED,

    /** Fetched from a service the user opted in to, and cached since. */
    ONLINE,
}

/**
 * A run of text inside a line, and when it is sung.
 *
 * [start] and [end] index into the line's own [LyricLine.text], so the view can
 * highlight a prefix without re-splitting anything or worrying about whether
 * the words it joins back together match the line it is drawing.
 */
data class LyricWord(val timeMs: Long, val start: Int, val end: Int)

/** One line, timed if the source gave a timestamp. */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    /**
     * Word timings, when the file carried them — A2-style LRC writes a
     * `<mm:ss.xx>` marker before each word. Empty for the ordinary case, which
     * is nearly every lyric, so a line costs no more than it used to.
     */
    val words: List<LyricWord> = emptyList(),
) {
    /**
     * How far through [text] the singer is at [positionMs], as a character
     * index; the whole line once it is past, and 0 when there are no word
     * timings to go on.
     *
     * Returning a character offset rather than a word number keeps the caller
     * from having to know how the line was split.
     */
    fun sungUpTo(positionMs: Long): Int {
        if (words.isEmpty()) return 0

        var sung = 0
        for (word in words) {
            if (word.timeMs > positionMs) break
            sung = word.end
        }
        return sung
    }

    companion object {
        /** A line with no timing — plain lyrics, or a stray line in a timed file. */
        const val NO_TIME = -1L
    }
}

/**
 * A track's words.
 *
 * Always a list of lines whether or not they are timed, so the view draws one
 * shape and only [isSynced] decides whether anything is highlighted.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val source: LyricsSource,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * The line that should be lit at [positionMs], or -1 before the first one.
     *
     * Binary search: this runs on every position tick, and a linear scan over a
     * long lyric would be doing that work several times a second for nothing.
     * Lines are sorted by [LyricLine.timeMs] at parse time.
     */
    fun indexAt(positionMs: Long): Int {
        if (!isSynced) return -1

        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }
}

/**
 * Lyrics as a tag hands them over, before Choir decides what they mean.
 *
 * [Text] needs a second look: an embedded USLT frame very often contains a
 * whole LRC document, timestamps and all, so it goes back through the LRC
 * parser before being treated as plain prose.
 */
internal sealed interface RawLyrics {
    @JvmInline
    value class Text(val value: String) : RawLyrics

    @JvmInline
    value class Timed(val lines: List<LyricLine>) : RawLyrics
}
