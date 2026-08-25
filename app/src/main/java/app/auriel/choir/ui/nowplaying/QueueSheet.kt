// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(ExperimentalMaterial3Api::class)

package app.auriel.choir.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.auriel.choir.R
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.playback.PlaybackUiState
import app.auriel.choir.playback.QueueItem
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The queue, as a popup over the player.
 *
 * The list is in **play order**, which is the only order worth showing: with
 * shuffle on, the order the tracks were added in is not the order they will be
 * heard in, and a queue view that shows the former is telling the user about
 * bookkeeping rather than about music. [PlaybackUiState.queue] arrives already
 * walked into that order — see `PlaybackConnection.queueOf`.
 *
 * Shuffle and repeat sit in the sheet's own header rather than only on the
 * player behind it. They are what the list *means* — whether it will be
 * scrambled, whether it stops at the bottom, whether it never leaves this track
 * — and having to close the queue to change how the queue behaves is a strange
 * thing to ask.
 *
 * Tracks already played stay in the list, dimmed. They are how you get back to
 * something you just heard, which is most of the reason to open a queue at all.
 */
@Composable
fun QueueSheet(
    state: PlaybackUiState,
    onPlayQueueItem: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChoirColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        QueueHeading(
            state = state,
            onToggleShuffle = onToggleShuffle,
            onCycleRepeat = onCycleRepeat,
        )

        HorizontalDivider(thickness = 1.dp, color = colors.divider)

        if (state.queue.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                )
            }
        } else {
            QueueList(
                queue = state.queue,
                queueIndex = state.queueIndex,
                onPlayQueueItem = onPlayQueueItem,
            )
        }

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

/**
 * What the queue is, and the two switches that decide how it behaves.
 */
@Composable
private fun QueueHeading(
    state: PlaybackUiState,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp)
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.queue_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = queueSubtitle(state),
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // The same two toggles as the transport row, reading "on" through
        // opacity because there is no accent colour to switch to.
        ModeToggle(
            icon = ChoirIcons.Shuffle,
            contentDescription = stringResource(R.string.cd_shuffle),
            onClick = onToggleShuffle,
            dimmed = !state.shuffleEnabled,
        )
        ModeToggle(
            icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) {
                ChoirIcons.RepeatOne
            } else {
                ChoirIcons.Repeat
            },
            contentDescription = stringResource(R.string.cd_repeat),
            onClick = onCycleRepeat,
            dimmed = state.repeatMode == Player.REPEAT_MODE_OFF,
        )
    }
}

/**
 * "Shuffled · repeating all · 42 tracks left".
 *
 * Assembled rather than held as one string per combination: order and repeat
 * are independent, and there are six of them.
 */
@Composable
private fun queueSubtitle(state: PlaybackUiState): String {
    val separator = stringResource(R.string.queue_mode_separator)

    val order = stringResource(
        if (state.shuffleEnabled) R.string.queue_shuffled else R.string.queue_in_order,
    )
    val repeat = when (state.repeatMode) {
        Player.REPEAT_MODE_ALL -> stringResource(R.string.queue_repeat_all)
        Player.REPEAT_MODE_ONE -> stringResource(R.string.queue_repeat_one)
        else -> null
    }
    val remaining = state.remainingInQueue.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.queue_remaining, it, it)
    }

    return listOfNotNull(order, repeat, remaining).joinToString(separator)
}

@Composable
private fun ModeToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    dimmed: Boolean,
) {
    val colors = LocalChoirColors.current

    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = colors.onSurface,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .padding(12.dp)
            .size(22.dp)
            .alpha(if (dimmed) 0.35f else 1f),
    )
}

/**
 * The list itself, opened at the track that is playing.
 *
 * Scrolled rather than filtered: what has already played is still there, one
 * flick up, which is how you get back to the song you did not catch the name
 * of. The current track is put a little below the top so a couple of those are
 * visible without scrolling at all.
 */
@Composable
private fun QueueList(
    queue: List<QueueItem>,
    queueIndex: Int,
    onPlayQueueItem: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(queueIndex) {
        if (queueIndex >= 0) {
            listState.scrollToItem(index = (queueIndex - 1).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        // Capped rather than left to fill: a sheet that covers the player it
        // was opened from has stopped being a popup.
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
    ) {
        itemsIndexed(queue, key = { _, item -> item.mediaIndex }) { index, item ->
            QueueRow(
                item = item,
                position = index + 1,
                isCurrent = index == queueIndex,
                isPast = queueIndex >= 0 && index < queueIndex,
                onClick = { onPlayQueueItem(item.mediaIndex) },
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    position: Int,
    isCurrent: Boolean,
    isPast: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalChoirColors.current
    val playLabel = stringResource(R.string.cd_queue_play)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp)
            // Played tracks recede without disappearing. Opacity rather than a
            // second grey, so the row keeps the same ink as every other.
            .alpha(if (isPast) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (isCurrent) {
                Icon(
                    imageVector = ChoirIcons.PlayingMark,
                    contentDescription = stringResource(R.string.queue_now_playing),
                    tint = colors.onSurface,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(
                    text = position.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title.ifBlank { stringResource(R.string.unknown_track) },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.artist.isNotBlank()) {
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (item.durationMs > 0L) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = MusicUtils.makeLengthString(item.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
        }
    }
}
