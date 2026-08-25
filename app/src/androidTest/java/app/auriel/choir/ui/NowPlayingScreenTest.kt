// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.auriel.choir.playback.NowPlaying
import app.auriel.choir.playback.PlaybackUiState
import app.auriel.choir.playback.QueueItem
import app.auriel.choir.ui.library.LyricsState
import app.auriel.choir.ui.nowplaying.NowPlayingScreen
import app.auriel.choir.ui.theme.ChoirTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The player screen, and the queue popup that opens over it.
 *
 * Instrumented rather than a JVM test because this is a composition: the sheet
 * is a real window drawn over the screen, and the thing worth checking is that
 * it opens, lists the queue in the order it was given, and reports the *player's*
 * index for a row rather than the row's own position — which is the distinction
 * that only matters once shuffle is on, and the one that would silently play
 * the wrong track if it were got wrong.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val nowPlaying = NowPlaying(
        trackId = 7L,
        title = "Pink Moon",
        artist = "Nick Drake",
        album = "Pink Moon",
        artworkUri = null,
    )

    /**
     * A queue that is *not* in index order — the shape shuffle produces. The
     * second entry listed is the player's item 0, and tapping it has to say 0.
     */
    private val shuffledQueue = listOf(
        QueueItem(mediaIndex = 2, trackId = 9L, title = "Road", artist = "Nick Drake", durationMs = 121_000),
        QueueItem(mediaIndex = 0, trackId = 7L, title = "Pink Moon", artist = "Nick Drake", durationMs = 128_000),
        QueueItem(mediaIndex = 1, trackId = 8L, title = "Place To Be", artist = "Nick Drake", durationMs = 163_000),
    )

    private fun state(
        queue: List<QueueItem> = shuffledQueue,
        queueIndex: Int = 1,
        shuffleEnabled: Boolean = true,
        repeatMode: Int = Player.REPEAT_MODE_ALL,
    ) = PlaybackUiState(
        isConnected = true,
        nowPlaying = nowPlaying,
        isPlaying = true,
        positionMs = 1_000,
        durationMs = 128_000,
        shuffleEnabled = shuffleEnabled,
        repeatMode = repeatMode,
        queue = queue,
        queueIndex = queueIndex,
    )

    private fun show(
        state: PlaybackUiState = state(),
        onPlayQueueItem: (Int) -> Unit = {},
        onToggleShuffle: () -> Unit = {},
        onCycleRepeat: () -> Unit = {},
    ) {
        compose.setContent {
            ChoirTheme(darkTheme = false) {
                NowPlayingScreen(
                    state = state,
                    onBack = {},
                    onPlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    onSeek = {},
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    onPlayQueueItem = onPlayQueueItem,
                    isLiked = false,
                    onToggleLike = {},
                    lyrics = LyricsState(),
                )
            }
        }
    }

    private fun openQueue() {
        compose.onNodeWithContentDescription("Queue").performClick()
        compose.waitForIdle()
    }

    @Test
    fun the_player_shows_what_is_playing() {
        show()

        compose.onNodeWithText("Pink Moon").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
    }

    @Test
    fun there_is_no_queue_button_when_there_is_no_queue() {
        show(state(queue = emptyList(), queueIndex = -1))

        compose.onNodeWithContentDescription("Queue").assertDoesNotExist()
    }

    @Test
    fun the_queue_button_opens_the_queue() {
        show()
        openQueue()

        compose.onNodeWithText("Up next").assertIsDisplayed()
        compose.onNodeWithText("Road").assertIsDisplayed()
        compose.onNodeWithText("Place To Be").assertIsDisplayed()
    }

    /**
     * The header has to say *how* the queue will play, because with shuffle on
     * the list is not the order the tracks were added in and the user has no
     * other way to know that.
     */
    @Test
    fun the_queue_says_whether_it_is_shuffled_and_repeating() {
        show()
        openQueue()

        compose.onNodeWithText("Shuffled · repeating all · 1 track left").assertIsDisplayed()
    }

    @Test
    fun a_queue_in_order_says_so() {
        show(state(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_OFF))
        openQueue()

        compose.onNodeWithText("In order · 1 track left").assertIsDisplayed()
    }

    @Test
    fun repeat_one_is_named_separately_from_repeat_all() {
        show(state(shuffleEnabled = false, repeatMode = Player.REPEAT_MODE_ONE))
        openQueue()

        compose.onNodeWithText("In order · repeating this track · 1 track left").assertIsDisplayed()
    }

    /**
     * The one that would be a real bug. "Place To Be" is listed third but is
     * the player's item 1; reporting its row position instead would start a
     * different song than the one that was tapped.
     */
    @Test
    fun tapping_a_row_reports_the_players_index_not_the_rows_position() {
        var played: Int? = null
        show(onPlayQueueItem = { played = it })
        openQueue()

        compose.onNodeWithText("Place To Be").performClick()

        assertEquals(1, played)
    }

    /**
     * The current track is marked rather than left to be inferred from the
     * scroll position, which is the only thing that says *where* in the queue
     * you are once the list has been scrolled by hand.
     */
    @Test
    fun the_queue_marks_the_track_that_is_playing() {
        show()
        openQueue()

        compose.onNodeWithContentDescription("Playing now").assertIsDisplayed()
    }

    /**
     * Both mode toggles are reachable from inside the queue: one from the
     * player behind the sheet, one from the sheet's own header.
     */
    @Test
    fun the_queue_carries_its_own_shuffle_and_repeat_toggles() {
        show()
        openQueue()

        compose.onAllNodesWithContentDescription("Shuffle").assertCountEquals(2)
        compose.onAllNodesWithContentDescription("Repeat").assertCountEquals(2)
    }
}
