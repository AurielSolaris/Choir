// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the launcher is told about Choir's widgets.
 *
 * None of this is reachable from a JVM test: an `appwidget-provider` is XML the
 * platform parses on install, and the object under test is what the platform
 * made of it. A typo in a resource reference, a receiver that was renamed
 * without its manifest entry, an `updatePeriodMillis` quietly reintroduced —
 * each of those installs cleanly and only shows up as a widget that will not
 * appear, or a phone that wakes all night.
 */
@RunWith(AndroidJUnit4::class)
class WidgetProviderInfoTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private val providers: List<AppWidgetProviderInfo>
        get() = AppWidgetManager.getInstance(context)
            .installedProviders
            .filter { it.provider.packageName == context.packageName }

    private fun provider(receiver: String): AppWidgetProviderInfo =
        providers.first { it.provider.className.endsWith(receiver) }

    @Test
    fun all_four_widgets_are_installed() {
        val names = providers.map { it.provider.className.substringAfterLast('.') }.sorted()

        assertEquals(
            listOf(
                "CompactControlsWidgetReceiver",
                "LikedSongsWidgetReceiver",
                "LyricLineWidgetReceiver",
                "NowPlayingWidgetReceiver",
            ),
            names,
        )
    }

    /**
     * The claim the whole widget design rests on. A non-zero period is the
     * platform waking the device to redraw something that has not changed, and
     * it is one attribute away at all times.
     */
    @Test
    fun nothing_polls() {
        providers.forEach { info ->
            assertEquals(
                "${info.provider.className} has an update period",
                0,
                info.updatePeriodMillis,
            )
        }
    }

    @Test
    fun every_widget_describes_itself_in_the_picker() {
        providers.forEach { info ->
            val description = info.loadDescription(context)
            assertNotNull("${info.provider.className} has no description", description)
            assertTrue(
                "${info.provider.className} has an empty description",
                !description.isNullOrBlank(),
            )
        }
    }

    /**
     * A preview the launcher can actually draw, rather than the app icon.
     *
     * `previewLayout` is honoured from API 31; below that the picker falls back
     * to `previewImage`, so both have to be set and this only asserts the one
     * the device will use.
     */
    @Test
    fun every_widget_previews_as_itself() {
        providers.forEach { info ->
            assertNotEquals(
                "${info.provider.className} has no preview image",
                0,
                info.previewImage,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertNotEquals(
                    "${info.provider.className} has no preview layout",
                    0,
                    info.previewLayout,
                )
            }
        }
    }

    @Test
    fun the_two_row_widgets_are_two_cells_tall_and_the_strips_are_one() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        assertEquals(2, provider("NowPlayingWidgetReceiver").targetCellHeight)
        assertEquals(2, provider("LikedSongsWidgetReceiver").targetCellHeight)
        assertEquals(1, provider("CompactControlsWidgetReceiver").targetCellHeight)
        assertEquals(1, provider("LyricLineWidgetReceiver").targetCellHeight)
    }

    /**
     * Now Playing composes a different arrangement at 4×2 than at 2×2, which is
     * only reachable if the launcher is told it may be stretched that far.
     */
    @Test
    fun now_playing_may_be_resized_to_its_wide_shape() {
        val info = provider("NowPlayingWidgetReceiver")

        assertTrue(
            "Now Playing cannot be resized horizontally",
            info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
        )
    }
}
