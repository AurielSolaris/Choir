// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.likes.LikedTrackEntity
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.playlist.PlaylistMemberEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(
    id: Long,
    title: String = "Song $id",
    artist: String = "Artist",
    durationMs: Long = 180_000,
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

private fun like(
    trackId: Long,
    title: String = "Song $trackId",
    artist: String = "Artist",
    durationMs: Long = 180_000,
) = LikedTrackEntity(trackId, title, artist, durationMs, likedAt = 0L)

private fun member(
    memberId: Long,
    trackId: Long,
    title: String = "Song $trackId",
    artist: String = "Artist",
    durationMs: Long = 180_000,
) = PlaylistMemberEntity(
    id = memberId,
    playlistId = 1L,
    trackId = trackId,
    position = memberId.toInt(),
    title = title,
    artist = artist,
    durationMs = durationMs,
)

/**
 * The re-linking rules, shared by liked songs and playlist members — both keep
 * a MediaStore id that the platform is free to change under them.
 */
class RelinkTest {

    @Test
    fun `nothing to do when every reference still resolves`() {
        val relinks = relinksFor(
            stored = listOf(like(1L), like(2L)),
            library = listOf(track(1L), track(2L)),
        )

        assertTrue(relinks.isEmpty())
    }

    @Test
    fun `a renumbered track carries its reference across`() {
        // What a media rescan does: same file, same tags, brand new id.
        val relinks = relinksFor(
            stored = listOf(like(7L, title = "Ghosts")),
            library = listOf(track(9001L, title = "Ghosts")),
        )

        assertEquals(listOf(Relink(oldTrackId = 7L, newTrackId = 9001L)), relinks)
    }

    @Test
    fun `matching ignores case and surrounding space in the tags`() {
        val relinks = relinksFor(
            stored = listOf(like(7L, title = " Ghosts ", artist = "THE BAND")),
            library = listOf(track(8L, title = "ghosts", artist = "the band")),
        )

        assertEquals(listOf(Relink(7L, 8L)), relinks)
    }

    @Test
    fun `a few milliseconds of drift still counts as the same track`() {
        // Best effort: matching is on whole seconds, so drift within a second
        // is absorbed. Drift across a second boundary is not, and the reference
        // just stays where it is — which is why the row is never deleted.
        val relinks = relinksFor(
            stored = listOf(like(7L, title = "Ghosts", durationMs = 180_000)),
            library = listOf(track(8L, title = "Ghosts", durationMs = 180_400)),
        )

        assertEquals(listOf(Relink(7L, 8L)), relinks)
    }

    @Test
    fun `a track already referenced under another id is not stolen`() {
        val relinks = relinksFor(
            stored = listOf(like(1L, title = "Twice"), like(2L, title = "Twice")),
            library = listOf(track(2L, title = "Twice")),
        )

        assertTrue(relinks.isEmpty())
    }

    @Test
    fun `two candidates that look identical are left alone`() {
        val relinks = relinksFor(
            stored = listOf(like(1L, title = "Intro")),
            library = listOf(track(10L, title = "Intro"), track(11L, title = "Intro")),
        )

        assertTrue(relinks.isEmpty())
    }

    @Test
    fun `two distinct orphan ids that look identical are left alone`() {
        val relinks = relinksFor(
            stored = listOf(like(1L, title = "Intro"), like(2L, title = "Intro")),
            library = listOf(track(10L, title = "Intro")),
        )

        assertTrue(relinks.isEmpty())
    }

    @Test
    fun `one track listed twice in a playlist gives one relink, not an ambiguity`() {
        // A playlist may hold the same track more than once. Those rows share a
        // fingerprint *and* an id, so they are one reference, not a conflict —
        // the caller applies the single relink to every row holding that id.
        val relinks = relinksFor(
            stored = listOf(
                member(memberId = 1L, trackId = 5L, title = "Encore"),
                member(memberId = 2L, trackId = 5L, title = "Encore"),
            ),
            library = listOf(track(77L, title = "Encore")),
        )

        assertEquals(listOf(Relink(5L, 77L)), relinks)
    }

    @Test
    fun `an empty library is treated as unreadable, not as everything deleted`() {
        val relinks = relinksFor(stored = listOf(like(1L)), library = emptyList())

        assertTrue(relinks.isEmpty())
    }

    @Test
    fun `a genuinely deleted track produces no relink and is not dropped here`() {
        val relinks = relinksFor(
            stored = listOf(like(1L, title = "Gone")),
            library = listOf(track(2L, title = "Something else")),
        )

        assertTrue(relinks.isEmpty())
    }
}
