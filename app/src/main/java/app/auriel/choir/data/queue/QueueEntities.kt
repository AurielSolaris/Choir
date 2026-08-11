// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.queue

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One slot in the saved play queue. [position] is the index in the queue, so the
 * table is a list and the same track may legitimately appear twice.
 */
@Entity(tableName = "queue_entries")
data class QueueEntryEntity(
    @PrimaryKey val position: Int,
    val trackId: Long,
)

/**
 * Where playback had got to. Single-row table — AOSP kept the equivalent in
 * SharedPreferences, but it belongs next to the queue it describes.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val queueIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
