// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The one thing a widget reads, and the one thing the player writes.
 *
 * Worth pinning down because the two ends never run at the same time. The
 * player writes this and the process dies; the launcher draws a widget hours
 * later, possibly after the app has been updated underneath it. Everything that
 * can go wrong between those two moments goes wrong here.
 */
class WidgetSnapshotTest {

    @Nested
    @DisplayName("surviving the gap between writing and drawing")
    inner class RoundTrip {

        @Test
        fun `comes back the way it went in`() {
            val snapshot = WidgetSnapshot(
                trackId = 42L,
                title = "Fake Plastic Trees",
                artist = "Radiohead",
                album = "The Bends",
                artworkUri = "content://media/external/audio/albumart/7",
                isPlaying = true,
                isLiked = true,
                lyricLine = "Her green plastic watering can",
                hasTrack = true,
            )

            assertEquals(snapshot, WidgetSnapshot.decode(snapshot.encode()))
        }

        @Test
        fun `an empty snapshot round trips too`() {
            assertEquals(WidgetSnapshot.Empty, WidgetSnapshot.decode(WidgetSnapshot.Empty.encode()))
        }

        /**
         * The store clears before it writes, so an absent key has to mean
         * absent. Were these written as empty strings instead, the previous
         * track's lyric would outlive the track.
         */
        @Test
        fun `absent fields are left out rather than written empty`() {
            val encoded = WidgetSnapshot(trackId = 1L, title = "x", hasTrack = true).encode()

            assertFalse(encoded.containsKey("lyricLine"))
            assertFalse(encoded.containsKey("artworkUri"))
        }

        @Test
        fun `a track with no lyric decodes as having none`() {
            val decoded = WidgetSnapshot.decode(
                WidgetSnapshot(trackId = 1L, title = "x", hasTrack = true).encode(),
            )

            assertNull(decoded.lyricLine)
            assertNull(decoded.artworkUri)
        }
    }

    @Nested
    @DisplayName("a snapshot from a version that is not this one")
    inner class Versioning {

        /**
         * The ordinary case, not an edge one: the app updates while a widget
         * sits on the home screen, and the next thing to read the snapshot is
         * newer than the thing that wrote it.
         */
        @Test
        fun `is discarded rather than half-read`() {
            val stale = WidgetSnapshot(trackId = 1L, title = "x", hasTrack = true)
                .encode()
                .toMutableMap()
                .apply { put("version", "0") }

            assertEquals(WidgetSnapshot.Empty, WidgetSnapshot.decode(stale))
        }

        @Test
        fun `so is one with no version at all`() {
            assertEquals(WidgetSnapshot.Empty, WidgetSnapshot.decode(mapOf("title" to "x")))
        }

        @Test
        fun `and so is nothing`() {
            assertEquals(WidgetSnapshot.Empty, WidgetSnapshot.decode(emptyMap()))
        }
    }

    @Nested
    @DisplayName("what counts as idle")
    inner class Idle {

        /**
         * Paused is not idle. A paused track is still the thing to draw — its
         * name, its art, and a play button — and treating it as nothing would
         * throw away the state at the moment it is most wanted.
         */
        @Test
        fun `a paused track is not idle`() {
            val paused = WidgetSnapshot(trackId = 1L, title = "x", isPlaying = false, hasTrack = true)

            assertFalse(paused.isIdle)
        }

        @Test
        fun `nothing ever played is idle`() {
            assertTrue(WidgetSnapshot.Empty.isIdle)
        }

        /**
         * Something is queued that Choir did not queue — another app's item,
         * with no id to resolve. There is nothing to like, nothing to look up,
         * and nothing honest to say about it.
         */
        @Test
        fun `a track with no id of ours is idle`() {
            val foreign = WidgetSnapshot(trackId = null, title = "x", hasTrack = true)

            assertTrue(foreign.isIdle)
        }
    }
}
