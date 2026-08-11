// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The icons Choir draws.
 *
 * Defined in-tree from path data rather than pulled from `material-icons`: the
 * dependency is large, and PLAN.md phase 5 replaces these with a hand-sketched
 * set anyway. Swapping the path strings below is the whole job when it does.
 *
 * Fill is black because [androidx.compose.material3.Icon] tints on draw.
 */
object ChoirIcons {

    val Play: ImageVector by lazy { icon("Play", "M8,5v14l11,-7z") }

    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }

    val SkipNext: ImageVector by lazy {
        icon("SkipNext", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    }

    val SkipPrevious: ImageVector by lazy {
        icon("SkipPrevious", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    }

    val Shuffle: ImageVector by lazy {
        icon(
            "Shuffle",
            "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 " +
                "5.41,20 17.96,7.46 20,9.5V4h-5.5zm0.33,9.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5" +
                "l-2.04,2.04 -3.13,-3.13z",
        )
    }

    val Repeat: ImageVector by lazy {
        icon(
            "Repeat",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zm10,10H7v-3l-4,4 4,4v-3h12v-6h-2v4z",
        )
    }

    val RepeatOne: ImageVector by lazy {
        icon(
            "RepeatOne",
            "M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zm10,10H7v-3l-4,4 4,4v-3h12v-6h-2v4zm-4,-2V9h-1" +
                "l-2,1v1h1.5v4H13z",
        )
    }

    val ArrowBack: ImageVector by lazy {
        icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    }

    val Search: ImageVector by lazy {
        icon(
            "Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3" +
                "S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99" +
                "L20.49,19l-4.99,-5zm-6,0C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 " +
                "11.99,14 9.5,14z",
        )
    }

    val Close: ImageVector by lazy {
        icon(
            "Close",
            "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 " +
                "17.59,19 19,17.59 13.41,12z",
        )
    }

    /** Stands in for missing album art, and marks the empty library. */
    val MusicNote: ImageVector by lazy {
        icon(
            "MusicNote",
            "M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 " +
                "4,-1.79 4,-4V7h4V3h-6z",
        )
    }

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = SolidColor(Color.Black),
        ).build()
}
