// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.auriel.choir.data.LibrarySnapshot
import app.auriel.choir.data.MediaStoreRepository
import app.auriel.choir.data.MusicLibrary
import app.auriel.choir.data.likes.LikesRepository
import app.auriel.choir.data.likes.likedTracksIn
import app.auriel.choir.data.lyrics.Lyrics
import app.auriel.choir.data.lyrics.LyricsRepository
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.playlist.PlaylistFiles
import app.auriel.choir.data.playlist.PlaylistRepository
import app.auriel.choir.data.playlist.PlaylistSummary
import app.auriel.choir.data.playlist.PlaylistTrack
import app.auriel.choir.data.playlist.playlistTracksIn
import app.auriel.choir.playback.PlaybackConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The four ways in, in the order the tab strip shows them. */
enum class LibraryTab { TRACKS, ALBUMS, ARTISTS, PLAYLISTS }

/** The outcome of an import or export, for the one line of feedback it earns. */
sealed interface PlaylistFileResult {
    data class Imported(val name: String, val imported: Int, val missing: Int) : PlaylistFileResult
    data object Exported : PlaylistFileResult
    data object Failed : PlaylistFileResult
}

/** The playlist currently open, resolved against the library. */
data class OpenPlaylist(
    val playlistId: Long = -1L,
    val name: String = "",
    val entries: List<PlaylistTrack> = emptyList(),
) {
    val tracks: List<Track> get() = entries.map(PlaylistTrack::track)
}

