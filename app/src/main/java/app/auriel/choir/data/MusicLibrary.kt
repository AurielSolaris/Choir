// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.Playlist
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.model.inAlbumOrder
import app.auriel.choir.data.model.toAlbums
import app.auriel.choir.data.model.toArtists
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Everything the browse screens draw from, as one consistent picture. */
data class LibrarySnapshot(
    val isLoading: Boolean = true,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = !isLoading && tracks.isEmpty()

    fun album(id: Long): Album? = albums.firstOrNull { it.id == id }

    fun artist(id: Long): Artist? = artists.firstOrNull { it.id == id }

    fun playlist(id: Long): Playlist? = playlists.firstOrNull { it.id == id }

    fun tracksOfAlbum(id: Long): List<Track> = tracks.filter { it.albumId == id }.inAlbumOrder()

    /** An artist's work, grouped by album the way a shelf would be. */
    fun albumsOfArtist(id: Long): List<Album> = albums.filter { album ->
        album.artistId == id || tracks.any { it.artistId == id && it.albumId == album.id }
    }

    fun tracksOfArtist(id: Long): List<Track> = tracks.filter { it.artistId == id }
}

/**
 * The library, loaded once and shared.
 *
 * Every browse view is a projection of the same track list, so there is one
 * loader for the whole app rather than one per screen: albums and artists are
 * grouped out of the tracks in memory, and screens observe [snapshot]. This is
 * the modern shape of what AOSP spread across a `CursorLoader` per browser.
 */
class MusicLibrary(private val repository: MediaStoreRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot = MutableStateFlow(LibrarySnapshot())
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    private var job: Job? = null

    /**
     * Begins observing MediaStore. Called once the read permission is granted;
     * calling it again while already running is a no-op.
     */
    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            repository.observeTracks().collect { tracks ->
                _snapshot.value = LibrarySnapshot(
                    isLoading = false,
                    tracks = tracks,
                    albums = tracks.toAlbums(),
                    artists = tracks.toArtists(),
                    // Re-read alongside the tracks: a media scan can add both.
                    playlists = repository.queryPlaylists(),
                )
            }
        }
    }

    /**
     * Playlist contents are not part of the snapshot: they need their own query
     * per playlist, and only matter while one is open.
     */
    suspend fun tracksOfPlaylist(playlistId: Long): List<Track> =
        repository.queryPlaylistTracks(playlistId)
}
