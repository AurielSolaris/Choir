// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import app.auriel.choir.data.documentTrackId
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.model.TrackSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun indexed(id: Long, path: String, name: String) = Track(
    id = id,
    title = "Song $id",
    artist = "Artist",
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = 180_000,
    trackNumber = 1,
    year = 2000,
    displayName = name,
    relativePath = path,
)

private fun stored(
    documentUri: String,
    path: String,
    name: String,
    title: String = "",
    artist: String = "",
    album: String = "",
    durationMs: Long = 0L,
) = FolderFileEntity(
    documentUri = documentUri,
    trackId = documentTrackId(documentUri),
    treeUri = "content://tree/primary%3AMusic",
    relativePath = path,
    displayName = name,
    mimeType = "application/octet-stream",
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNumber = 0,
    year = 0,
    sizeBytes = 1_000_000,
)

class UnindexedTracksTest {

    @Test
    fun `a file MediaStore already has is not carried a second time`() {
        val folder = listOf(stored("doc:1", "Music/", "song.mp3").toTrack())
        val library = listOf(indexed(1L, "Music/", "song.mp3"))

        assertTrue(unindexedTracks(folder, library).isEmpty())
    }

    @Test
    fun `a file MediaStore refused to index is kept`() {
        val folder = listOf(stored("doc:1", "Music/", "probe.wv").toTrack())
        val library = listOf(indexed(1L, "Music/", "song.mp3"))

        assertEquals(listOf("probe.wv"), unindexedTracks(folder, library).map(Track::displayName))
    }

    @Test
    fun `matching ignores case, because two providers need not agree on it`() {
        val folder = listOf(stored("doc:1", "MUSIC/", "Song.MP3").toTrack())
        val library = listOf(indexed(1L, "music/", "song.mp3"))

        assertTrue(unindexedTracks(folder, library).isEmpty())
    }

    @Test
    fun `the same file under two granted folders appears once`() {
        val folder = listOf(
            stored("doc:1", "Music/", "probe.wv").toTrack(),
            stored("doc:2", "Music/", "probe.wv").toTrack(),
        )

        assertEquals(1, unindexedTracks(folder, emptyList()).size)
    }

    @Test
    fun `the same filename in two folders is two different files`() {
        val folder = listOf(
            stored("doc:1", "Music/A/", "01.wv").toTrack(),
            stored("doc:2", "Music/B/", "01.wv").toTrack(),
        )

        assertEquals(2, unindexedTracks(folder, emptyList()).size)
    }

    @Test
    fun `an empty library keeps everything the folders found`() {
        val folder = listOf(stored("doc:1", "Music/", "probe.wv").toTrack())

        assertEquals(1, unindexedTracks(folder, emptyList()).size)
    }
}

class FolderTrackTest {

    @Test
    fun `a file with no readable tags falls back to its filename, extension and all`() {
        val track = stored("doc:1", "Music/", "probe.wv").toTrack()

        assertEquals("probe.wv", track.title)
        assertEquals("Unknown artist", track.artist)
        assertEquals("Unknown album", track.album)
        assertEquals(0L, track.durationMs)
        assertTrue(!track.hasKnownDuration)
    }

    @Test
    fun `tags win where the platform could read them`() {
        val track = stored(
            "doc:1",
            "Music/",
            "01.flac",
            title = "Pink Moon",
            artist = "Nick Drake",
            album = "Pink Moon",
            durationMs = 122_000,
        ).toTrack()

        assertEquals("Pink Moon", track.title)
        assertEquals("Nick Drake", track.artist)
        assertEquals(122_000L, track.durationMs)
    }

    @Test
    fun `a folder track carries the document URI it must be opened with`() {
        val track = stored("content://tree/doc%3A1", "Music/", "probe.wv").toTrack()

        assertEquals(TrackSource.Folder("content://tree/doc%3A1"), track.source)
        assertTrue(track.isFromFolder)
        assertEquals("Music/probe.wv", track.path)
    }
}

class DocumentTrackIdTest {

    @Test
    fun `the same document always gets the same id`() {
        assertEquals(
            documentTrackId("content://com.android.externalstorage/tree/primary%3AMusic"),
            documentTrackId("content://com.android.externalstorage/tree/primary%3AMusic"),
        )
    }

    @Test
    fun `different documents get different ids`() {
        assertNotEquals(documentTrackId("doc:1"), documentTrackId("doc:2"))
    }

    @Test
    fun `ids are negative, so they cannot collide with a MediaStore id`() {
        val ids = (1..2_000).map { documentTrackId("content://tree/primary%3AMusic/$it.wv") }

        assertTrue(ids.all { it < 0L }, "every folder id must be negative")
        assertEquals(ids.size, ids.distinct().size, "no collisions across a large folder")
    }
}
