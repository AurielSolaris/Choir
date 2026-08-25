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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.padding
import androidx.glance.text.Text
import app.auriel.choir.MainActivity
import app.auriel.choir.R

/**
 * A 4×1 line of the song, as it is being sung.
 *
 * The one widget whose content changes while nothing else does, and therefore
 * the one that could have been a timer. It is not. The lyric line changes at
 * times the file states outright, so
 * [app.auriel.choir.playback.WidgetPublisher] sleeps until the next of them and
 * rewrites the snapshot then — one wake per line, only while something is
 * playing, and none at all when the track has no synced words or the widget is
 * not on the home screen.
 *
 * That also settles what to show when there is no lyric. Not a blank, and not
 * the title dressed up as one: the widget says the song has no synced words,
 * because a lyric widget that silently shows nothing is indistinguishable from
 * a broken one.
 */
internal class LyricLineWidget : ChoirWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(STRIP))

    @Composable
    override fun Content(snapshot: WidgetSnapshot, artwork: Bitmap?) {
        if (snapshot.isIdle) {
            IdlePrompt(stringOf(R.string.widget_nothing_played))
            return
        }

        Box(
            modifier = GlanceModifier
                .widgetSurface()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(actionStartActivity<MainActivity>()),
            contentAlignment = Alignment.CenterStart,
        ) {
            val line = snapshot.lyricLine

            if (line.isNullOrBlank()) {
                Column {
                    Text(
                        text = stringOf(R.string.widget_no_synced_lyrics),
                        style = WidgetTheme.invitation,
                        maxLines = 1,
                    )
                    Text(
                        text = snapshot.title.ifBlank { stringOf(R.string.widget_unknown_track) },
                        style = WidgetTheme.metadata,
                        maxLines = 1,
                    )
                }
            } else {
                // The line alone, with nothing beside it. Anything else on this
                // strip competes with the words for the only two lines there are.
                Text(text = line, style = WidgetTheme.lyric, maxLines = 2)
            }
        }
    }

    private companion object {
        val STRIP = DpSize(280.dp, 48.dp)
    }
}

class LyricLineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = LyricLineWidget()
}
