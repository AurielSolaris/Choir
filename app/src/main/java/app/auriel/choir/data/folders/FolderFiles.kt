// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.model.TrackSource

/** What a tag reader managed to learn about a file nothing had indexed. */
data class FolderTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val year: Int = 0,
) {
    companion object {
        /** For the files the platform cannot open at all, which is most of the point. */
        val NONE = FolderTags()
    }
}

/**
 * Which of a granted folder's files the indexed library does not already have.
 *
 * A granted folder almost always overlaps MediaStore — someone points Choir at
 * `Music/` and nine tenths of it is already indexed. Those files must keep
 * their MediaStore identity rather than appearing a second time under a
 * document URI, or the same song would be listed twice, counted twice and liked
 * twice over. So the two sources are merged by path, and only what MediaStore
 * never indexed is carried as a folder track.
 *
 * The filtering happens here, at merge time, rather than during the scan: what
 * MediaStore knows changes on its own schedule — a scan finishes, a download
 * lands — and a file that becomes indexed a minute after being scanned must
 * stop being a folder track without waiting for the folder to be read again.
 *
 * Matching is on folder plus filename, case-insensitively, which is what both
 * sides can agree on: MediaStore has no document id and the provider has no
 * MediaStore id, and comparing sizes would break the moment a tag editor
 * rewrote a byte.
 */
fun unindexedTracks(folderTracks: List<Track>, indexed: List<Track>): List<Track> {
    if (folderTracks.isEmpty()) return emptyList()

    val known = indexed.mapTo(HashSet(indexed.size)) { fileKey(it.relativePath, it.displayName) }
    val seen = HashSet<String>(folderTracks.size)

    return folderTracks.filter { track ->
        val key = fileKey(track.relativePath, track.displayName)
        key !in known && seen.add(key)
    }
}

/** The identity two different providers can both arrive at for one file. */
internal fun fileKey(relativePath: String, displayName: String): String =
    (relativePath.trim('/') + '/' + displayName).lowercase()

/**
 * Turns a file found in a granted folder into a track the rest of the app can
 * treat like any other.
 *
 * The tags will often be blank: the tag reader is the platform's, and the files
 * that reach here are precisely the ones the platform's own scanner could not
 * parse. The filename then carries everything, extension included — three
 * unreadable files in one folder have to be told apart somehow, and it is also
 * the honest answer to "what do you know about this file".
 */
fun FolderFileEntity.toTrack(): Track = Track(
    id = trackId,
    title = title.ifBlank { displayName },
    artist = MusicUtils.tagOrFallback(artist, UNKNOWN_ARTIST),
    artistId = 0L,
    album = MusicUtils.tagOrFallback(album, UNKNOWN_ALBUM),
    albumId = 0L,
    durationMs = durationMs,
    trackNumber = trackNumber,
    year = year,
    displayName = displayName,
    mimeType = mimeType,
    relativePath = relativePath,
    source = TrackSource.Folder(documentUri),
)

private const val UNKNOWN_ARTIST = "Unknown artist"
private const val UNKNOWN_ALBUM = "Unknown album"
