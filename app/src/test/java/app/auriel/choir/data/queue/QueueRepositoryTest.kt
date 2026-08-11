// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.queue

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory stand-in for the Room DAO, keeping these tests off the device. */
private class FakeQueueDao : QueueDao {
    var entries = mutableListOf<QueueEntryEntity>()
    var state: PlaybackStateEntity? = null

    override suspend fun entries(): List<QueueEntryEntity> = entries.sortedBy { it.position }
    override suspend fun state(): PlaybackStateEntity? = state

    override suspend fun insertEntries(entries: List<QueueEntryEntity>) {
        this.entries.addAll(entries)
    }

    override suspend fun upsertState(state: PlaybackStateEntity) {
        this.state = state
    }

    override suspend fun deleteEntries() = entries.clear()

    override suspend fun deleteState() {
        state = null
    }
}

class QueueRepositoryTest {

    private val dao = FakeQueueDao()
    private val repository = QueueRepository(dao)

    @Test
    fun `load returns nothing when the queue was never saved`() = runTest {
        assertNull(repository.load())
    }

    @Test
    fun `load returns nothing when the state row is missing its entries`() = runTest {
        dao.state = PlaybackStateEntity(
            queueIndex = 3,
            positionMs = 1_000,
            shuffleEnabled = false,
            repeatMode = 0,
        )

        assertNull(repository.load())
    }

    @Test
    fun `a saved queue round-trips in order`() = runTest {
        val saved = SavedQueue(
            trackIds = listOf(30L, 10L, 20L),
            queueIndex = 1,
            positionMs = 42_000,
            shuffleEnabled = true,
            repeatMode = 2,
        )

        repository.save(saved)

        assertEquals(saved, repository.load())
    }

    @Test
    fun `saving replaces the previous queue rather than appending`() = runTest {
        repository.save(
            SavedQueue(listOf(1L, 2L, 3L), queueIndex = 2, positionMs = 0, false, repeatMode = 0),
        )
        repository.save(
            SavedQueue(listOf(9L), queueIndex = 0, positionMs = 0, false, repeatMode = 0),
        )

        assertEquals(listOf(9L), repository.load()?.trackIds)
    }

    @Test
    fun `an index past the end of a truncated queue is clamped`() = runTest {
        // The state row and the entries are written together, but a queue
        // truncated by an interrupted write must still restore in bounds.
        dao.entries = mutableListOf(QueueEntryEntity(0, 11L), QueueEntryEntity(1, 22L))
        dao.state = PlaybackStateEntity(
            queueIndex = 7,
            positionMs = -5,
            shuffleEnabled = false,
            repeatMode = 0,
        )

        val loaded = repository.load()

        assertEquals(1, loaded?.queueIndex)
        assertEquals(0L, loaded?.positionMs)
    }

    @Test
    fun `saving an empty queue clears the stored one`() = runTest {
        repository.save(
            SavedQueue(listOf(1L), queueIndex = 0, positionMs = 0, false, repeatMode = 0),
        )

        repository.save(
            SavedQueue(emptyList(), queueIndex = 0, positionMs = 0, false, repeatMode = 0),
        )

        assertNull(repository.load())
        assertTrue(dao.entries.isEmpty())
    }
}
