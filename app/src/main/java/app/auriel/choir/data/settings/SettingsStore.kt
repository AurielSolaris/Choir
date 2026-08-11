// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/**
 * Choir's settings, on disk.
 *
 * SharedPreferences with a change listener turned into a Flow, rather than
 * DataStore: this is a handful of scalars read on one screen, and Choir already
 * declines dependencies it can do without — there is no image library and the
 * icons are path data in a Kotlin file.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** Re-reads on every change; there are few enough keys for that to be free. */
    val lyrics: Flow<LyricsSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(readLyrics())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readLyrics())

        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    fun readLyrics(): LyricsSettings = LyricsSettings(
        onlineEnabled = prefs.getBoolean(KEY_ONLINE, false),
        unmeteredOnly = prefs.getBoolean(KEY_UNMETERED_ONLY, true),
        providers = LyricsProviderId.entries.associateWith { id ->
            val defaults = LyricsSettings.DEFAULT_PROVIDERS[id] ?: ProviderSettings()
            ProviderSettings(
                enabled = prefs.getBoolean(id.enabledKey, defaults.enabled),
                apiKey = prefs.getString(id.keyKey, defaults.apiKey).orEmpty(),
                baseUrl = prefs.getString(id.urlKey, defaults.baseUrl).orEmpty(),
            )
        },
        providerOrder = readProviderOrder(),
    )

    /**
     * Stored as names rather than ordinals: an ordinal is a promise never to
     * reorder the enum, and this is exactly the enum most likely to gain an
     * entry. A name that no longer exists is simply skipped.
     */
    private fun readProviderOrder(): List<LyricsProviderId> {
        val stored = prefs.getString(KEY_ORDER, null) ?: return LyricsProviderId.entries.toList()
        return stored.split(',')
            .mapNotNull { name ->
                LyricsProviderId.entries.firstOrNull { it.name == name.trim() }
            }
            .ifEmpty { LyricsProviderId.entries.toList() }
    }

    fun setProviderOrder(order: List<LyricsProviderId>) =
        prefs.edit().putString(KEY_ORDER, order.joinToString(",") { it.name }).apply()

    fun setOnlineEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_ONLINE, enabled).apply()

    fun setUnmeteredOnly(only: Boolean) = prefs.edit().putBoolean(KEY_UNMETERED_ONLY, only).apply()

    fun setProviderEnabled(id: LyricsProviderId, enabled: Boolean) =
        prefs.edit().putBoolean(id.enabledKey, enabled).apply()

    fun setProviderKey(id: LyricsProviderId, key: String) =
        prefs.edit().putString(id.keyKey, key.trim()).apply()

    fun setProviderUrl(id: LyricsProviderId, url: String) =
        prefs.edit().putString(id.urlKey, url.trim()).apply()

    private val LyricsProviderId.enabledKey get() = "lyrics.${name.lowercase()}.enabled"
    private val LyricsProviderId.keyKey get() = "lyrics.${name.lowercase()}.key"
    private val LyricsProviderId.urlKey get() = "lyrics.${name.lowercase()}.url"

    private companion object {
        const val NAME = "choir.settings"
        const val KEY_ONLINE = "lyrics.online"
        const val KEY_UNMETERED_ONLY = "lyrics.unmeteredOnly"
        const val KEY_ORDER = "lyrics.order"
    }
}
