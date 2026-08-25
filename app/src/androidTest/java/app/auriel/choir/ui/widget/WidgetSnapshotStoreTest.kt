// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The snapshot, across the boundary it actually has to cross.
 *
 * `WidgetSnapshotTest` already covers encode and decode as a pair of pure
 * functions. What it cannot cover is the file: whether a write survives being
 * read back by something that was not running when it happened, and whether the
 * flow the Glance session observes actually fires. That is real
 * `SharedPreferences` or it is nothing, so it runs here.
 */
@RunWith(AndroidJUnit4::class)
class WidgetSnapshotStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val playing = WidgetSnapshot(
        trackId = 7L,
        title = "Pink Moon",
        artist = "Nick Drake",
        album = "Pink Moon",
        artworkUri = "content://media/external/audio/albumart/3",
        isPlaying = true,
        isLiked = true,
        lyricLine = "Saw it written and I saw it say",
        hasTrack = true,
    )

    @Before
    fun clear() {
        context.getSharedPreferences("choir_widget_snapshot", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun a_snapshot_survives_being_written_and_read_by_another_reader() {
        WidgetSnapshotStore(context).write(playing)

        // A second store, as a widget drawn later would build for itself.
        val read = WidgetSnapshotStore(context).read()

        assertEquals(playing, read)
    }

    @Test
    fun nothing_written_reads_back_as_idle() {
        val read = WidgetSnapshotStore(context).read()

        assertEquals(WidgetSnapshot.Empty, read)
        assertTrue("an empty store should be idle", read.isIdle)
    }

    /**
     * The write clears first, and this is why it has to.
     *
     * A lyric belongs to the track that was playing when it was written. Left
     * behind, it would sit under the *next* track's title — words from one song
     * captioning another, which is worse than showing no words at all.
     */
    @Test
    fun a_track_without_words_does_not_inherit_the_last_one_s() {
        val store = WidgetSnapshotStore(context)
        store.write(playing)

        store.write(playing.copy(trackId = 8L, title = "Road", lyricLine = null))

        assertNull(WidgetSnapshotStore(context).read().lyricLine)
    }

    /**
     * What the Glance session observes. If this flow does not fire, a placed
     * widget freezes at whatever was true when it was first drawn — the bug the
     * observation exists to remove.
     */
    @Test
    fun the_flow_reports_a_write_that_happens_after_it_is_collected() = runBlocking {
        val store = WidgetSnapshotStore(context)
        store.write(playing)

        val paused = CompletableDeferred<WidgetSnapshot>()
        val collector = launch(Dispatchers.Default) {
            store.snapshots().collect { if (!it.isPlaying) paused.complete(it) }
        }

        // The write has to come after the listener is registered, or this would
        // pass on the flow's opening read and prove nothing about the listener.
        delay(REGISTER_MS)
        store.write(playing.copy(isPlaying = false))

        val seen = withTimeout(TIMEOUT_MS) { paused.await() }
        collector.cancel()

        assertEquals(false, seen.isPlaying)
        assertEquals("Pink Moon", seen.title)
    }

    @Test
    fun the_flow_starts_with_what_is_already_stored() = runBlocking {
        WidgetSnapshotStore(context).write(playing)

        val first = withTimeout(TIMEOUT_MS) { WidgetSnapshotStore(context).snapshots().first() }

        assertEquals(playing, first)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** Long enough for a preference listener to be registered. */
        const val REGISTER_MS = 300L
    }
}
