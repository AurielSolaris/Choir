// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

// Variable-axis fonts are still behind an opt-in. The shape of the call is
// stable enough to bet on; if it changes it changes in one file.
@file:OptIn(ExperimentalTextApi::class)

package app.auriel.choir.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.auriel.choir.R

/**
 * Type is Choir's hierarchy. There is no accent colour to lean on (PLAN.md
 * section C), so weight, size, letter-spacing and the serif/sans split do all
 * the work:
 *
 *  - **EB Garamond** for content the library is *about* — track, album and
 *    artist names. An old-style serif: warm, bookish, and the closest thing to
 *    ink on paper that a screen manages.
 *  - **Inter** for chrome — labels, counts, timestamps. Set light and tracked
 *    out so it recedes behind the content and reads as annotation.
 *
 * Both are bundled as single variable fonts, so every weight below comes out of
 * one file per family; their OFL licences ship in `assets/licenses`.
 */
private val EbGaramond = FontFamily(
    Font(
        R.font.eb_garamond_variable,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.eb_garamond_variable,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.eb_garamond_variable,
        FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
)

private val Inter = FontFamily(
    Font(
        R.font.inter_variable,
        FontWeight.ExtraLight,
        variationSettings = FontVariation.Settings(FontVariation.weight(200)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Light,
        variationSettings = FontVariation.Settings(FontVariation.weight(300)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.inter_variable,
        FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
)

/** Digits of even width, so a ticking timestamp does not jitter. */
private const val TABULAR_FIGURES = "tnum"

val ChoirTypography = Typography(
    // Screen titles. Garamond is small on the body, so it is set large.
    headlineMedium = TextStyle(
        fontFamily = EbGaramond,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.2).sp,
    ),
    // Now-playing track title.
    titleLarge = TextStyle(
        fontFamily = EbGaramond,
        fontWeight = FontWeight.Medium,
        fontSize = 25.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    // Track title in a list row.
    titleMedium = TextStyle(
        fontFamily = EbGaramond,
        fontWeight = FontWeight.Normal,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Light,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Light,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp,
    ),
    // Section labels, tab names and counts: small, light, widely tracked. Set
    // in caps at the call site, where the extra tracking earns its keep.
    labelMedium = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Light,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.8.sp,
    ),
    // The same label, called out — an active tab against its inactive siblings.
    labelLarge = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.8.sp,
    ),
    // Timestamps.
    labelSmall = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.ExtraLight,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = TABULAR_FIGURES,
    ),
)
