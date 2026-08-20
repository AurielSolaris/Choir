// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(
    id: Long,
    path: String,
    name: String = "song$id.mp3",
    trackNumber: Int = 0,
) = Track(
    id = id,
    title = "Song $id",
    artist = "Artist",
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = 180_000,
    trackNumber = trackNumber,
    year = 2000,
    displayName = name,
    relativePath = path,
)

class FolderTreeTest {

    @Test
    fun `intermediate folders exist even when only the leaf holds music`() {
        val tree = listOf(track(1L, "Music/Nick Drake/Pink Moon/")).toFolderTree("Storage")

        val music = tree.folders.single()
        assertEquals("Music", music.name)
        val artist = music.folders.single()
        assertEquals("Nick Drake", artist.name)
        val album = artist.folders.single()
        assertEquals("Pink Moon", album.name)
        assertEquals(1, album.tracks.size)
        assertTrue(music.tracks.isEmpty())
    }

    @Test
    fun `a path is the folder's identity, slash-terminated and volume-relative`() {
        val tree = listOf(track(1L, "Music/Nick Drake/")).toFolderTree("Storage")

        assertEquals("", tree.path)
        assertEquals("Music/", tree.folders.single().path)
        assertEquals("Music/Nick Drake/", tree.folders.single().folders.single().path)
    }

    @Test
    fun `the count of a folder includes everything below it`() {
        val tree = listOf(
            track(1L, "Music/Album/Disc 1/"),
            track(2L, "Music/Album/Disc 2/"),
            track(3L, "Music/Album/"),
            track(4L, "Podcasts/"),
        ).toFolderTree("Storage")

        assertEquals(4, tree.trackCount)
        assertEquals(3, tree.find("Music/")?.trackCount)
        assertEquals(1, tree.find("Music/Album/Disc 1/")?.trackCount)
    }

    @Test
    fun `a track with no path sorts to the root rather than being dropped`() {
        val tree = listOf(track(1L, ""), track(2L, "Music/")).toFolderTree("Storage")

        assertEquals(listOf(1L), tree.tracks.map(Track::id))
        assertEquals(2, tree.trackCount)
    }

    @Test
    fun `paths are normalised, so a missing or doubled slash groups the same`() {
        val tree = listOf(
            track(1L, "Music/Album/"),
            track(2L, "Music/Album"),
            track(3L, "/Music/Album/"),
        ).toFolderTree("Storage")

        assertEquals(3, tree.find("Music/Album/")?.tracks?.size)
    }

    @Test
    fun `playing a folder takes its own tracks first, then each subfolder in turn`() {
        val tree = listOf(
            track(3L, "Music/Album/Disc 2/"),
            track(2L, "Music/Album/Disc 1/"),
            track(1L, "Music/Album/"),
        ).toFolderTree("Storage")

        assertEquals(
            listOf(1L, 2L, 3L),
            tree.find("Music/Album/")?.allTracks()?.map(Track::id),
        )
    }

    @Test
    fun `tracks in a folder read in track-number order, then by filename`() {
        val tree = listOf(
            track(1L, "Music/", name = "b.mp3"),
            track(2L, "Music/", name = "a.mp3"),
            track(3L, "Music/", name = "z.mp3", trackNumber = 1),
        ).toFolderTree("Storage")

        assertEquals(listOf(3L, 2L, 1L), tree.find("Music/")?.tracks?.map(Track::id))
    }

    @Test
    fun `subfolders are ordered case-insensitively by name`() {
        val tree = listOf(
            track(1L, "music/zappa/"),
            track(2L, "music/Beefheart/"),
            track(3L, "music/apple/"),
        ).toFolderTree("Storage")

        assertEquals(
            listOf("apple", "Beefheart", "zappa"),
            tree.find("music/")?.folders?.map(MusicFolder::name),
        )
    }

    @Test
    fun `a breadcrumb is the folder and every ancestor above it`() {
        val tree = listOf(track(1L, "Music/Nick Drake/Pink Moon/")).toFolderTree("Storage")

        assertEquals(
            listOf("Storage", "Music", "Nick Drake", "Pink Moon"),
            tree.trailTo("Music/Nick Drake/Pink Moon/").map(MusicFolder::name),
        )
    }

    @Test
    fun `a folder that is no longer in the tree resolves to nothing`() {
        val tree = listOf(track(1L, "Music/")).toFolderTree("Storage")

        assertNull(tree.find("Music/Gone/"))
        assertTrue(tree.trailTo("Elsewhere/").isEmpty())
    }

    @Test
    fun `an empty library still has a root to draw`() {
        val tree = emptyList<Track>().toFolderTree("Storage")

        assertTrue(tree.isEmpty)
        assertTrue(tree.folders.isEmpty())
        assertEquals("Storage", tree.name)
    }
}
