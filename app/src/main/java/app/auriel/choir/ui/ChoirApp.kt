// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import android.net.Uri
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.auriel.choir.R
import app.auriel.choir.data.folders.FolderRoot
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.playlist.PlaylistSummary
import app.auriel.choir.playback.PlaybackConnection
import app.auriel.choir.playback.PlaybackProblem
import app.auriel.choir.ui.components.AddToPlaylistSheet
import app.auriel.choir.ui.components.ConfirmDialog
import app.auriel.choir.ui.components.LikeState
import app.auriel.choir.ui.components.MiniPlayer
import app.auriel.choir.ui.components.MiniPlayerHeight
import app.auriel.choir.ui.components.TextPromptDialog
import app.auriel.choir.ui.components.Toast
import app.auriel.choir.ui.components.TrackActionsSheet
import app.auriel.choir.ui.detail.AlbumDetailScreen
import app.auriel.choir.ui.detail.ArtistDetailScreen
import app.auriel.choir.ui.detail.PlaylistScreen
import app.auriel.choir.ui.detail.TrackListScreen
import app.auriel.choir.ui.folders.FolderScreen
import app.auriel.choir.ui.library.FolderResult
import app.auriel.choir.ui.library.LibraryScreen
import app.auriel.choir.ui.library.LibraryViewModel
import app.auriel.choir.ui.library.PlaylistFileResult
import app.auriel.choir.ui.nowplaying.NowPlayingScreen
import app.auriel.choir.ui.permission.PermissionGate
import app.auriel.choir.ui.search.SearchScreen
import app.auriel.choir.ui.settings.SettingsScreen
import app.auriel.choir.ui.theme.ChoirTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"
    const val LIKED = "liked"
    const val SETTINGS = "settings"

    const val ALBUM_ID = "albumId"
    const val ARTIST_ID = "artistId"
    const val PLAYLIST_ID = "playlistId"
    const val FOLDER_PATH = "folderPath"

    const val ALBUM = "album/{$ALBUM_ID}"
    const val ARTIST = "artist/{$ARTIST_ID}"
    const val PLAYLIST = "playlist/{$PLAYLIST_ID}"
    const val FOLDER = "folder/{$FOLDER_PATH}"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun playlist(id: Long) = "playlist/$id"

    /**
     * A folder is addressed by its path rather than by an id, because it has
     * no id worth having: the tree is rebuilt on every rescan, and a path is
     * the one thing about a folder that survives that.
     */
    fun folder(path: String) = "folder/${Uri.encode(path)}"
}

/**
 * The whole app: a permission gate around the browse hierarchy.
 *
 * Transitions are fades only; nothing in Choir slides, scales or bounces.
 */
@Composable
fun ChoirApp() {
    ChoirTheme {
        PermissionGate {
            ChoirNavigation()
        }
    }
}

