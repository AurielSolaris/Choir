// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.folders.FolderFileEntity
import app.auriel.choir.data.folders.FolderRootEntity
import app.auriel.choir.data.folders.FoldersDao
import app.auriel.choir.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** In-memory stand-in for the Room DAO, keeping these tests off the device. */
private class FakeFoldersDao(files: List<FolderFileEntity> = emptyList()) : FoldersDao {
    val rows = MutableStateFlow(files)

    /** Every id asked about, so the test can prove which source was consulted. */
    val asked = mutableListOf<List<Long>>()

    override fun observeRoots(): Flow<List<FolderRootEntity>> = MutableStateFlow(emptyList())

    override suspend fun roots(): List<FolderRootEntity> = emptyList()

    override suspend fun insertRoot(root: FolderRootEntity) = Unit

    override suspend fun deleteRoot(treeUri: String) = Unit

    override fun observeFiles(): Flow<List<FolderFileEntity>> = rows

    override suspend fun filesIn(treeUri: String): List<FolderFileEntity> =
        rows.value.filter { it.treeUri == treeUri }

    override suspend fun filesByTrackId(trackIds: List<Long>): List<FolderFileEntity> {
        asked += trackIds
        return rows.value.filter { it.trackId in trackIds }
    }

    override suspend fun insertFiles(files: List<FolderFileEntity>) {
        rows.value = rows.value.filterNot { row -> files.any { it.documentUri == row.documentUri } } +
            files
    }

    override suspend fun deleteFilesIn(treeUri: String) {
        rows.value = rows.value.filterNot { it.treeUri == treeUri }
    }
}

private fun indexed(id: Long) = Track(
    id = id,
    title = "Song $id",
    artist = "Artist",
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = 180_000,
    trackNumber = 1,
    year = 2000,
    displayName = "song$id.mp3",
)

private fun folderFile(trackId: Long, name: String = "probe.wv") = FolderFileEntity(
    documentUri = "content://tree/doc%3A$trackId",
    trackId = trackId,
    treeUri = "content://tree/primary%3AMusic",
    relativePath = "Music/",
    displayName = name,
    mimeType = "application/octet-stream",
    title = "",
    artist = "",
    album = "",
    durationMs = 0L,
    trackNumber = 0,
    year = 0,
    sizeBytes = 1_000_000,
)

/** Records what the indexed half was asked, and answers with what it holds. */
private class FakeIndexedTracks(private val library: List<Track> = emptyList()) : IndexedTracks {
    val asked = mutableListOf<List<Long>>()

    override suspend fun byIds(ids: List<Long>): List<Track> {
        asked += ids
        return library.filter { it.id in ids }
    }
}

class TrackResolverTest {

    @Test
    fun `a queue of both kinds comes back whole, in the order it was saved`() = runTest {
        val dao = FakeFoldersDao(listOf(folderFile(-7L)))
        val mediaStore = FakeIndexedTracks(listOf(indexed(1L), indexed(2L)))

        val resolved = TrackResolver(mediaStore, dao).byIds(listOf(2L, -7L, 1L))

        assertEquals(listOf(2L, -7L, 1L), resolved.map(Track::id))
    }

    @Test
    fun `a folder track is asked of the folder table and never of MediaStore`() = runTest {
        val dao = FakeFoldersDao(listOf(folderFile(-7L)))
        val mediaStore = FakeIndexedTracks()

        val resolved = TrackResolver(mediaStore, dao).byIds(listOf(-7L))

        assertEquals(listOf(-7L), resolved.map(Track::id))
        assertEquals(listOf(listOf(-7L)), dao.asked)
        assertTrue(mediaStore.asked.isEmpty())
        assertEquals("probe.wv", resolved.single().title)
    }

    @Test
    fun `an all-MediaStore queue does not touch the folder table`() = runTest {
        val dao = FakeFoldersDao()
        val mediaStore = FakeIndexedTracks(listOf(indexed(1L)))

        TrackResolver(mediaStore, dao).byIds(listOf(1L))

        assertTrue(dao.asked.isEmpty())
        assertEquals(listOf(listOf(1L)), mediaStore.asked)
    }

    @Test
    fun `a track whose file has gone is dropped, not substituted`() = runTest {
        val dao = FakeFoldersDao()
        val mediaStore = FakeIndexedTracks(listOf(indexed(1L)))

        val resolved = TrackResolver(mediaStore, dao).byIds(listOf(1L, -9L, 5L))

        assertEquals(listOf(1L), resolved.map(Track::id))
    }

    @Test
    fun `a queue holding one track twice resolves it in both places`() = runTest {
        val dao = FakeFoldersDao()
        val mediaStore = FakeIndexedTracks(listOf(indexed(1L)))

        val resolved = TrackResolver(mediaStore, dao).byIds(listOf(1L, 1L))

        assertEquals(listOf(1L, 1L), resolved.map(Track::id))
    }

    @Test
    fun `nothing saved resolves to nothing, without asking anyone`() = runTest {
        val dao = FakeFoldersDao()
        val mediaStore = FakeIndexedTracks()

        assertTrue(TrackResolver(mediaStore, dao).byIds(emptyList()).isEmpty())
        assertTrue(dao.asked.isEmpty())
        assertTrue(mediaStore.asked.isEmpty())
    }
}
