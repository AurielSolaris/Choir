// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.data.AlbumArtLoader
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors
import org.koin.compose.koinInject

/**
 * Album art for [artworkUri], loaded off the main thread, with a hand-framed
 * placeholder when there is none.
 *
 * [size] is the layout size and also decides how far the bitmap is downsampled,
 * so a list thumbnail never decodes a full-resolution cover.
 */
@Composable
fun AlbumArt(
    artworkUri: Uri?,
    size: Dp,
    modifier: Modifier = Modifier,
    loader: AlbumArtLoader = koinInject(),
) {
    val sizePx = with(LocalDensity.current) { size.roundToPx() }
    var bitmap by remember(artworkUri, sizePx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(artworkUri, sizePx) {
        bitmap = loader.load(artworkUri, sizePx)
    }

    AlbumArtFrame(bitmap = bitmap, modifier = modifier)
}

@Composable
private fun AlbumArtFrame(bitmap: ImageBitmap?, modifier: Modifier = Modifier) {
    val colors = LocalChoirColors.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(colors.surface)
            .border(1.dp, colors.divider),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = ChoirIcons.MusicNote,
                contentDescription = null,
                tint = colors.divider,
                modifier = Modifier.fillMaxWidth(0.4f).aspectRatio(1f),
            )
        }
    }
}
