// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.folders.FolderRepository
import app.auriel.choir.data.folders.FolderRoot
import app.auriel.choir.data.folders.unindexedTracks
import app.auriel.choir.data.model.Album
import app.auriel.choir.data.model.Artist
import app.auriel.choir.data.model.MusicFolder
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.model.inAlbumOrder
import app.auriel.choir.data.model.toAlbums
import app.auriel.choir.data.model.toArtists
import app.auriel.choir.data.model.toFolderTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Everything the browse screens draw from, as one consistent picture.
 *
 * Playlists are deliberately absent: since v0.3.0 they are Choir's own data,
 * held in Room and observed separately, rather than a MediaStore collection
 * that the platform stopped answering questions about.
 */
data class LibrarySnapshot(
    val isLoading: Boolean = true,
    /** What MediaStore indexed. The Tracks, Albums and Artists tabs are these. */
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    /**
     * Music found in granted folders that MediaStore never indexed — the `.wv`
     * and `.tta` files the scanner types as `application/octet-stream`. Kept
     * apart from [tracks] because they have no tags to group by: an album view
     * of files the platform could not read would be one enormous "Unknown
     * album". They appear in the folder tree, in search, and anywhere a track
     * is referred to by id.
     */
    val folderTracks: List<Track> = emptyList(),
    /** Both of the above, arranged the way the files themselves are. */
    val folders: MusicFolder = MusicFolder(path = "", name = ROOT_FOLDER_NAME),
) {
    val isEmpty: Boolean get() = !isLoading && tracks.isEmpty() && folderTracks.isEmpty()

    /**
     * Everything playable, indexed or not. This is what an id is resolved
     * against — a queue entry, a like, a playlist member — because a track
     * reached through a granted folder is as real as any other once it is
     * playing.
     */
    val allTracks: List<Track> get() = if (folderTracks.isEmpty()) tracks else tracks + folderTracks

    fun album(id: Long): Album? = albums.firstOrNull { it.id == id }

    fun artist(id: Long): Artist? = artists.firstOrNull { it.id == id }

    fun tracksOfAlbum(id: Long): List<Track> = tracks.filter { it.albumId == id }.inAlbumOrder()

    /** An artist's work, grouped by album the way a shelf would be. */
    fun albumsOfArtist(id: Long): List<Album> = albums.filter { album ->
        album.artistId == id || tracks.any { it.artistId == id && it.albumId == album.id }
    }

    fun tracksOfArtist(id: Long): List<Track> = tracks.filter { it.artistId == id }

    fun folder(path: String): MusicFolder? = folders.find(path)
}

/** The tree's own name. Shown only in a breadcrumb, never as a row. */
const val ROOT_FOLDER_NAME = "Storage"

/**
 * The library, loaded once and shared.
 *
 * Every browse view is a projection of the same track list, so there is one
 * loader for the whole app rather than one per screen: albums and artists are
 * grouped out of the tracks in memory, and screens observe [snapshot]. This is
 * the modern shape of what AOSP spread across a `CursorLoader` per browser.
 *
 * Since v0.4.0 there are two sources rather than one. MediaStore is still the
 * truth about what it indexed; the folders the user granted supply what it
 * refused to. They are merged here, once, so that no screen below has to know
 * there were two.
 */
class MusicLibrary(
    private val repository: MediaStoreRepository,
    private val folders: FolderRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _snapshot = MutableStateFlow(LibrarySnapshot())
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    /** The granted folders themselves, for the rows that manage them. */
    val folderRoots: StateFlow<List<FolderRoot>> = folders.roots

    /** True while a granted folder is being read, which can take a moment. */
    val isScanningFolders: StateFlow<Boolean> = folders.isScanning

    private var job: Job? = null

    /**
     * Begins observing MediaStore and the granted folders. Called once the read
     * permission is granted; calling it again while already running is a no-op.
     */
    fun start() {
        if (job?.isActive == true) return

        job = scope.launch {
            // The stored folder files arrive immediately; the rescan below
            // updates them in place once it has walked the trees again.
            launch { folders.refresh() }

            combine(
                repository.observeTracks(),
                folders.observeFiles(),
            ) { tracks, folderFiles -> publish(tracks, folderFiles) }
                .collect { snapshot -> _snapshot.value = snapshot }
        }
    }

    /** Rereads the granted folders, for when files have been copied in. */
    fun rescanFolders() {
        scope.launch { folders.refresh() }
    }

    private fun publish(tracks: List<Track>, folderFiles: List<Track>): LibrarySnapshot {
        val folderTracks = unindexedTracks(folderFiles, tracks)

        return LibrarySnapshot(
            isLoading = false,
            tracks = tracks,
            albums = tracks.toAlbums(),
            artists = tracks.toArtists(),
            folderTracks = folderTracks,
            folders = (tracks + folderTracks).toFolderTree(ROOT_FOLDER_NAME),
        )
    }
}
