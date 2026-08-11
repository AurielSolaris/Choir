// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.auriel.choir.data.TrackReference

/**
 * A playlist Choir owns.
 *
 * The reason these exist at all: `MediaStore.Audio.Playlists` was deprecated in
 * Android 10 and closed to other apps' rows in Android 11, so the platform's
 * playlists are permanently empty on every device Choir is likely to run on.
 */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One track's place in one playlist.
 *
 * Keyed on its own [id] rather than on the track, because a playlist may
 * legitimately hold the same track more than once — and removing the second
 * copy must not remove the first.
 *
 * [position] is renumbered from zero on every reorder. Fractional keys would
 * avoid the rewrite, but a playlist is a few hundred rows at most and one
 * transaction is simpler to reason about than a key space that slowly loses
 * precision.
 */
@Entity(
    tableName = "playlist_members",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            // Deleting a playlist must not leave its contents behind.
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistMemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val playlistId: Long,
    override val trackId: Long,
    val position: Int,
    override val title: String,
    override val artist: String,
    override val durationMs: Long,
) : TrackReference

/** A playlist and how much is in it, which is all the list view needs. */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
)
