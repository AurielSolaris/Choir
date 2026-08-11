// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import app.auriel.choir.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory stand-in for the Room DAO, keeping these tests off the device. */
private class FakePlaylistDao : PlaylistDao {
    val playlists = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val members = MutableStateFlow<List<PlaylistMemberEntity>>(emptyList())
    private var nextPlaylistId = 1L
    private var nextMemberId = 1L

    override fun observeSummaries(): Flow<List<PlaylistSummary>> = playlists.map { rows ->
        rows.map { row ->
            PlaylistSummary(row.id, row.name, members.value.count { it.playlistId == row.id })
        }.sortedBy { it.name.lowercase() }
    }

    override fun observeMembers(playlistId: Long): Flow<List<PlaylistMemberEntity>> =
        members.map { rows -> rows.filter { it.playlistId == playlistId }.sortedBy { it.position } }

    override suspend fun members(playlistId: Long): List<PlaylistMemberEntity> =
        members.value.filter { it.playlistId == playlistId }.sortedBy { it.position }

    override suspend fun allMembers(): List<PlaylistMemberEntity> = members.value

    override suspend fun playlist(playlistId: Long): PlaylistEntity? =
        playlists.value.firstOrNull { it.id == playlistId }

    override suspend fun idOfName(name: String): Long? =
        playlists.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id

    override suspend fun insertPlaylist(playlist: PlaylistEntity): Long {
        val id = nextPlaylistId++
        playlists.value = playlists.value + playlist.copy(id = id)
        return id
    }

    override suspend fun rename(playlistId: Long, name: String, now: Long) {
        playlists.value = playlists.value.map {
            if (it.id == playlistId) it.copy(name = name, updatedAt = now) else it
        }
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlists.value = playlists.value.filterNot { it.id == playlistId }
        // The real table cascades; the fake has to say so out loud.
        members.value = members.value.filterNot { it.playlistId == playlistId }
    }

    override suspend fun insertMembers(members: List<PlaylistMemberEntity>) {
        this.members.value = this.members.value + members.map { it.copy(id = nextMemberId++) }
    }

    override suspend fun deleteMember(memberId: Long) {
        members.value = members.value.filterNot { it.id == memberId }
    }

    override suspend fun lastPosition(playlistId: Long): Int =
        members.value.filter { it.playlistId == playlistId }.maxOfOrNull { it.position } ?: -1

    override suspend fun setPosition(memberId: Long, position: Int) {
        members.value = members.value.map {
            if (it.id == memberId) it.copy(position = position) else it
        }
    }

    override suspend fun repoint(oldTrackId: Long, newTrackId: Long) {
        members.value = members.value.map {
            if (it.trackId == oldTrackId) it.copy(trackId = newTrackId) else it
        }
    }

    override suspend fun touch(playlistId: Long, now: Long) {
        playlists.value = playlists.value.map {
            if (it.id == playlistId) it.copy(updatedAt = now) else it
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

class PlaylistRepositoryTest {

    private val dao = FakePlaylistDao()
    private val repository = PlaylistRepository(dao) { 1_000L }

    private suspend fun order(playlistId: Long): List<Long> =
        dao.members(playlistId).map(PlaylistMemberEntity::trackId)

    @Test
    fun `a new playlist starts empty and is still listed`() = runTest {
        val id = repository.create("Late night")

        val summary = repository.playlists.first().single()
        assertEquals(id, summary.id)
        assertEquals("Late night", summary.name)
        assertEquals(0, summary.trackCount)
    }

    @Test
    fun `creating the same name twice returns the playlist that exists`() = runTest {
        val first = repository.create("Late night")
        val second = repository.create("  late NIGHT  ")

        assertEquals(first, second)
        assertEquals(1, repository.playlists.first().size)
    }

    @Test
    fun `an import never merges into an existing playlist of the same name`() = runTest {
        repository.create("Deep cuts")

        val second = repository.createDistinct("Deep cuts")
        val third = repository.createDistinct("Deep cuts")

        val names = repository.playlists.first().map(PlaylistSummary::name)
        assertEquals(3, names.size)
        assertTrue(names.containsAll(listOf("Deep cuts", "Deep cuts (2)", "Deep cuts (3)")))
        assertTrue(second != third)
    }

    @Test
    fun `a blank name is given one rather than stored empty`() = runTest {
        repository.create("   ")

        assertTrue(repository.playlists.first().single().name.isNotBlank())
    }

    @Test
    fun `tracks are appended in the order they were added`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L)))
        repository.add(id, listOf(track(3L)))

