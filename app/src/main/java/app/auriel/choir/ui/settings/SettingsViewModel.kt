// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auriel.choir.data.lyrics.online.LyricsCache
import app.auriel.choir.data.settings.LyricsProviderId
import app.auriel.choir.data.settings.LyricsSettings
import app.auriel.choir.data.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val store: SettingsStore,
    private val cache: LyricsCache,
) : ViewModel() {

    val lyrics: StateFlow<LyricsSettings> = store.lyrics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.readLyrics())

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    init {
        refreshCacheSize()
    }

    fun setOnlineEnabled(enabled: Boolean) = store.setOnlineEnabled(enabled)

    fun setUnmeteredOnly(only: Boolean) = store.setUnmeteredOnly(only)

    fun setProviderEnabled(id: LyricsProviderId, enabled: Boolean) =
        store.setProviderEnabled(id, enabled)

    fun setProviderKey(id: LyricsProviderId, key: String) = store.setProviderKey(id, key)

    fun setProviderUrl(id: LyricsProviderId, url: String) = store.setProviderUrl(id, url)

    /**
     * Moves a service one place earlier or later in the asking order.
     *
     * Written against the repaired order rather than what is stored, so a move
     * also writes back a list this version understands — a saved order from an
     * older build is tidied the first time it is touched.
     */
    fun moveProvider(id: LyricsProviderId, by: Int) {
        val order = lyrics.value.orderedProviders.toMutableList()
        val from = order.indexOf(id)
        val to = from + by
        if (from < 0 || to !in order.indices) return

        order.removeAt(from)
        order.add(to, id)
        store.setProviderOrder(order)
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cache.clear() }
            refreshCacheSize()
        }
    }

    private fun refreshCacheSize() {
        viewModelScope.launch {
            _cacheBytes.value = withContext(Dispatchers.IO) { cache.sizeBytes() }
        }
    }
}
