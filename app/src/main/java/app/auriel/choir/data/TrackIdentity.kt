// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.model.Track

/**
 * A stored reference to a track: an id, plus enough about it to recognise the
 * same track again when MediaStore has renumbered the library.
 *
 * Likes and playlist members both keep one. Neither is a copy of the track —
 * MediaStore remains the only source of truth for what audio exists — they are
 * just breadcrumbs back to it.
 */
interface TrackReference {
    val trackId: Long
    val title: String
    val artist: String
    val durationMs: Long
}

/** A stored reference that must follow its track to a new MediaStore id. */
data class Relink(val oldTrackId: Long, val newTrackId: Long)

/**
 * What makes two rows "the same track" when the id no longer agrees.
 *
 * Title, artist and length: enough to be confident in a real library, cheap to
 * compute, and derived only from tags Choir already reads. Duration is rounded
 * to whole seconds because a rescanned file reports its length identically but
 * a re-encode drifts by a few milliseconds.
 */
private fun fingerprint(title: String, artist: String, durationMs: Long): String =
    buildString {
        append(title.trim().lowercase())
        append(' ')
        append(artist.trim().lowercase())
        append(' ')
        append(durationMs / 1000L)
    }

internal fun Track.fingerprint(): String = fingerprint(title, artist, durationMs)

internal fun TrackReference.fingerprint(): String = fingerprint(title, artist, durationMs)

/**
 * Works out which stored references point at ids the library no longer has, and
 * where those tracks went.
 *
 * A dangling reference is *never* deleted here. An id can vanish because the
 * file was deleted, but just as easily because the SD card is unmounted or a
 * media scan is half-finished, and quietly discarding someone's favourites or
 * emptying their playlists in those cases would be unforgivable. Dangling rows
 * simply do not appear until their track comes back.
 *
 * Ambiguity is left alone for the same reason: if two distinct ids or two
 * candidate tracks share a fingerprint there is no way to tell which belonged
 * to which, and a wrong guess moves a reference onto a track nobody chose.
 *
 * Results are per *id*, not per row, so a playlist holding the same track three
 * times produces one relink that the caller applies to all three.
 */
fun relinksFor(stored: List<TrackReference>, library: List<Track>): List<Relink> {
    if (stored.isEmpty() || library.isEmpty()) return emptyList()

    val libraryIds = library.mapTo(HashSet(library.size), Track::id)
    val storedIds = stored.mapTo(HashSet(stored.size), TrackReference::trackId)

    val orphans = stored
        .distinctBy(TrackReference::trackId)
        .filter { it.trackId !in libraryIds }
    if (orphans.isEmpty()) return emptyList()

    // A track something already points at cannot be adopted by an orphan too.
    val candidates = library
        .filter { it.id !in storedIds }
        .groupBy { it.fingerprint() }

    return orphans
        .groupBy { it.fingerprint() }
        .mapNotNull { (print, sharingThePrint) ->
            val orphan = sharingThePrint.singleOrNull() ?: return@mapNotNull null
            val match = candidates[print]?.singleOrNull() ?: return@mapNotNull null
            Relink(oldTrackId = orphan.trackId, newTrackId = match.id)
        }
}
