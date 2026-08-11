// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

import android.net.Uri

/**
 * The things a library can be browsed by, and the rules for deriving them.
 *
 * AOSP gave `AlbumBrowser` and `ArtistBrowser` their own MediaStore collections
 * and their own cursors. Choir folds them out of the track list instead: the
 * album and artist tables carry columns that were deprecated or emptied by
 * scoped storage, and a derived view is guaranteed to agree with the tracks the
 * player can actually open. The whole library is a few hundred rows — grouping
 * it costs nothing.
 */
data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val trackCount: Int,
    val year: Int,
) {
    val artworkUri: Uri get() = Track.albumArtUri(id)
}

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
)

/**
 * A MediaStore playlist, and read-only: the platform closed this collection off
 * to other apps' rows years ago. Choir's own playlists live in Room; this type
 * survives only to import the ones made before that happened.
 */
data class Playlist(
    val id: Long,
    val name: String,
)

/** Shown in place of an album artist when its tracks disagree. */
const val VARIOUS_ARTISTS = "Various artists"

/**
 * Groups tracks into albums, newest-looking metadata winning ties.
 *
 * Compilations are common enough to matter: an album whose tracks name more
 * than one artist is credited to [VARIOUS_ARTISTS] rather than to whichever
 * track happened to sort first.
 */
fun List<Track>.toAlbums(): List<Album> =
    groupBy(Track::albumId)
        .map { (albumId, tracks) ->
            val artists = tracks.map(Track::artist).distinct()
            Album(
                id = albumId,
                title = tracks.first().album,
                artist = artists.singleOrNull() ?: VARIOUS_ARTISTS,
                artistId = if (artists.size == 1) tracks.first().artistId else 0L,
                trackCount = tracks.size,
                year = tracks.maxOf(Track::year),
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Album::title))

fun List<Track>.toArtists(): List<Artist> =
    groupBy(Track::artistId)
        .map { (artistId, tracks) ->
            Artist(
                id = artistId,
                name = tracks.first().artist,
                albumCount = tracks.map(Track::albumId).distinct().size,
                trackCount = tracks.size,
            )
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Artist::name))

/**
 * Album running order: by track number where tags provide one, falling back to
 * title so untagged rips still land somewhere sensible rather than at random.
 */
fun List<Track>.inAlbumOrder(): List<Track> =
    sortedWith(
        compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER, Track::title),
    )
