// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.auriel.choir.R
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.playlist.PlaylistTrack
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.LikeState
import app.auriel.choir.ui.components.OrderedTrackRow
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.rememberReorderState
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * One of Choir's own playlists, editable.
 *
 * This is where the AOSP `PlaylistBrowser` drill-down finally does what it
 * always should have: rename, delete, reorder by dragging, and remove a track
 * without the platform's blessing. None of it touches MediaStore, whose
 * playlist tables have been closed to other apps since Android 11.
 */
@Composable
fun PlaylistScreen(
    name: String,
    entries: List<PlaylistTrack>,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackLongPress: (Track, Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    likes: LikeState,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current

    // The order the finger is drawing, which runs ahead of the database until
    // the drag ends. Reset whenever the stored order changes underneath it.
    var order by remember(entries) { mutableStateOf(entries) }

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        onMove = { from, to ->
            order = order.toMutableList().apply { add(to, removeAt(from)) }
        },
        onSettle = { onReorder(order.map(PlaylistTrack::memberId)) },
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(
            title = name,
            subtitle = playlistSubtitle(order.size),
            onBack = onBack,
            actions = {
                if (order.isNotEmpty()) {
                    IconAction(
                        icon = ChoirIcons.Shuffle,
                        contentDescription = stringResource(R.string.cd_shuffle),
                        onClick = onShuffle,
                    )
                    IconAction(
                        icon = ChoirIcons.Export,
                        contentDescription = stringResource(R.string.cd_export_playlist),
                        onClick = onExport,
                    )
                }
                IconAction(
                    icon = ChoirIcons.Edit,
                    contentDescription = stringResource(R.string.cd_rename_playlist),
                    onClick = onRename,
                )
                IconAction(
                    icon = ChoirIcons.Delete,
                    contentDescription = stringResource(R.string.cd_delete_playlist),
                    onClick = onDelete,
                )
            },
        )

        if (order.isEmpty()) {
            CenteredMessage(stringResource(R.string.playlist_empty))
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            // Nothing else may live in this list: the reorder logic reads item
            // indices as playlist positions.
            itemsIndexed(order, key = { _, entry -> entry.memberId }) { index, entry ->
                val isDragging = reorder.draggingIndex == index

                OrderedTrackRow(
                    position = index + 1,
                    track = entry.track,
                    onClick = { onPlay(index) },
                    modifier = Modifier
                        // The lifted row has to draw over its neighbours.
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragging) reorder.draggingOffset else 0f
                        }
                        .background(if (isDragging) colors.surface else colors.background),
                    onLongClick = { onTrackLongPress(entry.track, entry.memberId) },
                    likes = likes,
                    leading = {
                        Icon(
                            imageVector = ChoirIcons.DragHandle,
                            contentDescription = stringResource(R.string.cd_reorder),
                            tint = colors.muted,
                            modifier = Modifier
                                .size(20.dp)
                                .alpha(if (isDragging) 1f else 0.6f)
                                // Keyed on the row, not its index — see
                                // ReorderState.handleModifier.
                                .then(
                                    reorder.handleModifier(entry.memberId) {
                                        order.indexOfFirst { it.memberId == entry.memberId }
                                    },
                                )
                                .padding(end = 4.dp),
                        )
                    },
                )
                RowDivider()
            }
        }
    }
}

@Composable
private fun playlistSubtitle(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.track_count, count, count)