        assertEquals(listOf(1L, 2L, 3L), order(id))
    }

    @Test
    fun `the same track may be added twice and removed once`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(1L)))

        val first = dao.members(id).first().id
        repository.remove(id, first)

        assertEquals(listOf(1L), order(id))
    }

    @Test
    fun `removing closes the gap it leaves`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L), track(3L)))

        repository.remove(id, dao.members(id)[1].id)

        assertEquals(listOf(0, 1), dao.members(id).map(PlaylistMemberEntity::position))
    }

    @Test
    fun `moving a track down carries the ones between it along`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L), track(3L), track(4L)))

        repository.move(id, from = 0, to = 2)

        assertEquals(listOf(2L, 3L, 1L, 4L), order(id))
    }

    @Test
    fun `moving a track up does the same in reverse`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L), track(3L)))

        repository.move(id, from = 2, to = 0)

        assertEquals(listOf(3L, 1L, 2L), order(id))
    }

    @Test
    fun `a move that goes nowhere leaves the order alone`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L)))

        repository.move(id, from = 0, to = 0)
        repository.move(id, from = 5, to = 0)

        assertEquals(listOf(1L, 2L), order(id))
    }

    @Test
    fun `an order the UI settled on is written straight through`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L), track(3L)))
        val ids = dao.members(id).map(PlaylistMemberEntity::id)

        repository.applyOrder(id, listOf(ids[2], ids[0], ids[1]))

        assertEquals(listOf(3L, 1L, 2L), order(id))
    }

    @Test
    fun `an incomplete order is refused rather than half-applied`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L), track(3L)))
        val ids = dao.members(id).map(PlaylistMemberEntity::id)

        // A stale list from a drag that raced a removal.
        repository.applyOrder(id, listOf(ids[1], ids[0]))

        assertEquals(listOf(1L, 2L, 3L), order(id))
    }

    @Test
    fun `deleting a playlist takes its contents with it`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L)))

        repository.delete(id)

        assertTrue(repository.playlists.first().isEmpty())
        assertTrue(dao.allMembers().isEmpty())
    }

    @Test
    fun `a rescan that renumbers the library keeps the playlist intact`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L, title = "Ghosts")))

        repository.reconcile(listOf(track(5001L, title = "Ghosts")))

        assertEquals(listOf(5001L), order(id))
    }

    @Test
    fun `every copy of a repeated track follows the renumber`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L, title = "Encore"), track(1L, title = "Encore")))

        repository.reconcile(listOf(track(88L, title = "Encore")))

        assertEquals(listOf(88L, 88L), order(id))
    }

    @Test
    fun `an unreadable library does not empty a playlist`() = runTest {
        val id = repository.create("Mix")
        repository.add(id, listOf(track(1L), track(2L)))

        repository.reconcile(emptyList())

        assertEquals(listOf(1L, 2L), order(id))
    }
}

class PlaylistTracksInTest {

    private fun member(memberId: Long, trackId: Long) = PlaylistMemberEntity(
        id = memberId,
        playlistId = 1L,
        trackId = trackId,
        position = memberId.toInt(),
        title = "Song $trackId",
        artist = "Artist",
        durationMs = 180_000,
    )

    @Test
    fun `members resolve in playlist order, not library order`() {
        val entries = playlistTracksIn(
            members = listOf(member(1L, 3L), member(2L, 1L)),
            library = listOf(track(1L), track(2L), track(3L)),
        )

        assertEquals(listOf(3L, 1L), entries.map { it.track.id })
        assertEquals(listOf(1L, 2L), entries.map(PlaylistTrack::memberId))
    }

    @Test
    fun `a member whose track is missing is skipped, not blanked`() {
        val entries = playlistTracksIn(
            members = listOf(member(1L, 1L), member(2L, 99L), member(3L, 2L)),
            library = listOf(track(1L), track(2L)),
        )

        assertEquals(listOf(1L, 2L), entries.map { it.track.id })
    }

    @Test
    fun `the same track twice keeps both entries, each with its own member id`() {
        val entries = playlistTracksIn(
            members = listOf(member(1L, 1L), member(2L, 1L)),
            library = listOf(track(1L)),
        )

        assertEquals(2, entries.size)
        assertEquals(listOf(1L, 2L), entries.map(PlaylistTrack::memberId))
    }
}
