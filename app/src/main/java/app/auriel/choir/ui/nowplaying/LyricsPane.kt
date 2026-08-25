// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.auriel.choir.data.lyrics.LyricLine
import app.auriel.choir.data.lyrics.Lyrics
import app.auriel.choir.ui.theme.LocalChoirColors
import app.auriel.choir.ui.theme.lyric

/**
 * The words, scrolling themselves.
 *
 * Every line is set identically — the same face, size and weight whether or not
 * it is the one being sung. Timed lyrics mark the current line in ink against
 * grey and keep it about a third of the way down the pane, which is where the
 * eye expects to read from; tapping a line seeks to it. Where the file carries
 * word timings the ink moves through the line as it is sung. Untimed lyrics are
 * the same list with nothing marked and no movement.
 *
 * Manual scrolling wins: dragging suspends the automatic follow for a few
 * seconds so that reading ahead does not turn into a fight with the player.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Run on the frame clock rather than on the reported position, which only
    // arrives four times a second — a highlight driven straight off it is up to
    // 250 ms late and moves in visible steps.
    val position = rememberLivePosition(positionMs, isPlaying)

    // Derived, so this only reports a change when the *line* changes — which
    // is every few seconds, not every tick.
    val activeIndex by remember(lyrics) {
        derivedStateOf { lyrics.indexAt(position.value) }
    }

    var draggedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        // Only a real drag counts — watching isScrollInProgress would also see
        // this pane's own animated scrolls and permanently suppress itself.
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) draggedAt = nowMs()
        }
    }

    LaunchedEffect(activeIndex, lyrics) {
        if (!lyrics.isSynced || activeIndex < 0) return@LaunchedEffect
        if (nowMs() - draggedAt < MANUAL_SCROLL_GRACE_MS) return@LaunchedEffect

        val viewport = listState.layoutInfo.let { it.viewportEndOffset - it.viewportStartOffset }
        listState.animateScrollToItem(activeIndex, -viewport / 3)
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        // Enough room above and below that the first and last lines can still
        // reach the reading position.
        contentPadding = PaddingValues(vertical = 96.dp),
    ) {
        itemsIndexed(lyrics.lines, key = { index, _ -> index }) { index, line ->
            LyricRow(
                line = line,
                isActive = lyrics.isSynced && index == activeIndex,
                isSynced = lyrics.isSynced,
                position = position,
                onSeek = onSeek,
            )
        }
    }
}

/**
 * One line.
 *
 * A separate composable on purpose: it gives each line its own recomposition
 * scope, so the active line can read the playback position without dragging
 * every other line on screen along with it.
 */
@Composable
private fun LyricRow(
    line: LyricLine,
    isActive: Boolean,
    isSynced: Boolean,
    position: State<Long>,
    onSeek: (Long) -> Unit,
) {
    val colors = LocalChoirColors.current

    val text = if (isActive && line.words.isNotEmpty()) {
        // Reading `position` here, inside the active line only, is what keeps
        // the four-times-a-second invalidation local to this row.
        sungText(line, position.value, colors.onBackground, colors.muted)
    } else {
        AnnotatedString(line.text.ifBlank { "·" })
    }

    Text(
        text = text,
        // One style for every line, sung or not. The face and the size do not
        // change as the song moves through the pane — see ChoirTypography.lyric
        // — so the column never reflows and the whole lyric reads as one
        // document. The current line is marked in ink against grey, and by
        // where the pane holds it.
        style = MaterialTheme.typography.lyric,
        color = when {
            isActive -> colors.onBackground
            isSynced -> colors.muted
            else -> colors.onBackground
        },
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isSynced && line.timeMs != LyricLine.NO_TIME) {
                    Modifier.clickable { onSeek(line.timeMs) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * The active line, split into what has been sung and what has not.
 *
 * Two spans rather than one per word: the words are contiguous ranges over the
 * same string, so the boundary is a single index and there is no reason to
 * build a span for each one.
 */
private fun sungText(line: LyricLine, positionMs: Long, sung: Color, unsung: Color): AnnotatedString {
    val boundary = line.sungUpTo(positionMs).coerceIn(0, line.text.length)

    return buildAnnotatedString {
        withStyle(SpanStyle(color = sung)) { append(line.text, 0, boundary) }
        if (boundary < line.text.length) {
            withStyle(SpanStyle(color = unsung)) { append(line.text, boundary, line.text.length) }
        }
    }
}

/**
 * The playback position, advanced every frame instead of four times a second.
 *
 * [PlaybackConnection] ticks the position 250 ms apart, which is ample for a
 * seek bar and hopeless for a word highlight: it can be a quarter of a second
 * behind the singing, and it arrives in steps large enough to see. Between
 * ticks the position is not unknown, though — it advances in real time — so it
 * is extrapolated from the last reported value against the frame clock, and
 * re-anchored whenever a real one arrives.
 *
 * No extra work crosses to the player: this is arithmetic on a value already
 * being delivered.
 */
@Composable
private fun rememberLivePosition(reportedMs: Long, isPlaying: Boolean): State<Long> {
    val reported = rememberUpdatedState(reportedMs)
    val playing = rememberUpdatedState(isPlaying)
    val live = remember { mutableLongStateOf(reportedMs) }

    LaunchedEffect(Unit) {
        var anchorMs = reported.value
        var anchorFrameMs = -1L
        var lastReported = reported.value

        while (true) {
            withFrameMillis { frameMs ->
                // Re-anchor on the first frame, and whenever the player says
                // something new — which includes after a seek, so the highlight
                // lands with the audio rather than drifting back to it.
                if (anchorFrameMs < 0 || reported.value != lastReported) {
                    lastReported = reported.value
                    anchorMs = reported.value
                    anchorFrameMs = frameMs
                }
                live.longValue = if (playing.value) {
                    anchorMs + (frameMs - anchorFrameMs)
                } else {
                    anchorMs
                }
            }
        }
    }
    return live
}

private fun nowMs(): Long = android.os.SystemClock.uptimeMillis()

/** Long enough to read a verse ahead, short enough not to feel stuck. */
private const val MANUAL_SCROLL_GRACE_MS = 6_000L
