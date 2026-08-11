// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.auriel.choir.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.playlist.PlaylistSummary
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * What a long press on a track offers.
 *
 * A sheet rather than a direct toggle: liking used to happen on long press, but
 * once there is more than one thing a row can do, an invisible gesture that
 * silently picks one of them is worse than a list that says what it will do.
 */
@Composable
fun TrackActionsSheet(
    track: Track,
    isLiked: Boolean,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = LocalChoirColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetHeading(
                title = track.title,
                subtitle = MusicUtils.makeSubtitle(track.artist, track.album),
            )

            SheetAction(
                icon = if (isLiked) ChoirIcons.HeartFilled else ChoirIcons.Heart,
                label = stringResource(if (isLiked) R.string.action_unlike else R.string.action_like),
                onClick = onLike,
            )
            SheetAction(
                icon = ChoirIcons.PlaylistAdd,
                label = stringResource(R.string.action_add_to_playlist),
                onClick = onAddToPlaylist,
            )
            if (onRemoveFromPlaylist != null) {
                SheetAction(
                    icon = ChoirIcons.Close,
                    label = stringResource(R.string.action_remove_from_playlist),
                    onClick = onRemoveFromPlaylist,
                )
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

/** Picks a playlist to add to, or makes one on the spot. */
@Composable
fun AddToPlaylistSheet(
    playlists: List<PlaylistSummary>,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChoirColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SheetHeading(title = stringResource(R.string.action_add_to_playlist), subtitle = null)

            SheetAction(
                icon = ChoirIcons.Add,
                label = stringResource(R.string.playlist_new),
                onClick = onCreate,
            )

            Column(
                // A long list of playlists must not push the sheet past the
                // top of the screen.
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (playlist in playlists) {
                    SheetAction(
                        icon = null,
                        label = playlist.name,
                        trailing = pluralStringResource(
                            R.plurals.track_count,
                            playlist.trackCount,
                            playlist.trackCount,
                        ),
                        onClick = { onSelect(playlist.id) },
                    )
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun SheetHeading(title: String, subtitle: String?) {
    val colors = LocalChoirColors.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The playlist rows have no icon, so the label still lines up with the
        // actions above them.
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Spacer(Modifier.size(20.dp))
        }
        Spacer(Modifier.width(20.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}
