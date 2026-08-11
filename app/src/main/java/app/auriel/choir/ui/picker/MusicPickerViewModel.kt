// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.picker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auriel.choir.data.MusicLibrary
import app.auriel.choir.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class PickerUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val tracks: List<Track> = emptyList(),
)

/**
 * Backs the standalone picker. Deliberately has no [PlaybackConnection]
 * [app.auriel.choir.playback.PlaybackConnection]: a picker chooses a file and
 * leaves, and must never disturb what the user is listening to.
 */
class MusicPickerViewModel(private val library: MusicLibrary) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val state: StateFlow<PickerUiState> =
        combine(library.snapshot, _query) { snapshot, query ->
            val term = query.trim()
            PickerUiState(
                isLoading = snapshot.isLoading,
                query = query,
                tracks = if (term.isBlank()) {
                    snapshot.tracks
                } else {
                    snapshot.tracks.filter {
                        it.title.contains(term, ignoreCase = true) ||
                            it.artist.contains(term, ignoreCase = true) ||
                            it.album.contains(term, ignoreCase = true)
                    }
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PickerUiState())

    fun start() = library.start()

    fun onQueryChanged(query: String) {
        _query.value = query
    }
}
