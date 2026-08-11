// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface QueueDao {

    @Query("SELECT * FROM queue_entries ORDER BY position ASC")
    suspend fun entries(): List<QueueEntryEntity>

    @Query("SELECT * FROM playback_state WHERE id = ${PlaybackStateEntity.SINGLETON_ID}")
    suspend fun state(): PlaybackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<QueueEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: PlaybackStateEntity)

    @Query("DELETE FROM queue_entries")
    suspend fun deleteEntries()

    @Query("DELETE FROM playback_state")
    suspend fun deleteState()

    /** Replaces the whole saved queue atomically, so a crash can't tear it. */
    @Transaction
    suspend fun replace(entries: List<QueueEntryEntity>, state: PlaybackStateEntity) {
        deleteEntries()
        insertEntries(entries)
        upsertState(state)
    }

    @Transaction
    suspend fun clear() {
        deleteEntries()
        deleteState()
    }
}
