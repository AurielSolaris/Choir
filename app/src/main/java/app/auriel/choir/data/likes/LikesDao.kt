// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.auriel.choir.data.Relink
import kotlinx.coroutines.flow.Flow

@Dao
interface LikesDao {

    /** Newest first: the Liked Songs list reads as a record of what you kept. */
    @Query("SELECT * FROM liked_tracks ORDER BY likedAt DESC")
    fun observeAll(): Flow<List<LikedTrackEntity>>

    @Query("SELECT * FROM liked_tracks")
    suspend fun all(): List<LikedTrackEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM liked_tracks WHERE trackId = :trackId)")
    suspend fun isLiked(trackId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: LikedTrackEntity)

    @Query("DELETE FROM liked_tracks WHERE trackId = :trackId")
    suspend fun delete(trackId: Long)

    @Query("UPDATE liked_tracks SET trackId = :newTrackId WHERE trackId = :oldTrackId")
    suspend fun repoint(oldTrackId: Long, newTrackId: Long)

    /**
     * Re-points likes at tracks MediaStore has renumbered, all or nothing.
     *
     * [relinksFor] guarantees no target id is already liked, so the primary-key
     * update below cannot collide.
     */
    @Transaction
    suspend fun applyRelinks(relinks: List<Relink>) {
        for (relink in relinks) repoint(relink.oldTrackId, relink.newTrackId)
    }
}
