// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.C
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The order the queue popup lists tracks in.
 *
 * [playOrder] walks a timeline by following "what comes after this one", which
 * is the only way to learn a shuffle order — the player will not hand it over
 * as a list. The walk is over a linked structure the caller supplies, so the
 * cases worth testing are the shapes that structure can take: straight through,
 * scrambled, one item, and the circular one that repeat produces and that would
 * otherwise run until the heap gave out.
 */
class PlayOrderTest {

    /** A timeline that plays 0, 1, 2 … and stops. */
    private fun linear(count: Int): (Int) -> Int =
        { index -> if (index + 1 < count) index + 1 else C.INDEX_UNSET }

    /** A timeline that plays [order] and stops, expressed as "next" hops. */
    private fun following(order: List<Int>): (Int) -> Int = { index ->
        val at = order.indexOf(index)
        if (at < 0 || at == order.lastIndex) C.INDEX_UNSET else order[at + 1]
    }

    @Nested
    @DisplayName("in order")
    inner class InOrder {

        @Test
        fun `walks a queue from the first item to the last`() {
            assertEquals(listOf(0, 1, 2, 3), playOrder(4, 0, linear(4)))
        }

        @Test
        fun `a queue of one is a list of one`() {
            assertEquals(listOf(0), playOrder(1, 0, linear(1)))
        }

        @Test
        fun `an empty queue is an empty list`() {
            assertEquals(emptyList<Int>(), playOrder(0, C.INDEX_UNSET, linear(0)))
        }

        @Test
        fun `no first item means nothing to walk`() {
            assertEquals(emptyList<Int>(), playOrder(4, C.INDEX_UNSET, linear(4)))
        }
    }

    @Nested
    @DisplayName("shuffled")
    inner class Shuffled {

        /**
         * The point of the whole function: with shuffle on, the queue is listed
         * in the order it will be heard, not the order it was built in.
         */
        @Test
        fun `follows the shuffle order rather than the index order`() {
            val order = listOf(2, 0, 3, 1)
            assertEquals(order, playOrder(4, 2, following(order)))
        }

        @Test
        fun `starts wherever the shuffle order starts`() {
            val order = listOf(3, 1, 0, 2)
            assertEquals(order, playOrder(4, 3, following(order)))
        }
    }

    @Nested
    @DisplayName("repeat")
    inner class Repeat {

        /**
         * Repeat makes "the item after the last" the first one again. Followed
         * naively that is an infinite list; the walk has to stop at the item it
         * started from and list each track once.
         */
        @Test
        fun `a circular queue is listed once, not forever`() {
            val wrapping: (Int) -> Int = { index -> (index + 1) % 4 }
            assertEquals(listOf(0, 1, 2, 3), playOrder(4, 0, wrapping))
        }

        @Test
        fun `repeat one, where every item is followed by itself, lists it once`() {
            assertEquals(listOf(2), playOrder(4, 2, { it }))
        }

        @Test
        fun `a circular shuffle order wraps back to where it started`() {
            val order = listOf(1, 3, 0, 2)
            val wrapping: (Int) -> Int = { index -> order[(order.indexOf(index) + 1) % order.size] }
            assertEquals(order, playOrder(4, 1, wrapping))
        }
    }

    @Nested
    @DisplayName("a timeline that disagrees with itself")
    inner class Defensive {

        /**
         * A walk longer than the queue means the "next" it was given is not a
         * permutation. Trusting the count over the walk is the safe half: the
         * list is used to draw rows, and too many is worse than too few.
         */
        @Test
        fun `never returns more entries than the queue holds`() {
            val alwaysMoving: (Int) -> Int = { it + 1 }
            assertEquals(listOf(0, 1, 2), playOrder(3, 0, alwaysMoving))
        }

        @Test
        fun `stops at an index it has already visited`() {
            val backAndForth: (Int) -> Int = { index -> if (index == 0) 1 else 0 }
            assertEquals(listOf(0, 1), playOrder(8, 0, backAndForth))
        }
    }
}
