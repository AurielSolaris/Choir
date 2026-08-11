// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.settings.LyricsProviderId
import app.auriel.choir.data.settings.LyricsSettings
import app.auriel.choir.data.settings.SettingsStore

/**
 * Asks the configured services for a lyric, if — and only if — asking is allowed.
 *
 * Every gate is checked here rather than scattered through the providers, so
 * there is one place to read to know when Choir touches the network:
 *
 *  1. the master switch is on,
 *  2. at least one provider is enabled and configured,
 *  3. there is a connection, and it is unmetered unless told otherwise,
 *  4. this track has not already been asked about (see [LyricsCache]).
 *
 * Fail any of those and no packet leaves the device.
 */
class OnlineLyricsSource(
    private val context: Context,
    private val settings: SettingsStore,
    private val cache: LyricsCache,
    private val providers: List<LyricsProvider> = listOf(
        LrclibProvider(),
        NeteaseProvider(),
        MusixmatchProvider(),
        CustomProvider(),
    ),
) {

    /**
     * @return the raw lyric document, still to be parsed, or null if nothing was
     *   found or nothing was permitted.
     */
    fun fetch(query: LyricsQuery, fingerprint: String): String? {
        val configured = settings.readLyrics()

        // The switch is checked before the cache, not after. Turning the
        // feature off has to mean no lyrics from it at all — serving a copy
        // fetched last week would technically involve no network and still be
        // the opposite of what was asked for.
        val active = activeAndConfigured(configured)
        if (active.isEmpty()) return null

        cache.read(fingerprint)?.let { cached ->
            // A remembered miss counts too: asking three services the same
            // unanswerable question on every screen visit helps nobody.
            return cached.takeIf { it.isNotEmpty() }
        }
        if (!isConnectionAllowed(configured)) {
            MusicLog.d(TAG, "skipping fetch: no suitable connection")
            return null
        }

        for (provider in active) {
            val result = runCatching {
                provider.fetch(query, configured.provider(provider.id))
            }.getOrNull()

            if (!result.isNullOrBlank()) {
                MusicLog.i(TAG, "lyrics for '${query.title}' from ${provider.id}")
                cache.write(fingerprint, result)
                return result
            }
        }

        MusicLog.d(TAG, "no provider had lyrics for '${query.title}'")
        cache.writeMiss(fingerprint)
        return null
    }

    /** True when a fetch would be attempted, used to decide what to tell the user. */
    fun isEnabled(): Boolean = activeAndConfigured(settings.readLyrics()).isNotEmpty()

    private fun activeAndConfigured(configured: LyricsSettings): List<LyricsProvider> =
        configured.activeProviders.mapNotNull { id: LyricsProviderId ->
            providers.firstOrNull { it.id == id }
                ?.takeIf { it.isConfigured(configured.provider(id)) }
        }

    /**
     * Unmetered by default. Someone on a capped plan should not discover Choir
     * has been spending it on lyrics they never asked for.
     */
    private fun isConnectionAllowed(configured: LyricsSettings): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false

        return !configured.unmeteredOnly ||
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val TAG = "OnlineLyricsSource"
    }
}
