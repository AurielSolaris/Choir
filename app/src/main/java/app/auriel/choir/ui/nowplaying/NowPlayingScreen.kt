// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.playback.PlaybackUiState
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.AlbumArt
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The full-screen player: Choir's port of `MediaPlaybackActivity`.
 *
 * Art on top, metadata under it, a scrubber, then one row of transport
 * controls — the iPod-ish stack PLAN.md phase 5 builds on.
 */
@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChoirColors.current
    val nowPlaying = state.nowPlaying

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChoirIcons.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = colors.onBackground,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .padding(12.dp)
                    .size(24.dp),
            )
            Text(
                text = stringResource(R.string.now_playing_title),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
            )
        }

        if (nowPlaying == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.now_playing_nothing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AlbumArt(
                artworkUri = nowPlaying.artworkUri,
                size = 320.dp,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(32.dp))

            Text(
                text = nowPlaying.title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = MusicUtils.makeSubtitle(nowPlaying.artist, nowPlaying.album),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        SeekBar(state = state, onSeek = onSeek)

        Spacer(Modifier.height(8.dp))

        TransportControls(
            state = state,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
        )

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Scrubbing is local until the user lets go: reporting every intermediate value
 * to the player makes the thumb fight the position ticker.
 */
@Composable
private fun SeekBar(state: PlaybackUiState, onSeek: (Long) -> Unit) {
    val colors = LocalChoirColors.current

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }

    val displayedMs = if (isScrubbing) scrubPosition.toLong() else state.positionMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
        Slider(
            value = displayedMs.toFloat(),
            onValueChange = {
                isScrubbing = true
                scrubPosition = it
            },
            onValueChangeFinished = {
                onSeek(scrubPosition.toLong())
                isScrubbing = false
            },
            // An unknown duration would give an empty range, which Slider rejects.
            valueRange = 0f..(state.durationMs.coerceAtLeast(1L)).toFloat(),
            enabled = state.durationMs > 0L,
            colors = SliderDefaults.colors(
                thumbColor = colors.onBackground,
                activeTrackColor = colors.onBackground,
                inactiveTrackColor = colors.divider,
                disabledThumbColor = colors.divider,
                disabledActiveTrackColor = colors.divider,
                disabledInactiveTrackColor = colors.divider,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = MusicUtils.makeTimeString(displayedMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            Text(
                text = MusicUtils.makeTimeString(state.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}

@Composable
private fun TransportControls(
    state: PlaybackUiState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mode toggles read as "on" through opacity, since there is no accent
        // colour to switch to.
        ControlButton(
            icon = ChoirIcons.Shuffle,
            contentDescription = stringResource(R.string.cd_shuffle),
            onClick = onToggleShuffle,
            size = 24.dp,
            enabled = true,
            dimmed = !state.shuffleEnabled,
        )
        ControlButton(
            icon = ChoirIcons.SkipPrevious,
            contentDescription = stringResource(R.string.cd_previous),
            onClick = onPrevious,
            size = 36.dp,
            enabled = true,
        )
        ControlButton(
            icon = if (state.isPlaying) ChoirIcons.Pause else ChoirIcons.Play,
            contentDescription = stringResource(
                if (state.isPlaying) R.string.cd_pause else R.string.cd_play,
            ),
            onClick = onPlayPause,
            size = 56.dp,
            enabled = true,
        )
        ControlButton(
            icon = ChoirIcons.SkipNext,
            contentDescription = stringResource(R.string.cd_next),
            onClick = onNext,
            size = 36.dp,
            enabled = state.hasNext,
        )
        ControlButton(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                ChoirIcons.RepeatOne
            } else {
                ChoirIcons.Repeat
            },
            contentDescription = stringResource(R.string.cd_repeat),
            onClick = onCycleRepeat,
            size = 24.dp,
            enabled = true,
            dimmed = state.repeatMode == Player.REPEAT_MODE_OFF,
        )
    }
}

@Composable
private fun ControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    dimmed: Boolean = false,
) {
    val colors = LocalChoirColors.current

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = colors.onBackground,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp)
            .size(size)
            .alpha(if (!enabled) 0.25f else if (dimmed) 0.35f else 1f),
    )
}
