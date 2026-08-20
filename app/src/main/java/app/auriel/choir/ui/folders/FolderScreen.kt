// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.folders

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.data.model.MusicFolder
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.FolderRow
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.LikeState
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.TrackRow
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * One folder: what is inside it, and what is below it.
 *
 * Subfolders first, then tracks — the order every file manager has used since
 * there were file managers, and the one thing a listener does not have to be
 * taught. Drilling in pushes another copy of this screen, which is the same
 * hierarchical idiom the album and artist browsers use.
 */
@Composable
fun FolderScreen(
    folder: MusicFolder?,
    onBack: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onTrackLongPress: (Int) -> Unit,
    likes: LikeState,
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
            title = folder?.name.orEmpty(),
            subtitle = folder?.let {
                pluralStringResource(R.plurals.track_count, it.trackCount, it.trackCount)
            },
            onBack = onBack,
            actions = {
                if (folder != null && folder.trackCount > 0) {
                    IconAction(
                        icon = ChoirIcons.Shuffle,
                        contentDescription = stringResource(R.string.cd_shuffle),
                        onClick = onShuffle,
                    )
                }
            },
        )

        when {
            // The tree is rebuilt on every rescan, so a folder can vanish from
            // under an open screen — a card pulled out, a directory deleted.
            folder == null -> CenteredMessage(stringResource(R.string.folders_empty))

            folder.isEmpty -> CenteredMessage(stringResource(R.string.folders_empty))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
            ) {
                folderContents(
                    folder = folder,
                    onOpenFolder = onOpenFolder,
                    onPlay = onPlay,
                    onTrackLongPress = onTrackLongPress,
                    likes = likes,
                )
            }
        }
    }
}

/**
 * The rows one folder contributes, shared with the Folders tab so that the top
 * of the tree and every level below it read identically.
 */
fun LazyListScope.folderContents(
    folder: MusicFolder,
    onOpenFolder: (String) -> Unit,
    onPlay: (Int) -> Unit,
    onTrackLongPress: (Int) -> Unit,
    likes: LikeState,
) {
    items(folder.folders, key = { it.path }) { child ->
        FolderRow(
            name = child.name,
            countLabel = pluralStringResource(
                R.plurals.track_count,
                child.trackCount,
                child.trackCount,
            ),
            onClick = { onOpenFolder(child.path) },
        )
        RowDivider()
    }

    itemsIndexed(folder.tracks, key = { _, track -> track.id }) { index, track ->
        TrackRow(
            track = track,
            onClick = { onPlay(index) },
            onLongClick = { onTrackLongPress(index) },
            likes = likes,
        )
        RowDivider()
    }
}
