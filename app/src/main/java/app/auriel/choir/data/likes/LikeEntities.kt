// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.auriel.choir.data.TrackReference

/**
 * One liked track.
 *
 * [trackId] is a MediaStore id, which is *not* stable: a factory reset, a card
 * remount or a rebuilt media database renumbers the whole library and would
 * otherwise silently empty someone's favourites. The three metadata columns are
 * carried purely so a renumbered track can be recognised again — see
 * [app.auriel.choir.data.relinksFor]. They are never displayed; the library
 * remains the only source of truth for what a track is called.
 */
@Entity(tableName = "liked_tracks")
data class LikedTrackEntity(
    @PrimaryKey override val trackId: Long,
    override val title: String,
    override val artist: String,
    override val durationMs: Long,
    /** Ordering key: the Liked Songs list reads newest first. */
    val likedAt: Long,
) : TrackReference
