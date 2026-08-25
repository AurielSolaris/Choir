// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    /**
     * What is queued, **in the order it will actually play**.
     *
     * Not the order the items were added in. With shuffle on, the player walks
     * its own scrambled order and this list is that order, so the queue popup
     * shows what comes next rather than what came next before the dice were
     * rolled. See [PlaybackConnection.queueOf].
     */
    val queue: List<QueueItem> = emptyList(),
    /** Where in [queue] the current track sits, or -1 while nothing is loaded. */
    val queueIndex: Int = -1,
) {
    /** 0f..1f, and 0f rather than a divide-by-zero while a duration is unknown. */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** How many tracks are still to come, the current one excluded. */
    val remainingInQueue: Int
        get() = if (queueIndex < 0) 0 else (queue.size - queueIndex - 1).coerceAtLeast(0)
}

/**
 * One entry in the queue popup.
 *
 * [mediaIndex] is the player's own index for the item, which is *not* this
 * entry's position in the list: with shuffle on the two differ, and jumping to
 * a track means naming the index the player knows it by.
 */
data class QueueItem(
    val mediaIndex: Int,
    val trackId: Long?,
    val title: String,
    val artist: String,
    val durationMs: Long,
)

data class NowPlaying(
    val trackId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: Uri?,
)

/**
 * A track that would not play, described well enough to tell the user why.
 *
 * Choir shows files the media scanner could not parse — an AIFF, a WavPack —
 * because hiding them is worse than admitting they are there. The other half of
 * that bargain is saying something when one of them turns out to be unplayable,
 * rather than letting a tap do nothing at all.
 */
data class PlaybackProblem(
    val title: String,
    val format: AudioFormats.Format?,
    val reason: Reason,
) {
    enum class Reason {
        /** Media3 could not open the container. */
        UNREADABLE_CONTAINER,

        /** The container opened, but nothing could decode what was inside. */
        NO_DECODER,

        /** The file itself is gone, unreadable, or damaged. */
        UNREADABLE_FILE,

        /** Something else went wrong. */
        OTHER,
    }
}

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

    /**
     * Emitted once per failed track. A shared flow rather than state: a problem
     * is an event, and a rotation should not replay it.
     */
    private val _problems = MutableSharedFlow<PlaybackProblem>(extraBufferCapacity = 4)
    val problems: SharedFlow<PlaybackProblem> = _problems.asSharedFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var progressJob: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            // Read the item before the service's own error handler skips past it.
            val item = controller?.currentMediaItem
            _problems.tryEmit(
                PlaybackProblem(
                    title = item?.mediaMetadata?.title?.toString().orEmpty(),
                    format = item?.audioFormat(),
                    reason = error.reasonForUser(),
                ),
            )
        }
    }

    /**
     * Media3's error codes, collapsed to the distinctions a listener can act on.
     * Anything finer would be reporting an implementation detail as advice.
     */
    private fun PlaybackException.reasonForUser(): PlaybackProblem.Reason = when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> PlaybackProblem.Reason.UNREADABLE_CONTAINER

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> PlaybackProblem.Reason.NO_DECODER

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> PlaybackProblem.Reason.UNREADABLE_FILE

        else -> PlaybackProblem.Reason.OTHER
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

    /**
     * Jumps to a queue entry, by the index the *player* knows it by.
     *
     * [QueueItem.mediaIndex], not the entry's place in the popup — with shuffle
     * on those are different numbers, and only one of them means anything to
     * the player.
     */
    fun playQueueItem(mediaIndex: Int) {
        val player = controller ?: return
        if (mediaIndex !in 0 until player.mediaItemCount) return

        player.seekToDefaultPosition(mediaIndex)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
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
            queueCache = null
            _state.value = PlaybackUiState()
            return
        }

        val queue = queueOf(player)

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
            queue = queue,
            queueIndex = queue.indexOfFirst { it.mediaIndex == player.currentMediaItemIndex },
        )

        if (player.isPlaying) startTicking() else stopTicking()
    }

    // --- The queue ---------------------------------------------------------

    /**
     * The queue in play order, rebuilt only when it can have changed.
     *
     * `publish()` runs on every player event, and a queue can be the whole
     * library — rebuilding a few thousand entries each time the play/pause
     * state flips would be work for nothing. A [Timeline] is immutable and
     * replaced wholesale when the queue changes, so identity is a sound guard;
     * shuffle is tracked beside it because toggling it reorders the list
     * without touching the timeline.
     */
    private class QueueCache(
        val timeline: Timeline,
        val shuffled: Boolean,
        val items: List<QueueItem>,
    )

    private var queueCache: QueueCache? = null

    private fun queueOf(player: Player): List<QueueItem> {
        val timeline = player.currentTimeline
        val shuffled = player.shuffleModeEnabled

        queueCache?.let { cached ->
            if (cached.timeline === timeline && cached.shuffled == shuffled) return cached.items
        }

        val window = Timeline.Window()
        val items = playOrder(
            count = timeline.windowCount,
            first = timeline.getFirstWindowIndex(shuffled),
            // REPEAT_MODE_OFF regardless of what the player is set to: this is
            // the order of the queue, and repeat is a thing that happens when
            // it runs out, not a reason to list anything twice.
            next = { index -> timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffled) },
        ).map { index ->
            val item = timeline.getWindow(index, window).mediaItem
            QueueItem(
                mediaIndex = index,
                trackId = item.trackIdOrNull(),
                title = item.mediaMetadata.title?.toString().orEmpty(),
                artist = item.mediaMetadata.artist?.toString().orEmpty(),
                durationMs = window.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L,
            )
        }

        queueCache = QueueCache(timeline, shuffled, items)
        return items
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

        /**
         * Fast enough that the seek bar looks live and a synced lyric changes
         * line on time, slow enough to be free. Half a second was fine for the
         * scrubber alone but showed as lag against the words.
         */
        const val PROGRESS_TICK_MS = 250L
    }
}

/**
 * Walks a timeline into the order it will play.
 *
 * Pulled out as a plain function over two numbers and a step, because the
 * interesting part has nothing to do with Media3: it is a linked list that a
 * caller may have made circular. With repeat on, "the index after the last one"
 * is the first one again, and following that blindly builds a list that never
 * ends — so the walk stops at [count] steps, at an unset index, and at any
 * index it has already visited.
 *
 * Returns indices, not items, so the caller decides what a window is worth
 * reading out of.
 */
internal fun playOrder(count: Int, first: Int, next: (Int) -> Int): List<Int> {
    if (count <= 0 || first == C.INDEX_UNSET) return emptyList()

    val order = ArrayList<Int>(count)
    val seen = HashSet<Int>(count)
    var index = first

    while (index != C.INDEX_UNSET && order.size < count && seen.add(index)) {
        order.add(index)
        index = next(index)
    }
    return order
}
