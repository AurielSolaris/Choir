// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.Playlist
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The list idiom, used by every screen: serif title, light sans subtitle, and
 * an optional right-hand annotation. Dividers are hairlines inset to the text,
 * standing in for the pencil rules PLAN.md phase 5 will draw properly.
 */
@Composable
fun ChoirRow(
    title: String,
    subtitle: String?,
    trailing: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
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
        }

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

@Composable
fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = LocalChoirColors.current.divider,
        modifier = Modifier.padding(start = 16.dp),
    )
}

@Composable
fun TrackRow(track: Track, onClick: () -> Unit) {
    ChoirRow(
        title = track.title,
        subtitle = MusicUtils.makeSubtitle(track.artist, track.album),
        trailing = MusicUtils.makeTimeString(track.durationMs),
        onClick = onClick,
    )
}

/** A track inside an album or playlist, where the position matters more. */
@Composable
fun OrderedTrackRow(position: Int, track: Track, onClick: () -> Unit) {
    val colors = LocalChoirColors.current

    ChoirRow(
        title = track.title,
        subtitle = track.artist,
        trailing = MusicUtils.makeTimeString(track.durationMs),
        onClick = onClick,
        leading = {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.width(24.dp),
            )
        },
    )
}

@Composable
fun AlbumRow(album: Album, onClick: () -> Unit) {
    ChoirRow(
        title = album.title,
        subtitle = album.artist,
        trailing = album.trackCount.toString(),
        onClick = onClick,
        leading = { AlbumArt(artworkUri = album.artworkUri, size = 48.dp, modifier = Modifier.size(48.dp)) },
    )
}

@Composable
fun ArtistRow(artist: Artist, albumsLabel: String, onClick: () -> Unit) {
    ChoirRow(
        title = artist.name,
        subtitle = albumsLabel,
        trailing = artist.trackCount.toString(),
        onClick = onClick,
    )
}

@Composable
fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    ChoirRow(
        title = playlist.name,
        subtitle = null,
        trailing = null,
        onClick = onClick,
    )
}
