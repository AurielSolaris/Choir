// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.core

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Small formatting helpers shared across screens, ported from AOSP's `MusicUtils`.
 */
object MusicUtils {

    /**
     * Formats a duration the way a music player should: `m:ss`, widening to
     * `h:mm:ss` only once a track actually runs past the hour. Negative and
     * unknown durations (MediaStore reports -1) render as `0:00` rather than
     * leaking a nonsense number into the UI.
     */
    fun makeTimeString(durationMs: Long): String {
        if (durationMs <= 0L) return "0:00"

        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds / 60) % 60
        val seconds = totalSeconds % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", totalSeconds / 60, seconds)
        }
    }

    /**
     * Joins the metadata line under a track title. MediaStore uses the literal
     * string `<unknown>` for missing artist and album tags, so both are swapped
     * for readable fallbacks before joining.
     */
    fun makeSubtitle(artist: String, album: String, separator: String = SEPARATOR): String =
        listOf(artist, album).filter { it.isNotBlank() }.joinToString(separator)

    /** What joins the parts of a metadata line throughout the app. */
    const val SEPARATOR = " · "

    /** MediaStore's sentinel for a missing tag. */
    const val UNKNOWN_TAG = "<unknown>"

    fun tagOrFallback(value: String?, fallback: String): String =
        if (value.isNullOrBlank() || value == UNKNOWN_TAG) fallback else value
}
