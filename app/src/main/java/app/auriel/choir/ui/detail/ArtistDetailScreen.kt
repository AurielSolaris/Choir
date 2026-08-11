// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.AlbumRow
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * One artist's albums — the AOSP `ArtistAlbumBrowser` drill-down.
 *
 * Albums rather than a flat track list, because that is the level at which the
 * next choice is usually made. Shuffle plays everything they appear on.
 */
@Composable
fun ArtistDetailScreen(
    artist: Artist?,
    albums: List<Album>,
    onBack: () -> Unit,
    onAlbumSelected: (Long) -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current

    if (artist == null) {
        CenteredMessage(stringResource(R.string.library_empty), modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(
            title = artist.name,
            subtitle = listOf(
                pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount),
                pluralStringResource(R.plurals.track_count, artist.trackCount, artist.trackCount),
            ).joinToString(MusicUtils.SEPARATOR),
            onBack = onBack,
            actions = {
                IconAction(
                    icon = ChoirIcons.Shuffle,
                    contentDescription = stringResource(R.string.cd_shuffle),
                    onClick = onShuffle,
                )
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumRow(album = album, onClick = { onAlbumSelected(album.id) })
                RowDivider()
            }
        }
    }
}
