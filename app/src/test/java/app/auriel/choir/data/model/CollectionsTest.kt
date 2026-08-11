// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The browse views are derived from the track list rather than queried, so this
 * grouping is what Albums and Artists actually show.
 */
class CollectionsTest {

    private fun track(
        id: Long,
        title: String = "Track $id",
        artist: String = "Artist",
        artistId: Long = 1L,
        album: String = "Album",
        albumId: Long = 10L,
        trackNumber: Int = 0,
        year: Int = 0,
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        album = album,
        albumId = albumId,
        durationMs = 1_000,
        trackNumber = trackNumber,
        year = year,
    )

    @Nested
    @DisplayName("toAlbums")
    inner class ToAlbums {

        @Test
        fun `groups tracks by album id and counts them`() {
            val albums = listOf(
                track(1, albumId = 10, album = "One"),
                track(2, albumId = 10, album = "One"),
                track(3, albumId = 20, album = "Two"),
            ).toAlbums()

            assertEquals(2, albums.size)
            assertEquals(2, albums.first { it.id == 10L }.trackCount)
            assertEquals(1, albums.first { it.id == 20L }.trackCount)
        }

        @Test
        fun `credits a single artist to the album`() {
            val albums = listOf(
                track(1, artist = "Bach", artistId = 7),
                track(2, artist = "Bach", artistId = 7),
            ).toAlbums()

            assertEquals("Bach", albums.single().artist)
            assertEquals(7L, albums.single().artistId)
        }

        @Test
        fun `credits a compilation to various artists`() {
            val albums = listOf(
                track(1, artist = "Bach", artistId = 7),
                track(2, artist = "Handel", artistId = 8),
            ).toAlbums()

            assertEquals(VARIOUS_ARTISTS, albums.single().artist)
            // No single artist owns it, so it belongs to none of them.
            assertEquals(0L, albums.single().artistId)
        }

        @Test
        fun `takes the latest year any track claims`() {
            val albums = listOf(
                track(1, year = 0),
                track(2, year = 1999),
            ).toAlbums()

            assertEquals(1999, albums.single().year)
        }

        @Test
        fun `sorts by title ignoring case`() {
            val albums = listOf(
                track(1, albumId = 1, album = "banjo"),
                track(2, albumId = 2, album = "Accordion"),
                track(3, albumId = 3, album = "cello"),
            ).toAlbums()

            assertEquals(listOf("Accordion", "banjo", "cello"), albums.map(Album::title))
        }
    }

    @Nested
    @DisplayName("toArtists")
    inner class ToArtists {

        @Test
        fun `counts distinct albums and all tracks`() {
            val artists = listOf(
                track(1, artistId = 7, albumId = 10),
                track(2, artistId = 7, albumId = 10),
                track(3, artistId = 7, albumId = 11),
            ).toArtists()

            val artist = artists.single()
            assertEquals(2, artist.albumCount)
            assertEquals(3, artist.trackCount)
        }

        @Test
        fun `sorts by name ignoring case`() {
            val artists = listOf(
                track(1, artistId = 1, artist = "zoe"),
                track(2, artistId = 2, artist = "Adam"),
            ).toArtists()

            assertEquals(listOf("Adam", "zoe"), artists.map(Artist::name))
        }
    }

    @Nested
    @DisplayName("inAlbumOrder")
    inner class InAlbumOrder {

        @Test
        fun `orders by track number`() {
            val ordered = listOf(
                track(1, title = "C", trackNumber = 3),
                track(2, title = "A", trackNumber = 1),
                track(3, title = "B", trackNumber = 2),
            ).inAlbumOrder()

            assertEquals(listOf("A", "B", "C"), ordered.map(Track::title))
        }

        @Test
        fun `sends untagged tracks to the end, sorted by title`() {
            val ordered = listOf(
                track(1, title = "zeta", trackNumber = 0),
                track(2, title = "Alpha", trackNumber = 0),
                track(3, title = "Numbered", trackNumber = 5),
            ).inAlbumOrder()

            assertEquals(listOf("Numbered", "Alpha", "zeta"), ordered.map(Track::title))
        }
    }
}
