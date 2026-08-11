// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import app.auriel.choir.data.model.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(
    id: Long,
    title: String = "Song $id",
    artist: String = "Artist",
    durationMs: Long = 213_000,
) = Track(
    id = id,
    title = title,
    artist = artist,
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = durationMs,
    trackNumber = 1,
    year = 2000,
)

class M3uParseTest {

    @Test
    fun `a bare list of paths is a playlist`() {
        val entries = M3u.parse(
            """
            Music/One.mp3
            Music/Two.mp3
            """.trimIndent(),
        )

        assertEquals(listOf("Music/One.mp3", "Music/Two.mp3"), entries.map(M3uEntry::path))
    }

    @Test
    fun `EXTINF metadata is attached to the path that follows it`() {
        val entries = M3u.parse(
            """
            #EXTM3U
            #EXTINF:213,Arctic Monkeys - 505
            Music/505.m4a
            """.trimIndent(),
        )

        val entry = entries.single()
        assertEquals("Music/505.m4a", entry.path)
        assertEquals(213L, entry.durationSeconds)
        assertEquals("Arctic Monkeys", entry.artist)
        assertEquals("505", entry.title)
    }

    @Test
    fun `a hyphen inside a title is not mistaken for the artist separator`() {
        val entries = M3u.parse("#EXTINF:200,Artist - Well-Known Song\nx.mp3")

        assertEquals("Artist", entries.single().artist)
        assertEquals("Well-Known Song", entries.single().title)
    }

    @Test
    fun `a label with no separator is taken as the title alone`() {
        val entries = M3u.parse("#EXTINF:200,Just A Title\nx.mp3")

        assertNull(entries.single().artist)
        assertEquals("Just A Title", entries.single().title)
    }

    @Test
    fun `fractional durations are accepted`() {
        val entries = M3u.parse("#EXTINF:213.44,A - B\nx.mp3")

        assertEquals(213L, entries.single().durationSeconds)
    }

    @Test
    fun `unknown directives are skipped rather than treated as paths`() {
        val entries = M3u.parse(
            """
            #EXTM3U
            #PLAYLIST:Late night
            #EXTGRP:Group
            Music/One.mp3
            """.trimIndent(),
        )

        assertEquals(listOf("Music/One.mp3"), entries.map(M3uEntry::path))
    }

    @Test
    fun `an EXTINF with no path after it does not invent an entry`() {
        val entries = M3u.parse("#EXTM3U\n#EXTINF:200,A - B\n")

        assertTrue(entries.isEmpty())
    }

    @Test
    fun `blank lines and a byte order mark do not become entries`() {
        val entries = M3u.parse("\uFEFF#EXTM3U\n\n  \nMusic/One.mp3\n\n")

        assertEquals(listOf("Music/One.mp3"), entries.map(M3uEntry::path))
    }
}

class M3uResolveTest {

    private val library = listOf(
        track(1L, title = "One", artist = "First"),
        track(2L, title = "Two", artist = "Second"),
    )
    private val paths = mapOf(
        1L to "Music/Album/01 One.mp3",
        2L to "Music/Album/02 Two.mp3",
    )
    private val pathOf: (Track) -> String = { paths[it.id].orEmpty() }

    @Test
    fun `an exact path resolves`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("Music/Album/01 One.mp3")),
            library,
            pathOf,
        )

        assertEquals(listOf(1L), tracks.map(Track::id))
    }

    @Test
    fun `a path from a different device still resolves on its last two segments`() {
        // Written by another player, on another phone, under another root.
        val tracks = resolveM3u(
            listOf(M3uEntry("/storage/9C33-6BBD/Media/Album/01 One.mp3")),
            library,
            pathOf,
        )

        assertEquals(listOf(1L), tracks.map(Track::id))
    }

    @Test
    fun `windows separators are understood`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("D:\\Music\\Album\\02 Two.mp3")),
            library,
            pathOf,
        )

        assertEquals(listOf(2L), tracks.map(Track::id))
    }

    @Test
    fun `a file name alone resolves when the folder does not match`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("Somewhere/Else/02 Two.mp3")),
            library,
            pathOf,
        )

        assertEquals(listOf(2L), tracks.map(Track::id))
    }

    @Test
    fun `metadata rescues an entry whose path is nothing like ours`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("no/idea.mp3", title = "Two", artist = "Second")),
            library,
            pathOf,
        )

        assertEquals(listOf(2L), tracks.map(Track::id))
    }

    @Test
    fun `an entry that matches nothing is dropped, not guessed at`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("missing/track.mp3", title = "Nope", artist = "Nobody")),
            library,
            pathOf,
        )

        assertTrue(tracks.isEmpty())
    }

    @Test
    fun `order follows the file, not the library`() {
        val tracks = resolveM3u(
            listOf(M3uEntry("Music/Album/02 Two.mp3"), M3uEntry("Music/Album/01 One.mp3")),
            library,
            pathOf,
        )

        assertEquals(listOf(2L, 1L), tracks.map(Track::id))
    }

    @Test
    fun `a playlist written by Choir reads back as the same tracks`() {
        val written = M3u.write(library, pathOf)

        val tracks = resolveM3u(M3u.parse(written), library, pathOf)

        assertEquals(library.map(Track::id), tracks.map(Track::id))
    }

    @Test
    fun `what Choir writes carries the duration and the credit`() {
        val written = M3u.write(listOf(track(1L, title = "One", artist = "First")), pathOf)

        assertTrue(written.startsWith("#EXTM3U"))
        assertTrue(written.contains("#EXTINF:213,First - One"))
        assertTrue(written.contains("Music/Album/01 One.mp3"))
    }
}
