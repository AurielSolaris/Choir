// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.TrackResolver
import app.auriel.choir.data.likes.LikesRepository
import app.auriel.choir.data.lyrics.Lyrics
import app.auriel.choir.data.lyrics.LyricsRepository
import app.auriel.choir.ui.widget.ChoirWidgets
import app.auriel.choir.ui.widget.LyricLineWidget
import app.auriel.choir.ui.widget.WidgetSnapshot
import app.auriel.choir.ui.widget.WidgetSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the home screen widgets telling the truth.
 *
 * The widgets cannot ask the player anything — they are drawn by the launcher,
 * often with Choir's process dead — so something in the app has to write down
 * what is happening. This is that something, and it lives beside the player
 * because the player is what knows.
 *
 * Nothing here polls the player. Every write is provoked by a callback the
 * player was going to make anyway, with one exception, and that exception is
 * the interesting part.
 *
 * ## The lyric line
 *
 * A synced lyric changes while nothing else does, which is exactly the shape of
 * problem that usually becomes a one-second timer. It does not need to be. The
 * `.lrc` states the time of every line, so the next change is a known instant
 * rather than something to be discovered by looking — this sleeps until it, and
 * wakes once.
 *
 * One wake per line, only while playing, and only when a Lyric Line widget is
 * actually on a home screen. A paused phone in a pocket runs no timer at all.
 */
class WidgetPublisher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val likes: LikesRepository,
    private val lyricsRepository: LyricsRepository,
    private val trackResolver: TrackResolver,
    private val player: () -> Player?,
) {

    private val store = WidgetSnapshotStore(context)

    /** The lyric clock, alive only while there is a line worth waiting for. */
    private var lyricJob: Job? = null

    /** Held so that a pause, a like or a seek does not re-read the file. */
    private var loadedForTrackId: Long? = null
    private var loadedLyrics: Lyrics? = null
    private var likedTrackIds: Set<Long> = emptySet()

    private val listener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new track invalidates the lyrics and everything shown about it.
            loadedForTrackId = null
            loadedLyrics = null
            publish(reloadLyrics = true)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlaybackStateChanged(playbackState: Int) = publish()

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek moves which line is being sung without changing anything
            // else, so the lyric clock has to be restarted from where we landed.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) publish()
        }
    }

    fun attach() {
        player()?.addListener(listener)

        // Likes change when the player has nothing to say about it, so the
        // heart on the Now Playing widget follows the repository directly.
        scope.launch {
            likes.likedIds.collect { ids ->
                likedTrackIds = ids
                publish()
            }
        }
        publish(reloadLyrics = true)
    }

    fun detach() {
        lyricJob?.cancel()
        lyricJob = null
        player()?.removeListener(listener)
    }

    /**
     * Writes what the player is doing, and restarts the lyric clock.
     *
     * Safe to call as often as the player calls back: the write is a dozen
     * short strings into preferences, and Glance does nothing for a widget
     * nobody has placed.
     */
    private fun publish(reloadLyrics: Boolean = false) {
        scope.launch {
            val current = player()
            val item = current?.currentMediaItem

            if (current == null || item == null || current.mediaItemCount == 0) {
                writeAndUpdate(WidgetSnapshot.Empty)
                stopLyricClock()
                return@launch
            }

            if (reloadLyrics) loadLyrics(item)

            writeAndUpdate(snapshotOf(current, item, lyricAt(current.currentPosition)))
            restartLyricClock()
        }
    }

    private fun snapshotOf(current: Player, item: MediaItem, lyric: String?): WidgetSnapshot {
        val trackId = item.trackIdOrNull()
        return WidgetSnapshot(
            trackId = trackId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            artworkUri = item.mediaMetadata.artworkUri?.toString(),
            isPlaying = current.isPlaying,
            isLiked = trackId != null && trackId in likedTrackIds,
            lyricLine = lyric,
            hasTrack = true,
        )
    }

    private suspend fun writeAndUpdate(snapshot: WidgetSnapshot) {
        store.write(snapshot)
        runCatching { ChoirWidgets.updateAll(context) }
            .onFailure { MusicLog.w(TAG, "could not redraw the widgets", it) }
    }

    // --- Lyrics --------------------------------------------------------------

    /**
     * Reads the track's lyrics, but only if a Lyric Line widget exists.
     *
     * Otherwise this is a file read, and possibly a network fetch, on every
     * track change, for something nobody is looking at.
     */
    private suspend fun loadLyrics(item: MediaItem) {
        val trackId = item.trackIdOrNull() ?: return
        if (trackId == loadedForTrackId) return

        loadedForTrackId = trackId
        loadedLyrics = null

        if (!hasLyricWidget()) return

        val track = trackResolver.byIds(listOf(trackId)).firstOrNull() ?: return
        val lyrics = runCatching { lyricsRepository.forTrack(track) }
            .onFailure { MusicLog.w(TAG, "could not read lyrics for the widget", it) }
            .getOrNull()

        // Unsynced words have no times to wait for, so they are no use here —
        // a fixed line of an unsynced lyric would be a lie about what is being
        // sung, and the widget says so instead.
        loadedLyrics = lyrics?.takeIf { it.isSynced && !it.isEmpty }
    }

    private suspend fun hasLyricWidget(): Boolean = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(LyricLineWidget::class.java).isNotEmpty()
    }.getOrDefault(false)

    private fun lyricAt(positionMs: Long): String? {
        val lyrics = loadedLyrics ?: return null
        val index = lyrics.indexAt(positionMs)
        return lyrics.lines.getOrNull(index)?.text
    }

    /**
     * Sleeps until the next line begins, writes it, and sleeps again.
     *
     * The loop ends on its own at the last line — there is no next time to wait
     * for — so a track whose lyrics run out halfway leaves nothing running for
     * the rest of it.
     */
    private fun restartLyricClock() {
        stopLyricClock()

        val lyrics = loadedLyrics ?: return
        val current = player() ?: return
        if (!current.isPlaying) return

        lyricJob = scope.launch {
            while (isActive) {
                val playing = player()?.takeIf { it.isPlaying } ?: break
                val position = playing.currentPosition

                val wait = lyricWaitFrom(lyrics, position) ?: break
                delay(wait)

                val playingStill = player()?.takeIf { it.isPlaying } ?: break
                val line = lyricAt(playingStill.currentPosition)
                writeAndUpdate(store.read().copy(lyricLine = line))
            }
        }
    }

    private fun stopLyricClock() {
        lyricJob?.cancel()
        lyricJob = null
    }

    private companion object {
        const val TAG = "WidgetPublisher"
    }
}

/**
 * How long to sleep before the lyric changes, or null when it never will again.
 *
 * The whole argument for this widget not being a timer is in this function: the
 * next change is a fact the file states, so it can be waited for exactly once
 * instead of checked for repeatedly. Null ends the loop — past the last line
 * there is nothing further to wake up for, however long the song runs on.
 */
internal fun lyricWaitFrom(lyrics: Lyrics, positionMs: Long): Long? {
    val next = lyrics.lines.firstOrNull { it.timeMs > positionMs } ?: return null
    // A floor, so that a file with two lines a millisecond apart cannot turn
    // this into the busy loop it was written to avoid.
    return (next.timeMs - positionMs).coerceAtLeast(MIN_LYRIC_WAIT_MS)
}

/** Below this the wake costs more than the line is worth. */
internal const val MIN_LYRIC_WAIT_MS = 250L