@Composable
private fun ChoirNavigation(
    playback: PlaybackConnection = koinInject(),
) {
    val navController = rememberNavController()

    // One ViewModel for the graph: every destination is a view of the same
    // library, and a per-screen ViewModel would re-derive it on each push.
    val viewModel: LibraryViewModel = koinViewModel()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    // Every list draws hearts from the same set, so a like made anywhere is
    // visible everywhere at once.
    val likes = remember(likedIds) { LikeState(likedIds) }

    // The permission gate above guarantees access by the time this runs.
    LaunchedEffect(Unit) { viewModel.start() }

    // --- Sheets and dialogs, hoisted above the graph -------------------------
    //
    // One long-press sheet for the whole app rather than one per screen: it is
    // the same three questions wherever a track row appears, and the only thing
    // that varies is whether removing from a playlist is on offer.

    var actionTarget by remember { mutableStateOf<TrackTarget?>(null) }
    var addingToPlaylist by remember { mutableStateOf<List<Track>?>(null) }
    var namingPlaylist by remember { mutableStateOf<PlaylistPrompt?>(null) }
    var deletingPlaylist by remember { mutableStateOf<PlaylistSummary?>(null) }
    var exportTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var removingFolder by remember { mutableStateOf<FolderRoot?>(null) }

    // The system picker, which is the only way Choir ever sees a folder: the
    // grant it returns covers that subtree and nothing else.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let(viewModel::addFolder) }

    val importLauncher = rememberLauncherForActivityResult(
        // Not by MIME type: .m3u is reported as anything from audio/x-mpegurl
        // to application/octet-stream depending on which app wrote it.
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importPlaylist) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("audio/x-mpegurl"),
    ) { uri -> uri?.let { viewModel.exportPlaylist(it, exportTracks) } }

    val fade = tween<Float>(durationMillis = 180)

    // Lists run edge to edge under the gesture bar and the mini player, so both
    // have to be reserved as scroll padding.
    //
    // Only whether something is playing is observed here, never the position:
    // the player ticks four times a second, and reading the full state at this
    // level would recompose the entire graph — including a list mid-drag — on
    // every one of those ticks.
    val isPlayingSomething by remember(playback) {
        playback.state.map { it.nowPlaying != null }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = false)

    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listBottomPadding = navBarInset + if (isPlayingSomething) MiniPlayerHeight else 0.dp

    Box(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.LIBRARY,
            enterTransition = { fadeIn(fade) },
            exitTransition = { fadeOut(fade) },
            popEnterTransition = { fadeIn(fade) },
            popExitTransition = { fadeOut(fade) },
        ) {
            composable(Routes.LIBRARY) {
                val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()
                val legacyCount by viewModel.legacyPlaylistCount.collectAsStateWithLifecycle()
                val folderRoots by viewModel.folderRoots.collectAsStateWithLifecycle()
                val rootFolder = snapshot.folders

                LibraryScreen(
                    snapshot = snapshot,
                    selectedTab = selectedTab,
                    onTabSelected = viewModel::onTabSelected,
                    onTrackSelected = { viewModel.play(snapshot.tracks, it) },
                    onAlbumSelected = { navController.navigateOnce(Routes.album(it)) },
                    onArtistSelected = { navController.navigateOnce(Routes.artist(it)) },
                    onPlaylistSelected = { navController.navigateOnce(Routes.playlist(it)) },
                    onLikedSelected = { navController.navigateOnce(Routes.LIKED) },
                    onShuffleAll = viewModel::shuffleAll,
                    onSearch = { navController.navigateOnce(Routes.SEARCH) },
                    onSettings = { navController.navigateOnce(Routes.SETTINGS) },
                    onTrackLongPress = { index ->
                        snapshot.tracks.getOrNull(index)?.let { actionTarget = TrackTarget(it) }
                    },
                    onFolderSelected = { navController.navigateOnce(Routes.folder(it)) },
                    onFolderTrackSelected = { viewModel.play(rootFolder.tracks, it) },
                    onFolderTrackLongPress = { index ->
                        rootFolder.tracks.getOrNull(index)?.let { actionTarget = TrackTarget(it) }
                    },
                    onAddFolder = { folderLauncher.launch(null) },
                    onRescanFolders = viewModel::rescanFolders,
                    onRemoveFolder = { removingFolder = it },
                    onNewPlaylist = { namingPlaylist = PlaylistPrompt() },
                    onImportFile = { importLauncher.launch(arrayOf("*/*")) },
                    onImportLegacy = viewModel::importLegacyPlaylists,
                    playlists = playlists,
                    folderRoots = folderRoots,
                    legacyPlaylistCount = legacyCount,
                    likes = likes,
                    likedCount = likedTracks.size,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(Routes.LIKED) {
                val likedTracks by viewModel.likedTracks.collectAsStateWithLifecycle()

                TrackListScreen(
                    title = stringResource(R.string.liked_songs),
                    tracks = likedTracks,
                    // Never loading: the list is derived from state already in
                    // memory, not fetched.
                    isLoading = false,
                    emptyMessage = stringResource(R.string.liked_songs_hint),
                    onBack = navController::popBackStack,
                    onPlay = { viewModel.play(likedTracks, it) },
                    onShuffle = { viewModel.shuffle(likedTracks) },
                    likes = likes,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(
                route = Routes.ALBUM,
                arguments = listOf(navArgument(Routes.ALBUM_ID) { type = NavType.LongType }),
            ) { entry ->
                val albumId = entry.arguments?.getLong(Routes.ALBUM_ID) ?: return@composable
                val tracks = snapshot.tracksOfAlbum(albumId)

                AlbumDetailScreen(
                    album = snapshot.album(albumId),
                    tracks = tracks,
                    onBack = navController::popBackStack,
                    onPlay = { viewModel.play(tracks, it) },
                    onShuffle = { viewModel.shuffle(tracks) },
                    likes = likes,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(
                route = Routes.ARTIST,
                arguments = listOf(navArgument(Routes.ARTIST_ID) { type = NavType.LongType }),
            ) { entry ->
                val artistId = entry.arguments?.getLong(Routes.ARTIST_ID) ?: return@composable

                ArtistDetailScreen(
                    artist = snapshot.artist(artistId),
                    albums = snapshot.albumsOfArtist(artistId),
                    onBack = navController::popBackStack,
                    onAlbumSelected = { navController.navigateOnce(Routes.album(it)) },
                    onShuffle = { viewModel.shuffle(snapshot.tracksOfArtist(artistId)) },
                    bottomPadding = listBottomPadding,
                )
            }

            composable(
                route = Routes.PLAYLIST,
                arguments = listOf(navArgument(Routes.PLAYLIST_ID) { type = NavType.LongType }),
            ) { entry ->
                val playlistId = entry.arguments?.getLong(Routes.PLAYLIST_ID) ?: return@composable
                val open by viewModel.openPlaylist.collectAsStateWithLifecycle()

                // Survives process death, where the tap that opened it did not.
                LaunchedEffect(playlistId) { viewModel.openPlaylist(playlistId) }

                val entries = open.entries.takeIf { open.playlistId == playlistId }.orEmpty()
                val tracks = entries.map { it.track }

                PlaylistScreen(
                    name = open.name,
                    entries = entries,
                    onBack = navController::popBackStack,
                    onPlay = { viewModel.play(tracks, it) },
                    onShuffle = { viewModel.shuffle(tracks) },
                    onTrackLongPress = { track, memberId ->
                        actionTarget = TrackTarget(track, playlistId, memberId)
                    },
                    onReorder = { viewModel.reorderPlaylist(playlistId, it) },
                    onRename = {
                        namingPlaylist = PlaylistPrompt(playlistId = playlistId, name = open.name)
                    },
                    onDelete = {
                        deletingPlaylist = PlaylistSummary(playlistId, open.name, entries.size)
                    },
                    onExport = {
                        exportTracks = tracks
                        exportLauncher.launch("${open.name}.m3u")
                    },
                    likes = likes,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(
                route = Routes.FOLDER,
                arguments = listOf(navArgument(Routes.FOLDER_PATH) { type = NavType.StringType }),
            ) { entry ->
                val path = entry.arguments?.getString(Routes.FOLDER_PATH).orEmpty()
                val folder = snapshot.folder(path)
                val tracks = folder?.tracks.orEmpty()

                FolderScreen(
                    folder = folder,
                    onBack = navController::popBackStack,
                    onOpenFolder = { navController.navigateOnce(Routes.folder(it)) },
                    onPlay = { viewModel.play(tracks, it) },
                    // Everything below here, not just this level: "shuffle this
                    // folder" means the album and its two disc subfolders.
                    onShuffle = { folder?.let { viewModel.shuffle(it.allTracks()) } },
                    onTrackLongPress = { index ->
                        tracks.getOrNull(index)?.let { actionTarget = TrackTarget(it) }
                    },
                    likes = likes,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(Routes.SEARCH) {
                val results by viewModel.searchResults.collectAsStateWithLifecycle()

                SearchScreen(
                    results = results,
                    onQueryChanged = viewModel::onSearchQueryChanged,
                    onBack = navController::popBackStack,
                    onTrackSelected = { viewModel.play(results.tracks, it) },
                    onTrackLongPress = { index ->
                        results.tracks.getOrNull(index)?.let { actionTarget = TrackTarget(it) }
                    },
                    onAlbumSelected = { navController.navigateOnce(Routes.album(it)) },
                    onArtistSelected = { navController.navigateOnce(Routes.artist(it)) },
                    likes = likes,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = navController::popBackStack,
                    bottomPadding = listBottomPadding,
                )
            }

            composable(Routes.NOW_PLAYING) {
                // The only screen that wants the position, so the only one that
                // recomposes with the ticker.
                val playbackState by playback.state.collectAsStateWithLifecycle()

                // Liking needs the full track, not the trimmed metadata the
                // player carries; the control hides for anything the library
                // cannot account for.
                val playingTrack = playbackState.nowPlaying?.trackId?.let { id ->
                    snapshot.tracks.firstOrNull { it.id == id }
                }
                val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()

                // Tags are only read for a track someone is actually looking at.
                LaunchedEffect(playingTrack?.id) { viewModel.loadLyrics(playingTrack) }

                NowPlayingScreen(
                    state = playbackState,
                    onBack = navController::popBackStack,
                    onPlayPause = playback::togglePlayPause,
                    onNext = playback::next,
                    onPrevious = playback::previous,
                    onSeek = playback::seekTo,
                    onToggleShuffle = playback::toggleShuffle,
                    onCycleRepeat = playback::cycleRepeatMode,
                    onPlayQueueItem = playback::playQueueItem,
                    isLiked = playingTrack != null && playingTrack.id in likedIds,
                    onToggleLike = playingTrack?.let { track ->
                        { viewModel.toggleLike(track) }
                    },
                    lyrics = lyrics,
                )
            }
        }

        // The mini player sits above the graph so it persists across every
        // browse screen — but not over the full player, which replaces it.
        val backStackEntry by navController.currentBackStackEntryAsState()
        if (backStackEntry?.destination?.route != Routes.NOW_PLAYING) {
            // Collected inside its own composable so the ticker recomposes the
            // mini player alone, not everything above it.
            val miniState by playback.state.collectAsStateWithLifecycle()

            MiniPlayer(
                state = miniState,
                onClick = { navController.navigateOnce(Routes.NOW_PLAYING) },
                onPlayPause = playback::togglePlayPause,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // --- Sheets and dialogs ---------------------------------------------

        actionTarget?.let { target ->
            TrackActionsSheet(
                track = target.track,
                isLiked = target.track.id in likedIds,
                onLike = {
                    viewModel.toggleLike(target.track)
                    actionTarget = null
                },
                onAddToPlaylist = {
                    addingToPlaylist = listOf(target.track)
                    actionTarget = null
                },
                onRemoveFromPlaylist = if (target.playlistId != null && target.memberId != null) {
                    {
                        viewModel.removeFromPlaylist(target.playlistId, target.memberId)
                        actionTarget = null
                    }
                } else {
                    null
                },
                onDismiss = { actionTarget = null },
            )
        }

        addingToPlaylist?.let { tracks ->
            AddToPlaylistSheet(
                playlists = playlists,
                onSelect = { playlistId ->
                    viewModel.addToPlaylist(playlistId, tracks)
                    addingToPlaylist = null
                },
                onCreate = {
                    // Straight from the sheet into naming it, carrying the
                    // tracks along so the new playlist is not born empty.
                    namingPlaylist = PlaylistPrompt(pendingTracks = tracks)
                    addingToPlaylist = null
                },
                onDismiss = { addingToPlaylist = null },
            )
        }

        namingPlaylist?.let { prompt ->
            TextPromptDialog(
                title = stringResource(
                    if (prompt.playlistId == null) R.string.playlist_new else R.string.playlist_rename,
                ),
                initialValue = prompt.name,
                confirmLabel = stringResource(
                    if (prompt.playlistId == null) R.string.action_create else R.string.action_rename,
                ),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = { name ->
                    if (prompt.playlistId == null) {
                        viewModel.createPlaylist(name, prompt.pendingTracks)
                    } else {
                        viewModel.renamePlaylist(prompt.playlistId, name)
                    }
                    namingPlaylist = null
                },
                onDismiss = { namingPlaylist = null },
            )
        }

        deletingPlaylist?.let { playlist ->
            ConfirmDialog(
                title = stringResource(R.string.playlist_delete_title),
                message = stringResource(R.string.playlist_delete_message, playlist.name),
                confirmLabel = stringResource(R.string.action_delete),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    viewModel.deletePlaylist(playlist.id)
                    deletingPlaylist = null
                    navController.popBackStack()
                },
                onDismiss = { deletingPlaylist = null },
            )
        }

        removingFolder?.let { root ->
            ConfirmDialog(
                title = stringResource(R.string.folder_remove_title),
                message = stringResource(R.string.folder_remove_message, root.name),
                confirmLabel = stringResource(R.string.folder_remove),
                cancelLabel = stringResource(R.string.action_cancel),
                onConfirm = {
                    viewModel.removeFolder(root.treeUri)
                    removingFolder = null
                },
                onDismiss = { removingFolder = null },
            )
        }

        // One line of feedback for a folder that was granted, then gone.
        val folderResult by viewModel.folderResult.collectAsStateWithLifecycle()
        folderResult?.let { result ->
            Toast(message = result.message(), onShown = viewModel::clearFolderResult)
        }

        // One line of feedback for an import or export, then gone.
        val fileResult by viewModel.fileResult.collectAsStateWithLifecycle()
        fileResult?.let { result ->
            Toast(message = result.message(), onShown = viewModel::clearFileResult)
        }

        // The library now shows files the media scanner could not parse, and
        // some of them will not play. A tap that does nothing at all reads as a
        // bug; saying which format is missing reads as a limitation.
        var problem by remember { mutableStateOf<PlaybackProblem?>(null) }
        LaunchedEffect(playback) {
            playback.problems.collect { problem = it }
        }
        problem?.let { failed ->
            Toast(message = failed.message(), onShown = { problem = null })
        }
    }
}

/**
 * Turns a failure into a sentence, naming the format wherever Choir recognised
 * it. Vague apologies are what send people to a bug tracker.
 */
@Composable
private fun PlaybackProblem.message(): String {
    val name = format?.label
    return when {
        name != null && reason == PlaybackProblem.Reason.UNREADABLE_CONTAINER ->
            stringResource(R.string.playback_error_container, name)

        name != null && reason == PlaybackProblem.Reason.NO_DECODER ->
            stringResource(R.string.playback_error_decoder, name)

        reason == PlaybackProblem.Reason.UNREADABLE_FILE ->
            stringResource(R.string.playback_error_file, title)

        else -> stringResource(R.string.playback_error_other, title)
    }
}

/** A track a long press is asking about, and where it was pressed. */
private data class TrackTarget(
    val track: Track,
    val playlistId: Long? = null,
    val memberId: Long? = null,
)

/** A pending name — for a new playlist, or a rename of an existing one. */
private data class PlaylistPrompt(
    val playlistId: Long? = null,
    val name: String = "",
    val pendingTracks: List<Track> = emptyList(),
)

@Composable
private fun FolderResult.message(): String = when (this) {
    is FolderResult.Added -> stringResource(R.string.folder_added, name)
    FolderResult.Failed -> stringResource(R.string.folder_add_failed)
}

@Composable
private fun PlaylistFileResult.message(): String = when (this) {
    is PlaylistFileResult.Imported -> if (missing == 0) {
        stringResource(R.string.playlist_imported, imported, name)
    } else {
        stringResource(R.string.playlist_imported_partial, imported, name, missing)
    }

    PlaylistFileResult.Exported -> stringResource(R.string.playlist_exported)
    PlaylistFileResult.Failed -> stringResource(R.string.playlist_file_failed)
}

/**
 * Navigates only from a screen that is actually on top.
 *
 * A double tap on a row fires two clicks before the first navigation commits,
 * which would push the same screen twice; the entry is no longer RESUMED by the
 * time the second arrives, so this drops it.
 */
private fun NavHostController.navigateOnce(route: String) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
        navigate(route)
    }
}
