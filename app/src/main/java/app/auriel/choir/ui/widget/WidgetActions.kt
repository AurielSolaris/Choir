// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.ComponentName
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.TrackResolver
import app.auriel.choir.data.likes.LikedTrackEntity
import app.auriel.choir.data.likes.LikesRepository
import app.auriel.choir.playback.PlaybackService
import app.auriel.choir.playback.toMediaItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * What a tap on a widget does.
 *
 * The awkward part is that a widget's buttons are pressed in a process that may
 * have no player in it. Glance runs these callbacks in Choir's process, so the
 * usual approach — build a short-lived [MediaController], say one thing, let it
 * go — works, and has the useful side effect that connecting *starts* the
 * service if it is not running. That is what makes a resume button on an idle
 * widget work at all after a reboot.
 *
 * The alternative, firing a media-button intent at the service, avoids the
 * connection but needs the service already alive to receive it, and says
 * nothing back about whether it arrived. Paying about a tenth of a second to
 * bind is the better trade for a control someone pressed on purpose.
 */

/** Runs [block] against a connected controller, then lets it go. */
private suspend fun withPlayer(context: Context, block: (MediaController) -> Unit) {
    // Media3 requires that a controller is built and touched on the main
    // thread; Glance does not promise which one a callback arrives on.
    withContext(Dispatchers.Main) {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(context.applicationContext, token).buildAsync()

        val controller = suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    continuation.resume(runCatching { future.get() }.getOrNull())
                },
                androidx.core.content.ContextCompat.getMainExecutor(context),
            )
            continuation.invokeOnCancellation { MediaController.releaseFuture(future) }
        }

        if (controller == null) {
            MusicLog.w(TAG, "a widget button could not reach the playback service")
            MediaController.releaseFuture(future)
            return@withContext
        }
        try {
            block(controller)
        } finally {
            controller.release()
        }
    }
}

/**
 * Play, pause, or start the last thing over.
 *
 * The third case is the one that only widgets run into: the process died, the
 * queue was restored from disk but never prepared, and the player is idle
 * holding items nobody asked it to play yet.
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer(context) { player ->
            when {
                player.isPlaying -> player.pause()
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
    }
}

class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer(context) { it.seekToNext() }
    }
}

class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withPlayer(context) { it.seekToPrevious() }
    }
}

/**
 * Likes or unlikes what is playing, straight from the home screen.
 *
 * Reads the track back through [TrackResolver] rather than trusting the
 * snapshot, because a like is stored with the title and artist beside the id —
 * that is what lets it survive MediaStore renumbering the library — and the
 * snapshot is a display copy, not a record.
 */
class ToggleLikeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val koin = GlobalContext.getOrNull() ?: return
        val store = WidgetSnapshotStore(context)
        val trackId = store.read().trackId ?: return

        val track = koin.get<TrackResolver>().byIds(listOf(trackId)).firstOrNull()
        if (track == null) {
            MusicLog.i(TAG, "the liked track is no longer in the library")
            return
        }
        koin.get<LikesRepository>().toggle(track)
        ChoirWidgets.updateAll(context)
    }
}

/**
 * Plays the liked songs, shuffled.
 *
 * No algorithm chose this list and none orders it: the shuffle is a plain
 * random start into a random walk, which is the only thing Choir will ever do
 * that resembles a recommendation.
 */
class ShuffleLikedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val koin = GlobalContext.getOrNull() ?: return

        val likedIds = koin.get<LikesRepository>().liked.first().map(LikedTrackEntity::trackId)
        if (likedIds.isEmpty()) return

        val tracks = koin.get<TrackResolver>().byIds(likedIds)
        if (tracks.isEmpty()) {
            MusicLog.i(TAG, "nothing in the liked list still resolves to a file")
            return
        }

        withPlayer(context) { player ->
            player.setMediaItems(tracks.toMediaItems(), Random.nextInt(tracks.size), C.TIME_UNSET)
            player.shuffleModeEnabled = true
            player.prepare()
            player.play()
        }
    }
}

private const val TAG = "WidgetActions"
