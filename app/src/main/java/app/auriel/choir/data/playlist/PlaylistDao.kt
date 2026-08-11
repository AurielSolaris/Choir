// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import app.auriel.choir.data.Relink
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    /**
     * Playlists with their sizes, name-sorted.
     *
     * A left join, so a newly made and still empty playlist appears rather than
     * quietly not existing until something is added to it.
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, COUNT(m.id) AS trackCount
        FROM playlists p
        LEFT JOIN playlist_members m ON m.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE ASC
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlist_members WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeMembers(playlistId: Long): Flow<List<PlaylistMemberEntity>>

    @Query("SELECT * FROM playlist_members WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun members(playlistId: Long): List<PlaylistMemberEntity>

    @Query("SELECT * FROM playlist_members")
    suspend fun allMembers(): List<PlaylistMemberEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun playlist(playlistId: Long): PlaylistEntity?

    @Query("SELECT id FROM playlists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun idOfName(name: String): Long?

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :playlistId")
    suspend fun rename(playlistId: Long, name: String, now: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert
    suspend fun insertMembers(members: List<PlaylistMemberEntity>)

    @Query("DELETE FROM playlist_members WHERE id = :memberId")
    suspend fun deleteMember(memberId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_members WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("UPDATE playlist_members SET position = :position WHERE id = :memberId")
    suspend fun setPosition(memberId: Long, position: Int)

    @Query("UPDATE playlist_members SET trackId = :newTrackId WHERE trackId = :oldTrackId")
    suspend fun repoint(oldTrackId: Long, newTrackId: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, now: Long)

    /**
     * Writes a whole new running order at once.
     *
     * Positions are renumbered from zero rather than patched, so a list that
     * has drifted — through a failed write, or an import — comes back
     * contiguous instead of preserving the gap.
     */
    @Transaction
    suspend fun reorder(playlistId: Long, memberIdsInOrder: List<Long>, now: Long) {
        memberIdsInOrder.forEachIndexed { position, memberId -> setPosition(memberId, position) }
        touch(playlistId, now)
    }

    @Transaction
    suspend fun applyRelinks(relinks: List<Relink>) {
        for (relink in relinks) repoint(relink.oldTrackId, relink.newTrackId)
    }
}
