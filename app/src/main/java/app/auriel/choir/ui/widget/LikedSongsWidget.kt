// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text
import app.auriel.choir.R
import app.auriel.choir.data.likes.LikesRepository
import kotlinx.coroutines.flow.flowOf
import org.koin.core.context.GlobalContext

/**
 * A 2×2 shortcut into the liked songs.
 *
 * The only list Choir keeps on anyone's behalf, and the only widget that starts
 * playback from nothing rather than controlling playback already under way. It
 * shows how many songs are in it, because that number is the whole state a
 * manually curated list has — there is no "recently added", no "made for you",
 * and nothing here is ordered by anything but chance once the shuffle starts.
 *
 * Reads the count itself rather than from the snapshot: likes change when the
 * player has nothing to say, so a snapshot written on playback events would be
 * stale exactly when someone had just liked something.
 */
internal class LikedSongsWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SQUARE))

    /**
     * Observed rather than read once, for the reason spelled out in
     * [ChoirWidget.provideGlance]: a value captured here is frozen for the life
     * of the Glance session, and liking a song would never reach the widget.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val liked = GlobalContext.getOrNull()?.getOrNull<LikesRepository>()?.liked

        provideContent {
            val count by remember { liked ?: flowOf(emptyList()) }
                .collectAsState(emptyList())

            Content(count.size)
        }
    }

    @Composable
    private fun Content(count: Int) {
        if (count == 0) {
            IdlePrompt(stringOf(R.string.widget_nothing_liked))
            return
        }

        Column(
            modifier = GlanceModifier
                .widgetSurface()
                .padding(12.dp)
                // The whole face shuffles. A separate button would be smaller
                // than the thing it sits on, for one action.
                .clickable(actionRunCallback<ShuffleLikedAction>()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_widget_heart_filled),
                contentDescription = null,
                colorFilter = androidx.glance.ColorFilter.tint(WidgetTheme.onBackground),
                modifier = GlanceModifier.size(28.dp),
            )
            Spacer(GlanceModifier.size(8.dp))
            Text(
                text = stringOf(R.string.widget_liked_songs),
                style = WidgetTheme.title,
                maxLines = 1,
            )
            Text(
                text = androidx.glance.LocalContext.current.resources
                    .getQuantityString(R.plurals.widget_liked_count, count, count),
                style = WidgetTheme.metadata,
                maxLines = 1,
            )
        }
    }

    private companion object {
        val SQUARE = DpSize(140.dp, 110.dp)
    }
}

class LikedSongsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget get() = LikedSongsWidget()
}
