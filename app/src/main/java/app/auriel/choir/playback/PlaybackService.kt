// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.auriel.choir.MainActivity
import app.auriel.choir.R
import app.auriel.choir.core.MusicLog
import app.auriel.choir.core.Permissions
import app.auriel.choir.data.TrackResolver
import app.auriel.choir.data.queue.QueueRepository
import app.auriel.choir.data.queue.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Background playback, the Media3 equivalent of AOSP's `MediaPlaybackService`.
 *
 * The AOSP service hand-rolled everything a player needs — audio focus, the
 * becoming-noisy receiver, media buttons, the notification, wake locks, queue
 * persistence. Media3 owns all of that now except the last, so what remains here
 * is: build the player, expose a session, and remember the queue.
 */
class PlaybackService : MediaSessionService() {

    private val queueRepository: QueueRepository by inject()
    private val trackResolver: TrackResolver by inject()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var saveJob: Job? = null

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            // Where an FFmpeg decoder was built into this APK, this is what
            // reaches it. Without one the factory behaves as the default did.
            .setRenderersFactory(ChoirRenderersFactory(this))
            // Which containers can be opened at all — a decoder is no use for a
            // file nothing can demux.
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, ChoirExtractorsFactory()))
            // `true` hands audio focus to the player: it ducks for navigation
            // prompts and pauses for calls without any code of ours.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
            .also {
                it.addListener(PersistenceListener())
                it.addAnalyticsListener(DecoderLogger())
            }

        player = exoPlayer
        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setSessionActivity(openAppIntent())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            },
        )

        restoreSavedQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Swiping the app away should not kill music that is still playing — but if
     * playback is idle or paused there is nothing left to keep the process up
     * for, and leaving a dead notification behind is worse than stopping.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveQueueNow()
        scope.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    // --- Saved queue -------------------------------------------------------

    private fun restoreSavedQueue() {
        scope.launch {
            if (!Permissions.hasAudioAccess(this@PlaybackService)) return@launch

            val saved = queueRepository.load() ?: return@launch
            val tracks = trackResolver.byIds(saved.trackIds)
            if (tracks.isEmpty()) {
                MusicLog.i(TAG, "saved queue no longer resolves to any track; dropping it")
                queueRepository.clear()
                return@launch
            }

            val current = player ?: return@launch
            // The user may have started something in the time this took; never
            // stomp on live playback.
            if (current.mediaItemCount > 0) return@launch

            // Tracks may have gone missing, so the saved index can be stale.
            val index = saved.queueIndex.coerceIn(0, tracks.lastIndex)
            current.setMediaItems(tracks.toMediaItems(), index, saved.positionMs)
            current.shuffleModeEnabled = saved.shuffleEnabled
            current.repeatMode = saved.repeatMode
            current.prepare()
            MusicLog.d(TAG, "restored queue of ${tracks.size} at $index")
        }
    }

    /** Coalesces the bursts of events a single user action produces. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveQueueNow()
        }
    }

    private fun saveQueueNow() {
        val snapshot = snapshotQueue() ?: return
        // Deliberately not on `scope`: this also runs from onDestroy, where the
        // service scope is about to be cancelled.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { queueRepository.save(snapshot) }
                .onFailure { MusicLog.w(TAG, "could not persist the queue", it) }
        }
    }

    private fun snapshotQueue(): SavedQueue? {
        val current = player ?: return null
        val count = current.mediaItemCount
        if (count == 0) return null

        val trackIds = (0 until count).mapNotNull { current.getMediaItemAt(it).trackIdOrNull() }
        if (trackIds.size != count) return null // Something not ours is queued.

        return SavedQueue(
            trackIds = trackIds,
            queueIndex = current.currentMediaItemIndex,
            positionMs = current.currentPosition,
            shuffleEnabled = current.shuffleModeEnabled,
            repeatMode = current.repeatMode,
        )
    }

    /**
     * Records which decoder each track actually went through.
     *
     * Whether a file played is easy to observe; *what played it* is not, and
     * the two answers lead to opposite conclusions. A track that works because
     * the phone had a hardware decoder tells you nothing about whether the
     * FFmpeg build is doing anything, and the only honest way to tell them
     * apart is to ask the player.
     */
    private inner class DecoderLogger : AnalyticsListener {

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            MusicLog.i(TAG, "audio decoder: $decoderName")
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: androidx.media3.common.Format,
            decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
        ) {
            MusicLog.i(TAG, "audio format: ${format.sampleMimeType} ${format.bitrate}bps")
        }
    }

    private inner class PersistenceListener : Player.Listener {

        override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) =
            scheduleSave()

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) =
            scheduleSave()

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = scheduleSave()

        override fun onRepeatModeChanged(repeatMode: Int) = scheduleSave()

        /**
         * Pausing is the natural moment to record the position — while playing
         * it moves constantly, and onDestroy catches the rest.
         */
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) scheduleSave()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // A single unreadable file should not strand the queue: log it and
            // move on, which is what the AOSP service did on decode failure.
            MusicLog.w(TAG, "playback error: ${error.errorCodeName}", error)
            val current = player ?: return
            if (current.hasNextMediaItem()) {
                current.seekToNextMediaItem()
                current.prepare()
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val SAVE_DEBOUNCE_MS = 750L
    }
}
