// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.playback.PlaybackUiState
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors

/** Height of the bar, so the list underneath can reserve room for it. */
val MiniPlayerHeight = 64.dp

/**
 * The persistent bar across the bottom of the library: what is playing, a
 * play/pause key, and a hairline progress rule. Tapping it opens the full
 * player.
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onClick: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChoirColors.current
    val nowPlaying = state.nowPlaying ?: return
    val openLabel = stringResource(R.string.cd_open_now_playing)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onClick)
            .semantics { contentDescription = openLabel },
    ) {
        HorizontalDivider(thickness = 1.dp, color = colors.divider)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlbumArt(
                artworkUri = nowPlaying.artworkUri,
                size = 44.dp,
                modifier = Modifier.size(44.dp),
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = nowPlaying.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = if (state.isPlaying) ChoirIcons.Pause else ChoirIcons.Play,
                contentDescription = stringResource(
                    if (state.isPlaying) R.string.cd_pause else R.string.cd_play,
                ),
                tint = colors.onSurface,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onPlayPause)
                    .padding(10.dp)
                    .size(28.dp),
            )
        }

        // Progress as a rule rather than a bar: one pixel of ink, no chrome.
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(state.progress)
                    .height(1.dp)
                    .background(colors.onSurface),
            )
        }

        // The bar's surface runs to the very bottom of the window, so the list
        // scrolling underneath never shows through beside the gesture bar.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
