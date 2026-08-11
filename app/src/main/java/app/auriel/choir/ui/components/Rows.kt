// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalFoundationApi::class)

package app.auriel.choir.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.Playlist
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * Which tracks a list should draw a heart against.
 *
 * Only the marks — what a long press *does* is the calling screen's business,
 * because a row inside a playlist can offer to remove itself and a row in the
 * library cannot.
 */
@Immutable
data class LikeState(val likedIds: Set<Long> = emptySet()) {
    operator fun contains(track: Track): Boolean = track.id in likedIds
}

/**
 * The list idiom, used by every screen: serif title, light sans subtitle, and
 * an optional right-hand annotation. Dividers are hairlines inset to the text,
 * standing in for the pencil rules the restyle will draw properly.
 */
@Composable
fun ChoirRow(
    title: String,
    subtitle: String?,
    trailing: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    accessory: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
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

        if (accessory != null) {
            Spacer(Modifier.width(12.dp))
            accessory()
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

/**
 * The heart a liked track carries in a list.
 *
 * Drawn only when the track *is* liked. An outline on all several hundred rows
 * would be exactly the kind of persistent chrome Choir's restraint rules out,
 * and the toggle itself lives on a long press and on Now Playing,
 * where there is room to show it properly.
 */
@Composable
private fun LikeMark() {
    Icon(
        imageVector = ChoirIcons.HeartFilled,
        contentDescription = stringResource(R.string.cd_liked),
        tint = LocalChoirColors.current.onBackground,
        modifier = Modifier.size(13.dp),
    )
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    likes: LikeState = LikeState(),
) {
    ChoirRow(
        title = track.title,
        subtitle = MusicUtils.makeSubtitle(track.artist, track.album),
        trailing = MusicUtils.makeLengthString(track.durationMs),
        onClick = onClick,
        onLongClick = onLongClick,
        accessory = if (track in likes) {
            { LikeMark() }
        } else {
            null
        },
    )
}

/** A track inside an album or playlist, where the position matters more. */
@Composable
fun OrderedTrackRow(
    position: Int,
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    likes: LikeState = LikeState(),
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = LocalChoirColors.current

    ChoirRow(
        title = track.title,
        subtitle = track.artist,
        trailing = MusicUtils.makeLengthString(track.durationMs),
        onClick = onClick,
        modifier = modifier,
        onLongClick = onLongClick,
        leading = leading ?: {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
                modifier = Modifier.width(24.dp),
            )
        },
        accessory = if (track in likes) {
            { LikeMark() }
        } else {
            null
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
fun PlaylistRow(name: String, countLabel: String, onClick: () -> Unit) {
    ChoirRow(
        title = name,
        subtitle = countLabel,
        trailing = null,
        onClick = onClick,
    )
}

/**
 * Liked Songs, pinned to the top of the Playlists tab.
 *
 * Not a [Playlist]: it has no MediaStore id, it is never empty of meaning even
 * when it holds nothing, and it must not be confused with the rows below it.
 */
@Composable
fun LikedSongsRow(countLabel: String, onClick: () -> Unit) {
    val colors = LocalChoirColors.current

    ChoirRow(
        title = stringResource(R.string.liked_songs),
        subtitle = countLabel,
        trailing = null,
        onClick = onClick,
        leading = {
            Icon(
                imageVector = ChoirIcons.HeartFilled,
                contentDescription = null,
                tint = colors.onBackground,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}
