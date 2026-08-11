// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Choir's palette: ink, paper, and the greys between them. No accent colour, by
 * design (PLAN.md section C) — hierarchy comes from [ChoirTypography] and from
 * space.
 */
data class ChoirColors(
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val onSurface: Color,
    val muted: Color,
    val divider: Color,
)

val DarkChoirColors = ChoirColors(
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    muted = Color(0xFF888888),
    divider = Color(0xFF333333),
)

val LightChoirColors = ChoirColors(
    background = Color(0xFFF6F2E9),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    muted = Color(0xFF6E6A63),
    divider = Color(0xFFDDD7CB),
)

val LocalChoirColors = staticCompositionLocalOf { DarkChoirColors }

@Composable
fun ChoirTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkChoirColors else LightChoirColors

    // Material components read the M3 scheme, so the same two colours are
    // projected onto every role rather than letting a stray purple through.
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.onBackground,
            onPrimary = colors.background,
            secondary = colors.muted,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.divider,
            outlineVariant = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.onBackground,
            onPrimary = colors.background,
            secondary = colors.muted,
            background = colors.background,
            onBackground = colors.onBackground,
            surface = colors.surface,
            onSurface = colors.onSurface,
            surfaceVariant = colors.surface,
            onSurfaceVariant = colors.muted,
            outline = colors.divider,
            outlineVariant = colors.divider,
        )
    }

    CompositionLocalProvider(LocalChoirColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ChoirTypography,
            content = content,
        )
    }
}
