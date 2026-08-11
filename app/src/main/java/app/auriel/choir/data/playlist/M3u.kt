// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import app.auriel.choir.data.model.Track

/**
 * One entry read out of an `.m3u` file: a path, and whatever the `#EXTINF`
 * line claimed about it.
 *
 * The path is the only thing that is really trustworthy; the metadata is a
 * hint, used to identify a track when the path itself no longer resolves.
 */
data class M3uEntry(
    val path: String,
    val durationSeconds: Long = -1L,
    val title: String? = null,
    val artist: String? = null,
)

/**
 * Reads and writes `.m3u` / `.m3u8`.
 *
 * The format has no specification, only thirty years of convention: a list of
 * paths, optionally preceded by `#EXTM3U`, with each entry optionally preceded
 * by `#EXTINF:<seconds>,<artist> - <title>`. Anything else beginning with `#`
 * is a comment and is skipped, which is what makes the format survivable.
 *
 * `.m3u8` differs only in being UTF-8 by definition. Choir writes UTF-8 either
 * way, because the alternative is guessing at a codepage.
 */
object M3u {

    private const val HEADER = "#EXTM3U"
    private const val INFO_PREFIX = "#EXTINF:"

    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pending: M3uEntry? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            when {
                line.startsWith(INFO_PREFIX) -> pending = parseInfo(line)
                // Every other directive — #EXTM3U, #PLAYLIST, #EXTGRP — says
                // nothing about which file comes next.
                line.startsWith("#") -> Unit

                else -> {
                    entries += (pending ?: M3uEntry(path = line)).copy(path = line)
                    pending = null
                }
            }
        }
        return entries
    }

    /** `#EXTINF:213,Artist - Title`, where both halves are optional in practice. */
    private fun parseInfo(line: String): M3uEntry {
        val payload = line.removePrefix(INFO_PREFIX)
        val comma = payload.indexOf(',')

        val duration = if (comma >= 0) payload.substring(0, comma) else payload
        val label = if (comma >= 0) payload.substring(comma + 1).trim() else ""

        // " - " and not "-": plenty of titles contain a hyphen.
        val dash = label.indexOf(" - ")
        return M3uEntry(
            path = "",
            durationSeconds = duration.trim().toDoubleOrNull()?.toLong() ?: -1L,
            title = if (dash >= 0) label.substring(dash + 3).trim() else label.ifBlank { null },
            artist = if (dash >= 0) label.substring(0, dash).trim() else null,
        )
    }

    /**
     * Writes the extended form, since the metadata is what lets another player
     * — or Choir on another device — find the tracks when the paths do not
     * line up.
     */
    fun write(tracks: List<Track>, pathOf: (Track) -> String): String = buildString {
        appendLine(HEADER)
        for (track in tracks) {
            appendLine("$INFO_PREFIX${track.durationMs / 1000},${track.artist} - ${track.title}")
            appendLine(pathOf(track))
        }
    }
}

/**
 * Matches entries read from a file against the library.
 *
 * Paths in an `.m3u` come from wherever it was written, so they are matched
 * from the end backwards — file name first, then the folder above it — which
 * survives the whole tree having moved or the file having come from another
 * device. Only if that fails does the `#EXTINF` metadata get a turn.
 */
fun resolveM3u(entries: List<M3uEntry>, library: List<Track>, pathOf: (Track) -> String): List<Track> {
    if (entries.isEmpty() || library.isEmpty()) return emptyList()

    val byName = library.groupBy { pathOf(it).substringAfterLast('/').lowercase() }
    val byTail = library.groupBy { tail(pathOf(it)) }
    val byMetadata = library.groupBy { "${it.artist.trim().lowercase()} ${it.title.trim().lowercase()}" }

    return entries.mapNotNull { entry ->
        val path = entry.path.replace('\\', '/').substringBefore('?')

        byTail[tail(path)]?.singleOrNull()
            ?: byName[path.substringAfterLast('/').lowercase()]?.singleOrNull()
            ?: entry.metadataKey()?.let { byMetadata[it]?.firstOrNull() }
    }
}

/** The last two path segments, which identify a track far better than one. */
private fun tail(path: String): String =
    path.replace('\\', '/').trimEnd('/').split('/').takeLast(2).joinToString("/").lowercase()

private fun M3uEntry.metadataKey(): String? {
    val artist = artist?.trim()?.lowercase() ?: return null
    val title = title?.trim()?.lowercase() ?: return null
    return if (artist.isEmpty() || title.isEmpty()) null else "$artist $title"
}
