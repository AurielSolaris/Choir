// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The promise the README makes, as a test: a fresh install asks nobody
 * anything.
 */
class LyricsSettingsTest {

    @Test
    fun `online lyrics are off out of the box`() {
        assertFalse(LyricsSettings().onlineEnabled)
    }

    @Test
    fun `nothing is asked while the master switch is off, however configured`() {
        val settings = LyricsSettings(
            onlineEnabled = false,
            providers = mapOf(
                LyricsProviderId.LRCLIB to ProviderSettings(enabled = true),
                LyricsProviderId.MUSIXMATCH to ProviderSettings(enabled = true, apiKey = "k"),
                LyricsProviderId.CUSTOM to ProviderSettings(enabled = true, baseUrl = "https://x"),
            ),
        )

        assertTrue(settings.activeProviders.isEmpty())
    }

    @Test
    fun `mobile data is spared unless the user says otherwise`() {
        assertTrue(LyricsSettings().unmeteredOnly)
    }

    @Test
    fun `turning the switch on gives a working setup without further fiddling`() {
        val settings = LyricsSettings(onlineEnabled = true)

        assertEquals(listOf(LyricsProviderId.LRCLIB), settings.activeProviders)
    }

    @Test
    fun `keyed services stay off until they are configured`() {
        val settings = LyricsSettings(onlineEnabled = true)

        assertFalse(settings.provider(LyricsProviderId.MUSIXMATCH).enabled)
        assertFalse(settings.provider(LyricsProviderId.CUSTOM).enabled)
    }

    /**
     * NetEase needs no key, so "configured" cannot be what holds it back. It is
     * off because it calls another app's undocumented endpoints, and that is a
     * choice to be made rather than inherited.
     */
    @Test
    fun `the unofficial service stays off even though it needs no key`() {
        val settings = LyricsSettings(onlineEnabled = true)

        assertFalse(settings.provider(LyricsProviderId.NETEASE).enabled)
        assertFalse(settings.activeProviders.contains(LyricsProviderId.NETEASE))
    }

    @Test
    fun `NetEase is asked after LRCLIB and before anything needing a key`() {
        val settings = LyricsSettings(
            onlineEnabled = true,
            providers = mapOf(
                LyricsProviderId.MUSIXMATCH to ProviderSettings(enabled = true, apiKey = "k"),
                LyricsProviderId.NETEASE to ProviderSettings(enabled = true),
                LyricsProviderId.LRCLIB to ProviderSettings(enabled = true),
            ),
        )

        assertEquals(
            listOf(
                LyricsProviderId.LRCLIB,
                LyricsProviderId.NETEASE,
                LyricsProviderId.MUSIXMATCH,
            ),
            settings.activeProviders,
        )
    }

    @Test
    fun `the order can be rearranged, and the fetch follows it`() {
        val settings = LyricsSettings(
            onlineEnabled = true,
            providers = mapOf(
                LyricsProviderId.LRCLIB to ProviderSettings(enabled = true),
                LyricsProviderId.NETEASE to ProviderSettings(enabled = true),
            ),
            providerOrder = listOf(
                LyricsProviderId.NETEASE,
                LyricsProviderId.LRCLIB,
                LyricsProviderId.MUSIXMATCH,
                LyricsProviderId.CUSTOM,
            ),
        )

        assertEquals(
            listOf(LyricsProviderId.NETEASE, LyricsProviderId.LRCLIB),
            settings.activeProviders,
        )
    }

    /**
     * A stored order is written by whichever version of Choir saved it. The
     * repair below is what lets a provider be added in an update without
     * resetting anyone's arrangement — the new one turns up at the end.
     */
    @Test
    fun `a provider missing from a saved order is appended, not lost`() {
        val settings = LyricsSettings(
            providerOrder = listOf(LyricsProviderId.CUSTOM, LyricsProviderId.LRCLIB),
        )

        assertEquals(
            listOf(
                LyricsProviderId.CUSTOM,
                LyricsProviderId.LRCLIB,
                LyricsProviderId.NETEASE,
                LyricsProviderId.MUSIXMATCH,
            ),
            settings.orderedProviders,
        )
    }

    @Test
    fun `a saved order listing something twice keeps only the first place`() {
        val settings = LyricsSettings(
            providerOrder = listOf(
                LyricsProviderId.CUSTOM,
                LyricsProviderId.LRCLIB,
                LyricsProviderId.CUSTOM,
            ),
        )

        assertEquals(LyricsProviderId.entries.size, settings.orderedProviders.size)
        assertEquals(LyricsProviderId.CUSTOM, settings.orderedProviders.first())
    }

    @Test
    fun `an empty saved order falls back to the declared one`() {
        val settings = LyricsSettings(providerOrder = emptyList())

        assertEquals(LyricsProviderId.entries.toList(), settings.orderedProviders)
    }

    @Test
    fun `rearranging changes nothing while the master switch is off`() {
        val settings = LyricsSettings(
            onlineEnabled = false,
            providers = mapOf(LyricsProviderId.NETEASE to ProviderSettings(enabled = true)),
            providerOrder = listOf(LyricsProviderId.NETEASE),
        )

        assertTrue(settings.activeProviders.isEmpty())
    }

    @Test
    fun `services are asked free-first, then keyed, then whatever was plugged in`() {
        val settings = LyricsSettings(
            onlineEnabled = true,
            providers = mapOf(
                LyricsProviderId.CUSTOM to ProviderSettings(enabled = true, baseUrl = "https://x"),
                LyricsProviderId.MUSIXMATCH to ProviderSettings(enabled = true, apiKey = "k"),
                LyricsProviderId.LRCLIB to ProviderSettings(enabled = true),
            ),
        )

        assertEquals(
            listOf(
                LyricsProviderId.LRCLIB,
                LyricsProviderId.MUSIXMATCH,
                LyricsProviderId.CUSTOM,
            ),
            settings.activeProviders,
        )
    }
}
