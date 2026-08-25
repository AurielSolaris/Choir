// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.auriel.choir.MainActivity
import app.auriel.choir.R

/**
 * The main widget: what is playing, and the controls for it.
 *
 * Two shapes rather than one stretched. At 2×2 the art is the widget and the
 * words sit under it; at 4×2 there is room to put them beside it and to add the
 * skip buttons, which is a different arrangement rather than the same one wider.
 * Glance picks between them by the size the launcher reports, so the widget is
 * composed once per shape and the host chooses — no measuring, no reflow.
 */
internal class NowPlayingWidget : ChoirWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SQUARE, WIDE))

    /** Large enough for the 4×2 art on a dense screen, small enough to send. */
    override val artworkSizePx = 256

    @Composable
    override fun Content(snapshot: WidgetSnapshot, artwork: Bitmap?) {
        if (snapshot.isIdle) {
            IdlePrompt(stringOf(R.string.widget_nothing_played))
            return
        }
        if (LocalSize.current.width >= WIDE.width) {
            Wide(snapshot, artwork)
        } else {
            Square(snapshot, artwork)
        }
    }

    /** 2×2: the cover, with the title and artist beneath it. */
    @Composable
    private fun Square(snapshot: WidgetSnapshot, artwork: Bitmap?) {
        Column(
            modifier = GlanceModifier.widgetSurface().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centred rather than packed at the top. A launcher does not hand
            // out the size that was asked for — a 2x2 request came back half as
            // tall again on the device this was checked on — and top-aligned
            // content in a box taller than it expected reads as a broken widget
            // with a hole under it.
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artwork, 64.dp)
            Spacer(GlanceModifier.size(6.dp))
            Titles(snapshot, centred = true, maxLines = 1)
            Spacer(GlanceModifier.size(4.dp))
            // No skips: three buttons in this width leaves each one too small
            // to hit, and play/pause is the one that earns the space.
            TransportRow(snapshot, showSkip = false, buttonSize = 36.dp)
        }
    }

    /** 4×2: the cover beside the words, with the full transport row. */
    @Composable
    private fun Wide(snapshot: WidgetSnapshot, artwork: Bitmap?) {
        Row(
            modifier = GlanceModifier.widgetSurface().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artwork, 72.dp)
            Spacer(GlanceModifier.width(12.dp))
            Column(modifier = GlanceModifier.defaultWeight()) {
                Titles(snapshot, centred = false, maxLines = 1)
                Spacer(GlanceModifier.size(6.dp))
                TransportRow(snapshot, showSkip = true, buttonSize = 40.dp)
            }
            LikeButton(snapshot)
        }
    }

    /**
     * The track and the artist.
     *
     * Tapping either opens the app at what is playing — the text is the largest
     * target on the widget and the least useful thing to make inert.
     */
    @Composable
    private fun Titles(snapshot: WidgetSnapshot, centred: Boolean, maxLines: Int) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .clickable(actionStartActivity<MainActivity>()),
            horizontalAlignment = if (centred) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                text = snapshot.title.ifBlank { stringOf(R.string.widget_unknown_track) },
                style = WidgetTheme.title,
                maxLines = maxLines,
            )
            if (snapshot.artist.isNotBlank()) {
                Text(text = snapshot.artist, style = WidgetTheme.metadata, maxLines = 1)
            }
        }
    }

    @Composable
    private fun LikeButton(snapshot: WidgetSnapshot) {
        TransportButton(
            action = actionRunCallback<ToggleLikeAction>(),
            iconRes = if (snapshot.isLiked) {
                R.drawable.ic_widget_heart_filled
            } else {
                R.drawable.ic_widget_heart
            },
            contentDescription = stringOf(
                if (snapshot.isLiked) R.string.widget_unlike else R.string.widget_like,
            ),
            size = 36.dp,
        )
    }

    private companion object {
        val SQUARE = DpSize(140.dp, 110.dp)
        val WIDE = DpSize(280.dp, 110.dp)
    }
}

class NowPlayingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = NowPlayingWidget()
}
