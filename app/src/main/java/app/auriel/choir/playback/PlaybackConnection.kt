// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.model.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What the player is currently doing, in the terms the UI needs. */
data class PlaybackUiState(
    val isConnected: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
) {
    /** 0f..1f, and 0f rather than a divide-by-zero while a duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class NowPlaying(
    val trackId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: Uri?,
)

/**
 * The app's handle on [PlaybackService].
 *
 * Everything in the UI talks to the player through this: it owns the
 * [MediaController], mirrors the player's state into a [StateFlow], and ticks
 * the playback position while something is playing. Connect and release are
 * driven by the activity lifecycle, and both are safe to call repeatedly.
 */
class PlaybackConnection(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
    }

    fun connect() {
        if (controllerFuture != null) return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                // A cancelled future means release() beat us here.
                if (future.isCancelled) return@addListener
                controller = runCatching { future.get() }
                    .onFailure { MusicLog.e(TAG, "could not connect to the playback service", it) }
                    .getOrNull()
                    ?.also { it.addListener(listener) }
                publish()
            },
            // Media3 requires controller access on the application main thread.
            androidx.core.content.ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        _state.value = PlaybackUiState()
    }

    // --- Commands ----------------------------------------------------------

    /** Replaces the queue with [tracks] and starts at [startIndex]. */
    fun play(tracks: List<Track>, startIndex: Int) {
        val player = controller ?: return
        if (tracks.isEmpty()) return

        val index = startIndex.coerceIn(0, tracks.lastIndex)
        player.setMediaItems(tracks.toMediaItems(), index, C.TIME_UNSET)
        player.shuffleModeEnabled = false
        player.prepare()
        player.play()
    }

    /** Queues everything in shuffle order, starting somewhere at random. */
    fun shuffleAll(tracks: List<Track>) {
        val player = controller ?: return
        if (tracks.isEmpty()) return

        player.setMediaItems(tracks.toMediaItems(), Random.nextInt(tracks.size), C.TIME_UNSET)
        player.shuffleModeEnabled = true
        player.prepare()
        player.play()
    }

    fun togglePlayPause() {
        val player = controller ?: return
        when {
            player.isPlaying -> player.pause()
            // Reached the end of the queue: start it over rather than doing nothing.
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0, 0L)
                player.play()
            }
            else -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    /** Restarts the current track, or steps back if it only just started. */
    fun previous() {
        controller?.seekToPrevious()
    }

    fun next() {
        controller?.seekToNext()
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        player.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    fun toggleShuffle() {
        val player = controller ?: return
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    /** Cycles off → all → one, matching the AOSP repeat button. */
    fun cycleRepeatMode() {
        val player = controller ?: return
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // --- State mirroring ---------------------------------------------------

    private fun publish() {
        val player = controller
        if (player == null) {
            _state.value = PlaybackUiState()
            return
        }

        _state.value = PlaybackUiState(
            isConnected = true,
            nowPlaying = player.currentMediaItem?.toNowPlaying(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            // Duration is TIME_UNSET until the track is prepared.
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
        )

        if (player.isPlaying) startTicking() else stopTicking()
    }

    private fun startTicking() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                val player = controller ?: break
                _state.value = _state.value.copy(
                    positionMs = player.currentPosition.coerceAtLeast(0L),
                    durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                )
                delay(PROGRESS_TICK_MS)
            }
        }
    }

    private fun stopTicking() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun MediaItem.toNowPlaying() = NowPlaying(
        trackId = trackIdOrNull(),
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString().orEmpty(),
        artworkUri = mediaMetadata.artworkUri,
    )

    private companion object {
        const val TAG = "PlaybackConnection"

        /** Fast enough that the seek bar looks live, slow enough to be free. */
        const val PROGRESS_TICK_MS = 500L
    }
}
