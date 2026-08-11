// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drag-to-reorder for a [androidx.compose.foundation.lazy.LazyColumn], in about
 * as few moving parts as the job allows.
 *
 * Written here rather than pulled in: the one thing Choir needs from a
 * reordering library is "which row is the finger over now", and
 * [LazyListState.layoutInfo] already answers that.
 *
 * The list being dragged has to be the *only* thing in its LazyColumn — no
 * headers, no footers — because item indices are used directly as positions.
 */
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onSettle: () -> Unit,
) {
    /** The index being dragged, or -1. */
    var draggingIndex by mutableIntStateOf(-1)
        private set

    /** How far the dragged row has been lifted from its slot, in pixels. */
    var draggingOffset by mutableFloatStateOf(0f)
        private set

    fun start(index: Int) {
        draggingIndex = index
        draggingOffset = 0f
    }

    fun drag(deltaY: Float) {
        if (draggingIndex < 0) return
        draggingOffset += deltaY

        val dragged = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == draggingIndex } ?: return
        val centre = dragged.offset + draggingOffset + dragged.size / 2f

        // The row the finger is over now, if it is not the one being dragged.
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { candidate ->
            candidate.index != draggingIndex &&
                centre >= candidate.offset &&
                centre <= candidate.offset + candidate.size
        } ?: return

        onMove(draggingIndex, target.index)
        // The row has swapped into the target's slot, so the visual offset has
        // to shrink by the distance it just travelled or it would jump twice.
        draggingOffset -= (target.offset - dragged.offset)
        draggingIndex = target.index
    }

    fun stop() {
        if (draggingIndex < 0) return
        draggingIndex = -1
        draggingOffset = 0f
        onSettle()
    }

    /**
     * Attach to the grip, not to the whole row: an immediate drag anywhere on a
     * row would fight the list's own scrolling, and the alternative — starting
     * on a long press — is already taken by the track actions sheet.
     *
     * @param key something stable for the *row*, never its index. `pointerInput`
     *   restarts whenever its key changes, and a dragged row's index changes on
     *   every swap — keying on it silently kills the gesture mid-drag, taking
     *   the callback that saves the new order with it.
     * @param currentIndex where the row is right now, read when the drag starts.
     */
    fun handleModifier(key: Any, currentIndex: () -> Int): Modifier = Modifier.pointerInput(key) {
        detectDragGestures(
            onDragStart = { start(currentIndex()) },
            onDrag = { change, dragged ->
                change.consume()
                drag(dragged.y)
            },
            onDragEnd = { stop() },
            onDragCancel = { stop() },
        )
    }
}

/**
 * @param onMove reorder the list the UI is drawing, called many times per drag.
 * @param onSettle persist the order, called once when the finger lifts.
 */
@Composable
fun rememberReorderState(
    listState: LazyListState,
    onMove: (from: Int, to: Int) -> Unit,
    onSettle: () -> Unit,
): ReorderState {
    // The state outlives any one composition, but the callbacks close over the
    // list as it is *now* — captured once, a drag would reorder a stale copy.
    val currentMove by rememberUpdatedState(onMove)
    val currentSettle by rememberUpdatedState(onSettle)

    return remember(listState) {
        ReorderState(
            listState = listState,
            onMove = { from, to -> currentMove(from, to) },
            onSettle = { currentSettle() },
        )
    }
}
