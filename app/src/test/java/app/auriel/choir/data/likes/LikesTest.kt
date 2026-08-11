// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import app.auriel.choir.data.model.Track
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(id: Long) = Track(
    id = id,
    title = "Song $id",
    artist = "Artist",
    artistId = 1L,
    album = "Album",
    albumId = 1L,
    durationMs = 180_000,
    trackNumber = 1,
    year = 2000,
)

private fun like(trackId: Long, likedAt: Long = 0L) =
    LikedTrackEntity(trackId, "Song $trackId", "Artist", 180_000, likedAt)

class LikedTracksInTest {

    @Test
    fun `likes resolve in the order they were stored, not library order`() {
        val stored = listOf(like(3L, likedAt = 300), like(1L, likedAt = 200))
        val library = listOf(track(1L), track(2L), track(3L))

        assertEquals(listOf(3L, 1L), likedTracksIn(stored, library).map(Track::id))
    }

    @Test
    fun `a like whose track is missing is skipped rather than blanking the list`() {
        val stored = listOf(like(1L), like(99L), like(2L))
        val library = listOf(track(1L), track(2L))

        assertEquals(listOf(1L, 2L), likedTracksIn(stored, library).map(Track::id))
    }

    @Test
    fun `nothing liked gives an empty list`() {
        assertTrue(likedTracksIn(emptyList(), listOf(track(1L))).isEmpty())
    }
}
