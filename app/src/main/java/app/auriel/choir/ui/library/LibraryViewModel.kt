// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auriel.choir.data.LibrarySnapshot
import app.auriel.choir.data.MusicLibrary
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.Track
import app.auriel.choir.playback.PlaybackConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The four ways in, in the order the tab strip shows them. */
enum class LibraryTab { TRACKS, ALBUMS, ARTISTS, PLAYLISTS }

/** Tracks of the playlist currently open, if any. */
data class PlaylistTracks(
    val playlistId: Long = -1L,
    val isLoading: Boolean = false,
    val tracks: List<Track> = emptyList(),
)

data class SearchResults(
    val query: String = "",
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}

/**
 * Drives every browse screen.
 *
 * One ViewModel for the whole navigation graph rather than one per destination:
 * albums, artists and search are all views of a single in-memory library, and
 * splitting them would mean re-deriving the same lists on every drill-down.
 */
class LibraryViewModel(
    private val library: MusicLibrary,
    private val playback: PlaybackConnection,
) : ViewModel() {

    val snapshot: StateFlow<LibrarySnapshot> = library.snapshot

    private val _selectedTab = MutableStateFlow(LibraryTab.TRACKS)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _playlistTracks = MutableStateFlow(PlaylistTracks())
    val playlistTracks: StateFlow<PlaylistTracks> = _playlistTracks.asStateFlow()

    private var playlistJob: Job? = null

    /**
     * Filtering happens over the whole library on every keystroke. At a few
     * thousand rows that is imperceptible and needs no index; if a library ever
     * makes it stutter, this is the one place to fix.
     */
    val searchResults: StateFlow<SearchResults> =
        combine(snapshot, _searchQuery) { library, query ->
            val term = query.trim()
            if (term.isBlank()) {
                SearchResults(query = query)
            } else {
                SearchResults(
                    query = query,
                    tracks = library.tracks.filter {
                        it.title.contains(term, ignoreCase = true) ||
                            it.artist.contains(term, ignoreCase = true) ||
                            it.album.contains(term, ignoreCase = true)
                    },
                    albums = library.albums.filter {
                        it.title.contains(term, ignoreCase = true) ||
                            it.artist.contains(term, ignoreCase = true)
                    },
                    artists = library.artists.filter {
                        it.name.contains(term, ignoreCase = true)
                    },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    fun start() = library.start()

    fun onTabSelected(tab: LibraryTab) {
        _selectedTab.value = tab
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun clearSearch() {
        _searchQuery.value = ""
    }

    fun openPlaylist(playlistId: Long) {
        if (_playlistTracks.value.playlistId == playlistId && !_playlistTracks.value.isLoading) {
            return
        }

        playlistJob?.cancel()
        _playlistTracks.value = PlaylistTracks(playlistId = playlistId, isLoading = true)
        playlistJob = viewModelScope.launch {
            val tracks = library.tracksOfPlaylist(playlistId)
            _playlistTracks.value = PlaylistTracks(playlistId, isLoading = false, tracks = tracks)
        }
    }

    // --- Playback -----------------------------------------------------------
    //
    // Playing from a list always queues that whole list, so "next" means the
    // next track of the album or playlist you tapped, not the next track in the
    // library. This is what made the AOSP browsers feel coherent.

    fun play(tracks: List<Track>, index: Int) = playback.play(tracks, index)

    fun shuffle(tracks: List<Track>) = playback.shuffleAll(tracks)

    fun shuffleAll() = playback.shuffleAll(snapshot.value.tracks)
}
