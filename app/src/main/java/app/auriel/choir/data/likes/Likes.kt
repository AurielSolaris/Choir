// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import app.auriel.choir.data.model.Track

/**
 * Resolves stored likes against the library, keeping the order [stored] arrives
 * in and dropping rows whose track is not currently readable.
 *
 * Re-pointing likes onto renumbered tracks is [app.auriel.choir.data.relinksFor],
 * shared with playlists — both keep the same kind of breadcrumb back to a track.
 */
fun likedTracksIn(stored: List<LikedTrackEntity>, library: List<Track>): List<Track> {
    if (stored.isEmpty() || library.isEmpty()) return emptyList()

    val byId = library.associateBy(Track::id)
    return stored.mapNotNull { byId[it.trackId] }
}
