// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import app.auriel.choir.MainActivity
import app.auriel.choir.R

/**
 * A 4×1 strip: what is playing, and the three buttons.
 *
 * For a home screen that has no room to spare. There is no artwork here on
 * purpose — a cover shrunk into a single row reads as a smudge, and dropping it
 * buys the title enough width to be a title rather than an ellipsis.
 *
 * The transport is the point, so it keeps full-size buttons and the text takes
 * whatever is left.
 */
internal class CompactControlsWidget : ChoirWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(STRIP))

    @Composable
    override fun Content(snapshot: WidgetSnapshot, artwork: Bitmap?) {
        if (snapshot.isIdle) {
            IdlePrompt(stringOf(R.string.widget_nothing_played))
            return
        }

        Row(
            modifier = GlanceModifier.widgetSurface().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .clickable(actionStartActivity<MainActivity>()),
            ) {
                Text(
                    text = snapshot.title.ifBlank { stringOf(R.string.widget_unknown_track) },
                    style = WidgetTheme.titleCompact,
                    maxLines = 1,
                )
                if (snapshot.artist.isNotBlank()) {
                    Text(text = snapshot.artist, style = WidgetTheme.metadata, maxLines = 1)
                }
            }
            Spacer(GlanceModifier.width(8.dp))
            TransportRow(snapshot, showSkip = true, buttonSize = 38.dp)
        }
    }

    private companion object {
        val STRIP = DpSize(280.dp, 48.dp)
    }
}

class CompactControlsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = CompactControlsWidget()
}
