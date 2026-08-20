// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import androidx.compose.ui.unit.sp
// The two-colour factory, which is not in the same package as the one-colour
// one: androidx.glance.unit.ColorProvider takes a single colour and cannot
// express a widget that has to be legible on both a light and a dark launcher.
import androidx.glance.color.ColorProvider
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import app.auriel.choir.ui.theme.DarkChoirColors
import app.auriel.choir.ui.theme.LightChoirColors

/**
 * Choir's palette and type, restated in the terms Glance understands.
 *
 * The values come from `ui/theme`, which stays the one place they are decided —
 * but none of the machinery around them carries over. A widget is `RemoteViews`
 * drawn by the launcher's process: there is no `CompositionLocal` reaching
 * across that boundary, no `MaterialTheme`, and no access to the app's bundled
 * fonts, because the launcher cannot load a typeface out of another
 * application's assets.
 *
 * So EB Garamond and Inter are not here. What is here is the *rest* of the
 * hierarchy — the serif/sans split replaced by weight and letter-spacing, which
 * are the tools that survive the crossing. A widget should read as Choir's,
 * quietly, without pretending to a fidelity the platform will not give it.
 *
 * Light and dark are chosen per colour rather than per theme, because a widget
 * does not follow the app's setting: it follows whatever the launcher is doing,
 * and it may be asked to draw both at once for a preview.
 */
internal object WidgetTheme {

    val background = ColorProvider(
        day = LightChoirColors.background,
        night = DarkChoirColors.background,
    )

    val onBackground = ColorProvider(
        day = LightChoirColors.onBackground,
        night = DarkChoirColors.onBackground,
    )

    val muted = ColorProvider(day = LightChoirColors.muted, night = DarkChoirColors.muted)

    val divider = ColorProvider(day = LightChoirColors.divider, night = DarkChoirColors.divider)

    /**
     * A track or album name — the content the library is *about*.
     *
     * Serif in the app; here it keeps the role by being the only thing set at
     * full weight and full opacity, with everything around it stepped back.
     */
    val title = TextStyle(
        color = onBackground,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Serif,
    )

    /** The same, at the size a 4×1 strip can afford. */
    val titleCompact = title.copy(fontSize = 13.sp)

    /**
     * An artist name, a count, a state — annotation rather than content.
     *
     * Light and grey, so it recedes behind the title without a second colour
     * being introduced to do it.
     */
    val metadata = TextStyle(
        color = muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
    )

    /**
     * A lyric, which is neither chrome nor a title.
     *
     * Serif, because the words are the content; italic is not available to
     * Glance's `TextStyle` on every host, so the distinction is carried by size
     * and by the line standing alone.
     */
    val lyric = TextStyle(
        color = onBackground,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Serif,
    )

    /** The prompt an empty widget shows, which should not shout. */
    val invitation = metadata.copy(fontSize = 13.sp)
}
