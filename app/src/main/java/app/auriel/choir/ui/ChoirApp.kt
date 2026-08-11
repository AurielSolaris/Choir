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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import app.auriel.choir.playback.PlaybackConnection
import app.auriel.choir.ui.components.MiniPlayer
import app.auriel.choir.ui.components.MiniPlayerHeight
import app.auriel.choir.ui.detail.AlbumDetailScreen
import app.auriel.choir.ui.detail.ArtistDetailScreen
import app.auriel.choir.ui.detail.PlaylistDetailScreen
import app.auriel.choir.ui.library.LibraryScreen
import app.auriel.choir.ui.library.LibraryViewModel
import app.auriel.choir.ui.nowplaying.NowPlayingScreen
import app.auriel.choir.ui.permission.PermissionGate
import app.auriel.choir.ui.search.SearchScreen
import app.auriel.choir.ui.theme.ChoirTheme
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val NOW_PLAYING = "now_playing"

    const val ALBUM_ID = "albumId"
    const val ARTIST_ID = "artistId"
    const val PLAYLIST_ID = "playlistId"

    const val ALBUM = "album/{$ALBUM_ID}"
    const val ARTIST = "artist/{$ARTIST_ID}"
    const val PLAYLIST = "playlist/{$PLAYLIST_ID}"

    fun album(id: Long) = "album/$id"
    fun artist(id: Long) = "artist/$id"
    fun playlist(id: Long) = "playlist/$id"
}

/**
 * The whole app: a permission gate around the browse hierarchy.
 *
 * Transitions are fades only — PLAN.md is explicit that nothing else moves.
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
    val playbackState by playback.state.collectAsStateWithLifecycle()

    // The permission gate above guarantees access by the time this runs.
    LaunchedEffect(Unit) { viewModel.start() }

    val fade = tween<Float>(durationMillis = 180)

    // Lists run edge to edge under the gesture bar and the mini player, so both
    // have to be reserved as scroll padding.
    val navBarInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val listBottomPadding = navBarInset +
        if (playbackState.nowPlaying != null) MiniPlayerHeight else 0.dp

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

                LibraryScreen(
                    snapshot = snapshot,
                    selectedTab = selectedTab,
                    onTabSelected = viewModel::onTabSelected,
                    onTrackSelected = { viewModel.play(snapshot.tracks, it) },
                    onAlbumSelected = { navController.navigateOnce(Routes.album(it)) },
                    onArtistSelected = { navController.navigateOnce(Routes.artist(it)) },
                    onPlaylistSelected = { navController.navigateOnce(Routes.playlist(it)) },
                    onShuffleAll = viewModel::shuffleAll,
                    onSearch = { navController.navigateOnce(Routes.SEARCH) },
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
                val playlistTracks by viewModel.playlistTracks.collectAsStateWithLifecycle()

                // Survives process death, where the tap that loaded it did not.
                LaunchedEffect(playlistId) { viewModel.openPlaylist(playlistId) }

                val tracks = playlistTracks.tracks.takeIf {
                    playlistTracks.playlistId == playlistId
                }.orEmpty()

                PlaylistDetailScreen(
                    playlist = snapshot.playlist(playlistId),
                    tracks = tracks,
                    isLoading = playlistTracks.playlistId != playlistId ||
                        playlistTracks.isLoading,
                    onBack = navController::popBackStack,
                    onPlay = { viewModel.play(tracks, it) },
                    onShuffle = { viewModel.shuffle(tracks) },
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
                    onAlbumSelected = { navController.navigateOnce(Routes.album(it)) },
                    onArtistSelected = { navController.navigateOnce(Routes.artist(it)) },
                    bottomPadding = listBottomPadding,
                )
            }

            composable(Routes.NOW_PLAYING) {
                NowPlayingScreen(
                    state = playbackState,
                    onBack = navController::popBackStack,
                    onPlayPause = playback::togglePlayPause,
                    onNext = playback::next,
                    onPrevious = playback::previous,
                    onSeek = playback::seekTo,
                    onToggleShuffle = playback::toggleShuffle,
                    onCycleRepeat = playback::cycleRepeatMode,
                )
            }
        }

        // The mini player sits above the graph so it persists across every
        // browse screen — but not over the full player, which replaces it.
        val backStackEntry by navController.currentBackStackEntryAsState()
        if (backStackEntry?.destination?.route != Routes.NOW_PLAYING) {
            MiniPlayer(
                state = playbackState,
                onClick = { navController.navigateOnce(Routes.NOW_PLAYING) },
                onPlayPause = playback::togglePlayPause,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
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
