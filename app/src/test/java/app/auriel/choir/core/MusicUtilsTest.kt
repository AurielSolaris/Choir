// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MusicUtilsTest {

    @Nested
    @DisplayName("makeTimeString")
    inner class MakeTimeString {

        @Test
        fun `formats sub-minute durations with a zero minute`() {
            assertEquals("0:07", MusicUtils.makeTimeString(7_000))
            assertEquals("0:59", MusicUtils.makeTimeString(59_999))
        }

        @Test
        fun `pads seconds to two digits`() {
            assertEquals("3:05", MusicUtils.makeTimeString(185_000))
        }

        @Test
        fun `widens to hours only past the hour mark`() {
            assertEquals("59:59", MusicUtils.makeTimeString(TimeUnit.SECONDS.toMillis(3599)))
            assertEquals("1:00:00", MusicUtils.makeTimeString(TimeUnit.HOURS.toMillis(1)))
            assertEquals(
                "2:03:04",
                MusicUtils.makeTimeString(
                    TimeUnit.HOURS.toMillis(2) +
                        TimeUnit.MINUTES.toMillis(3) +
                        TimeUnit.SECONDS.toMillis(4),
                ),
            )
        }

        @Test
        fun `truncates rather than rounds partial seconds`() {
            assertEquals("0:01", MusicUtils.makeTimeString(1_999))
        }

        @Test
        fun `renders unknown and negative durations as zero`() {
            // MediaStore reports -1 for files it could not probe.
            assertEquals("0:00", MusicUtils.makeTimeString(-1))
            assertEquals("0:00", MusicUtils.makeTimeString(0))
        }
    }

    @Nested
    @DisplayName("tagOrFallback")
    inner class TagOrFallback {

        @Test
        fun `keeps a real tag`() {
            assertEquals("Bach", MusicUtils.tagOrFallback("Bach", "Unknown artist"))
        }

        @Test
        fun `replaces MediaStore's unknown sentinel`() {
            assertEquals(
                "Unknown artist",
                MusicUtils.tagOrFallback(MusicUtils.UNKNOWN_TAG, "Unknown artist"),
            )
        }

        @Test
        fun `replaces null and blank tags`() {
            assertEquals("Unknown album", MusicUtils.tagOrFallback(null, "Unknown album"))
            assertEquals("Unknown album", MusicUtils.tagOrFallback("   ", "Unknown album"))
        }
    }

    @Nested
    @DisplayName("makeSubtitle")
    inner class MakeSubtitle {

        @Test
        fun `joins artist and album`() {
            assertEquals("Bach · Cantatas", MusicUtils.makeSubtitle("Bach", "Cantatas"))
        }

        @Test
        fun `omits the separator when a part is missing`() {
            assertEquals("Bach", MusicUtils.makeSubtitle("Bach", ""))
            assertEquals("Cantatas", MusicUtils.makeSubtitle("", "Cantatas"))
            assertEquals("", MusicUtils.makeSubtitle("", ""))
        }
    }
}
