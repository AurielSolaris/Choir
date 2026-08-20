// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import app.auriel.choir.MainActivity
import app.auriel.choir.R
import app.auriel.choir.data.AlbumArtLoader
import org.koin.core.context.GlobalContext

/**
 * The four widgets, and the one thing that keeps them current.
 *
 * Nothing here polls. Each widget is redrawn when the player says something has
 * changed — see [app.auriel.choir.playback.WidgetPublisher] — and otherwise
 * sits still, which for a paused track or a phone in a pocket means it costs
 * nothing at all.
 */
object ChoirWidgets {

    private val all = listOf(
        NowPlayingWidget(),
        CompactControlsWidget(),
        LikedSongsWidget(),
        LyricLineWidget(),
    )

    /**
     * Redraws every placed widget.
     *
     * Cheap when none are placed, which is the common case: Glance resolves the
     * ids the launcher holds and does nothing for a widget nobody has added.
     */
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        all.forEach { it.updateAll(appContext) }
    }
}

/**
 * What the four have in common: they read a snapshot, and they may want art.
 *
 * Both happen in `provideGlance`, before any composition — it is the one
 * suspending part of a widget's lifecycle, and doing the work there means the
 * composable itself is a pure function of what was found.
 */
internal abstract class ChoirWidget : GlanceAppWidget() {

    /** Drawn once the snapshot, and any artwork it named, have been read. */
    @Composable
    abstract fun Content(snapshot: WidgetSnapshot, artwork: Bitmap?)

    /** Overridden by the widgets that never show art, to skip decoding it. */
    open val artworkSizePx: Int = 0

    /**
     * Composes from the snapshot as it changes, not as it was.
     *
     * The tempting version of this reads the snapshot here, loads the art here,
     * and hands both to `provideContent` as plain values. It renders correctly
     * exactly once. Glance recomposes a running session in place rather than
     * calling this again, so captured values are frozen for the life of the
     * session — the widget shows whatever was true when it was first drawn and
     * never changes, while the publisher writes update after update that
     * nothing reads. It only looks like it works because a session that has to
     * be recreated, after the process dies, does come back current.
     *
     * So the snapshot is observed and the artwork is derived from it, and both
     * live inside the composition where a change can actually reach the screen.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = WidgetSnapshotStore(context)
        val initial = store.read()

        provideContent {
            val snapshot by remember { store.snapshots() }.collectAsState(initial)

            // Keyed on the URI rather than the snapshot: a play/pause flips the
            // snapshot several times a minute and the cover does not change.
            val artwork by produceState<Bitmap?>(null, snapshot.artworkUri) {
                value = loadArtwork(context, snapshot)
            }

            Content(snapshot, artwork)
        }
    }

    private suspend fun loadArtwork(context: Context, snapshot: WidgetSnapshot): Bitmap? {
        if (artworkSizePx <= 0) return null
        val uri = snapshot.artworkUri?.toUri() ?: return null

        // The app's own loader when the process is already up, so a widget
        // redraw reuses the cache the player filled; its own otherwise.
        val loader = GlobalContext.getOrNull()?.getOrNull<AlbumArtLoader>()
            ?: AlbumArtLoader(context.applicationContext)

        // Decoded small on purpose. A widget's bitmap crosses to the launcher
        // inside a Binder transaction, and a full-size cover is a reliable way
        // to overrun it and have the whole widget silently fail to draw.
        return loader.load(uri, artworkSizePx)?.asAndroidBitmap()
    }
}

// --- Pieces the widgets share ------------------------------------------------

/**
 * The album art, or the mark that stands in for it.
 *
 * Missing artwork is the common case rather than an error, and a grey square
 * with a note in it says so more honestly than a blank does.
 */
@Composable
internal fun Artwork(bitmap: Bitmap?, size: androidx.compose.ui.unit.Dp) {
    val shape = GlanceModifier.size(size).cornerRadius(4.dp)

    if (bitmap != null) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shape,
        )
    } else {
        Box(
            modifier = shape.background(WidgetTheme.divider),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_note),
                contentDescription = null,
                colorFilter = androidx.glance.ColorFilter.tint(WidgetTheme.muted),
                modifier = GlanceModifier.size(size / 3),
            )
        }
    }
}

/**
 * One transport button: an icon, a tap target, and nothing else.
 *
 * The action arrives already built rather than as a reified type parameter,
 * which would make this an inline composable for no gain.
 */
@Composable
internal fun TransportButton(
    action: androidx.glance.action.Action,
    iconRes: Int,
    contentDescription: String,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .clickable(action),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            provider = ImageProvider(iconRes),
            contentDescription = contentDescription,
            colorFilter = androidx.glance.ColorFilter.tint(WidgetTheme.onBackground),
            modifier = GlanceModifier.size(size * 0.55f),
        )
    }
}

/**
 * Play and pause, plus the two skips where there is room for them.
 *
 * The play/pause icon is chosen from the snapshot rather than from anything
 * live, which is why the publisher writes one on every state change: a widget
 * showing a play button over music that is playing is the single most obvious
 * way for this to look broken.
 */
@Composable
internal fun TransportRow(
    snapshot: WidgetSnapshot,
    showSkip: Boolean = true,
    buttonSize: androidx.compose.ui.unit.Dp = 40.dp,
    modifier: GlanceModifier = GlanceModifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showSkip) {
            TransportButton(
                action = actionRunCallback<PreviousAction>(),
                iconRes = R.drawable.ic_widget_previous,
                contentDescription = stringOf(R.string.widget_previous),
                size = buttonSize,
            )
        }
        TransportButton(
            action = actionRunCallback<PlayPauseAction>(),
            iconRes = if (snapshot.isPlaying) {
                R.drawable.ic_widget_pause
            } else {
                R.drawable.ic_widget_play
            },
            contentDescription = stringOf(
                if (snapshot.isPlaying) R.string.widget_pause else R.string.widget_play,
            ),
            size = buttonSize,
        )
        if (showSkip) {
            TransportButton(
                action = actionRunCallback<NextAction>(),
                iconRes = R.drawable.ic_widget_next,
                contentDescription = stringOf(R.string.widget_next),
                size = buttonSize,
            )
        }
    }
}

/**
 * What a widget shows when nothing has ever played.
 *
 * An invitation rather than a report, and the whole surface opens the app —
 * there is nothing here to control yet, so anything smaller than the full area
 * would just be a target to miss.
 */
@Composable
internal fun IdlePrompt(text: String) {
    Box(
        // widgetSurface(), not fillMaxSize(). Without it the widget has no
        // background at all and the prompt floats as grey text directly on the
        // wallpaper — which does not read as an empty widget, it reads as a
        // rendering failure. It was one, until a real home screen showed it.
        modifier = GlanceModifier
            .widgetSurface()
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = WidgetTheme.invitation, maxLines = 3)
    }
}

/** The widget surface: Choir's paper, and the launcher's corner radius. */
internal fun GlanceModifier.widgetSurface(): GlanceModifier =
    fillMaxSize()
        .background(WidgetTheme.background)
        .cornerRadius(16.dp)

/**
 * A string resource, inside a composition that has no `LocalContext`.
 *
 * Glance provides its own context local, and reaching for the Compose one here
 * is a compile-time success and a runtime crash — so this exists mainly to make
 * the right one the easy one.
 */
@Composable
internal fun stringOf(resId: Int): String =
    androidx.glance.LocalContext.current.getString(resId)