/** The words for the track on the Now Playing screen, if it has any. */
data class LyricsState(
    val trackId: Long? = null,
    val isLoading: Boolean = false,
    val lyrics: Lyrics? = null,
) {
    val hasLyrics: Boolean get() = lyrics != null && !lyrics.isEmpty
}

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
    private val likes: LikesRepository,
    private val lyricsRepository: LyricsRepository,
    private val playlistRepository: PlaylistRepository,
    private val playlistFiles: PlaylistFiles,
    private val mediaStore: MediaStoreRepository,
) : ViewModel() {

    val snapshot: StateFlow<LibrarySnapshot> = library.snapshot

    private val _selectedTab = MutableStateFlow(LibraryTab.TRACKS)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // --- Playlists -----------------------------------------------------------

    val playlists: StateFlow<List<PlaylistSummary>> = playlistRepository.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _openPlaylist = MutableStateFlow(OpenPlaylist())
    val openPlaylist: StateFlow<OpenPlaylist> = _openPlaylist.asStateFlow()

    private var playlistJob: Job? = null

    /** Legacy MediaStore playlists, if this device still has any to offer. */
    private val _legacyPlaylistCount = MutableStateFlow(0)
    val legacyPlaylistCount: StateFlow<Int> = _legacyPlaylistCount.asStateFlow()

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

    // --- Liked songs ---------------------------------------------------------

    /** Which tracks show a heart. Every list observes the one set. */
    val likedIds: StateFlow<Set<Long>> = likes.likedIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Liked Songs as a playable list: stored order, resolved against the
     * library so a track whose file has gone simply does not appear.
     */
    val likedTracks: StateFlow<List<Track>> =
        combine(snapshot, likes.liked) { library, liked ->
            likedTracksIn(liked, library.tracks)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // MediaStore renumbers the whole library after a rescan, which would
        // otherwise orphan every like and empty every playlist. Reconciling on
        // each change is cheap and does nothing in the overwhelmingly common
        // case where ids are stable.
        viewModelScope.launch {
            library.snapshot
                .map { it.tracks }
                .distinctUntilChanged()
                .collect { tracks ->
                    likes.reconcile(tracks)
                    playlistRepository.reconcile(tracks)
                }
        }

        // Android 10 may still have playlists from before the platform closed
        // the tables off. Offering to take a copy is the only way they survive.
        viewModelScope.launch {
            _legacyPlaylistCount.value = runCatching { mediaStore.queryPlaylists().size }
                .getOrDefault(0)
        }
    }

    fun toggleLike(track: Track) {
        viewModelScope.launch { likes.toggle(track) }
    }

    // --- Lyrics --------------------------------------------------------------

    private val _lyrics = MutableStateFlow(LyricsState())
    val lyrics: StateFlow<LyricsState> = _lyrics.asStateFlow()

    private var lyricsJob: Job? = null

    /**
     * Loads the words for whatever is playing. Reading tags means opening the
     * audio file, so this is driven by the Now Playing screen rather than by
     * the queue: a track that is never looked at is never read.
     */
    fun loadLyrics(track: Track?) {
        if (track == null) {
            lyricsJob?.cancel()
            _lyrics.value = LyricsState()
            return
        }
        if (_lyrics.value.trackId == track.id) return

        lyricsJob?.cancel()
        _lyrics.value = LyricsState(trackId = track.id, isLoading = true)
        lyricsJob = viewModelScope.launch {
            val found = lyricsRepository.forTrack(track)
            _lyrics.value = LyricsState(track.id, isLoading = false, lyrics = found)
        }
    }

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

    /**
     * Watches one playlist. Members and the library are combined here rather
     * than in the database, because what a playlist *contains* is Choir's own
     * data and what is *playable* is MediaStore's.
     */
    fun openPlaylist(playlistId: Long) {
        if (_openPlaylist.value.playlistId == playlistId) return

        playlistJob?.cancel()
        _openPlaylist.value = OpenPlaylist(playlistId = playlistId)
        playlistJob = viewModelScope.launch {
            val name = playlistRepository.name(playlistId).orEmpty()
            combine(playlistRepository.members(playlistId), snapshot) { members, library ->
                OpenPlaylist(
                    playlistId = playlistId,
                    name = playlistRepository.name(playlistId) ?: name,
                    entries = playlistTracksIn(members, library.tracks),
                )
            }.collect { _openPlaylist.value = it }
        }
    }

    fun createPlaylist(name: String, andAdd: List<Track> = emptyList()) {
        viewModelScope.launch {
            val id = playlistRepository.create(name)
            if (andAdd.isNotEmpty()) playlistRepository.add(id, andAdd)
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        viewModelScope.launch {
            playlistRepository.rename(playlistId, name)
            // The open playlist's name is read once, so refresh it by hand.
            _openPlaylist.value = _openPlaylist.value.copy(name = name.trim())
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch { playlistRepository.delete(playlistId) }
    }

    fun addToPlaylist(playlistId: Long, tracks: List<Track>) {
        viewModelScope.launch { playlistRepository.add(playlistId, tracks) }
    }

    fun removeFromPlaylist(playlistId: Long, memberId: Long) {
        viewModelScope.launch { playlistRepository.remove(playlistId, memberId) }
    }

    fun reorderPlaylist(playlistId: Long, memberIdsInOrder: List<Long>) {
        viewModelScope.launch { playlistRepository.applyOrder(playlistId, memberIdsInOrder) }
    }

    // --- Playlist files ------------------------------------------------------

    private val _fileResult = MutableStateFlow<PlaylistFileResult?>(null)
    val fileResult: StateFlow<PlaylistFileResult?> = _fileResult.asStateFlow()

    fun importPlaylist(uri: Uri) {
        viewModelScope.launch {
            val result = playlistFiles.import(uri, snapshot.value.tracks)
            _fileResult.value = if (result == null) {
                PlaylistFileResult.Failed
            } else {
                PlaylistFileResult.Imported(result.name, result.imported, result.missing)
            }
        }
    }

    fun exportPlaylist(uri: Uri, tracks: List<Track>) {
        viewModelScope.launch {
            _fileResult.value = if (playlistFiles.export(uri, tracks)) {
                PlaylistFileResult.Exported
            } else {
                PlaylistFileResult.Failed
            }
        }
    }

    fun importLegacyPlaylists() {
        viewModelScope.launch {
            playlistFiles.importFromMediaStore()
            _legacyPlaylistCount.value = 0
        }
    }

    fun clearFileResult() {
        _fileResult.value = null
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
