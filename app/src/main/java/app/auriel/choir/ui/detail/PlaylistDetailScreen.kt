// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.data.model.Playlist
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.OrderedTrackRow
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * One playlist, in the order it stores — the AOSP `PlaylistBrowser`
 * drill-down. Read-only; editing arrives with Room in PLAN.md phase 4.
 */
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist?,
    tracks: List<Track>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(
            title = playlist?.name.orEmpty(),
            subtitle = if (isLoading) {
                null
            } else {
                pluralStringResource(R.plurals.track_count, tracks.size, tracks.size)
            },
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

        when {
            isLoading -> CenteredMessage(stringResource(R.string.library_loading))
            tracks.isEmpty() -> CenteredMessage(stringResource(R.string.playlist_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
            ) {
                // Playlists may hold the same track twice, so position — not id
                // — is what makes a row unique here.
                itemsIndexed(tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    OrderedTrackRow(
                        position = index + 1,
                        track = track,
                        onClick = { onPlay(index) },
                    )
                    RowDivider()
                }
            }
        }
    }
}
