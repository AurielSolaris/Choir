// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import app.auriel.choir.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory stand-in for the Room DAO, keeping these tests off the device. */
private class FakeLikesDao : LikesDao {
    val rows = MutableStateFlow<List<LikedTrackEntity>>(emptyList())

    override fun observeAll(): Flow<List<LikedTrackEntity>> = rows

    override suspend fun all(): List<LikedTrackEntity> = rows.value

    override suspend fun isLiked(trackId: Long): Boolean = rows.value.any { it.trackId == trackId }

    override suspend fun insert(row: LikedTrackEntity) {
        rows.value = rows.value.filterNot { it.trackId == row.trackId } + row
    }

    override suspend fun delete(trackId: Long) {
        rows.value = rows.value.filterNot { it.trackId == trackId }
    }

    override suspend fun repoint(oldTrackId: Long, newTrackId: Long) {
        rows.value = rows.value.map {
            if (it.trackId == oldTrackId) it.copy(trackId = newTrackId) else it
        }
    }
}

private fun track(id: Long, title: String = "Song $id") = Track(
    id = id,
    title = title,
    artist = "Artist",
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = 180_000,
    trackNumber = 1,
    year = 2000,
)

class LikesRepositoryTest {

    private val dao = FakeLikesDao()
    private var clock = 1_000L
    private val repository = LikesRepository(dao) { clock }

    @Test
    fun `toggling an unliked track likes it`() = runTest {
        assertTrue(repository.toggle(track(1L)))
        assertEquals(setOf(1L), repository.likedIds.first())
    }

    @Test
    fun `toggling twice leaves nothing behind`() = runTest {
        repository.toggle(track(1L))

        assertFalse(repository.toggle(track(1L)))
        assertTrue(repository.likedIds.first().isEmpty())
    }

    @Test
    fun `liking stores the tags needed to find the track again`() = runTest {
        repository.setLiked(track(4L, title = "Ghosts"), liked = true)

        val row = dao.all().single()
        assertEquals("Ghosts", row.title)
        assertEquals(180_000, row.durationMs)
        assertEquals(1_000L, row.likedAt)
    }

    @Test
    fun `liking the same track twice does not duplicate the row`() = runTest {
        repository.setLiked(track(1L), liked = true)
        clock = 2_000L
        repository.setLiked(track(1L), liked = true)

        assertEquals(1, dao.all().size)
    }

    @Test
    fun `a rescan that renumbers the library keeps the like`() = runTest {
        repository.setLiked(track(1L, title = "Ghosts"), liked = true)

        // Same file, new MediaStore id.
        repository.reconcile(listOf(track(5001L, title = "Ghosts")))

        assertEquals(setOf(5001L), repository.likedIds.first())
    }

    @Test
    fun `an unreadable library does not touch stored likes`() = runTest {
        repository.setLiked(track(1L), liked = true)

        repository.reconcile(emptyList())

        assertEquals(setOf(1L), repository.likedIds.first())
    }

    @Test
    fun `a deleted track keeps its row so the like returns if the file does`() = runTest {
        repository.setLiked(track(1L, title = "Gone"), liked = true)

        repository.reconcile(listOf(track(2L, title = "Still here")))

        assertEquals(setOf(1L), repository.likedIds.first())
    }
}
