// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.AlbumArt
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.LikeState
import app.auriel.choir.ui.components.OrderedTrackRow
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * One album, in running order — the AOSP `AlbumBrowser` drill-down.
 *
 * Tapping a track queues the whole album from that point, so skipping forward
 * stays inside the record rather than wandering into the rest of the library.
 */
@Composable
fun AlbumDetailScreen(
    album: Album?,
    tracks: List<Track>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    likes: LikeState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current

    if (album == null) {
        // The album vanished under us — a delete or a rescan while it was open.
        CenteredMessage(stringResource(R.string.library_empty), modifier)
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(
            title = album.title,
            subtitle = albumSubtitle(album),
            onBack = onBack,
            actions = {
                if (tracks.isNotEmpty()) {
                    IconAction(
                        icon = ChoirIcons.Shuffle,
                        contentDescription = stringResource(R.string.cd_shuffle),
                        onClick = onShuffle,
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp),
                ) {
                    AlbumArt(
                        artworkUri = album.artworkUri,
                        size = 160.dp,
                        modifier = Modifier.size(160.dp),
                    )
                }
            }

            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                OrderedTrackRow(
                    position = if (track.trackNumber > 0) track.trackNumber else index + 1,
                    track = track,
                    onClick = { onPlay(index) },
                    likes = likes,
                )
                RowDivider()
            }
        }
    }
}

@Composable
private fun albumSubtitle(album: Album): String {
    val tracks = pluralStringResource(R.plurals.track_count, album.trackCount, album.trackCount)
    val parts = buildList {
        add(album.artist)
        add(tracks)
        if (album.year > 0) add(album.year.toString())
    }
    return parts.joinToString(MusicUtils.SEPARATOR)
}
