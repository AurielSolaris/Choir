// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.queue

import app.auriel.choir.core.MusicLog

/**
 * The queue as the player thinks of it: an ordered list of track ids plus where
 * playback had got to.
 */
data class SavedQueue(
    val trackIds: List<Long>,
    val queueIndex: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: Int,
) {
    val isEmpty: Boolean get() = trackIds.isEmpty()
}

/**
 * Persists the play queue so Choir reopens where it was left, which is what the
 * AOSP service did with its saved-queue preferences.
 */
class QueueRepository(private val dao: QueueDao) {

    suspend fun load(): SavedQueue? {
        val entries = dao.entries()
        val state = dao.state()
        if (entries.isEmpty() || state == null) return null

        val trackIds = entries.map(QueueEntryEntity::trackId)
        return SavedQueue(
            trackIds = trackIds,
            // Guard the index: the row is written independently of the entries
            // and a truncated queue must not restore out of bounds.
            queueIndex = state.queueIndex.coerceIn(0, trackIds.lastIndex),
            positionMs = state.positionMs.coerceAtLeast(0L),
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = state.repeatMode,
        )
    }

    suspend fun save(queue: SavedQueue) {
        if (queue.isEmpty) {
            clear()
            return
        }
        dao.replace(
            entries = queue.trackIds.mapIndexed { index, trackId ->
                QueueEntryEntity(position = index, trackId = trackId)
            },
            state = PlaybackStateEntity(
                queueIndex = queue.queueIndex,
                positionMs = queue.positionMs,
                shuffleEnabled = queue.shuffleEnabled,
                repeatMode = queue.repeatMode,
            ),
        )
        MusicLog.v(TAG, "saved queue of ${queue.trackIds.size} at ${queue.queueIndex}")
    }

    suspend fun clear() = dao.clear()

    private companion object {
        const val TAG = "QueueRepository"
    }
}
