// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.data.LibrarySnapshot
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.AlbumRow
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ArtistRow
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.ChoirTabs
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.PlaylistRow
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.TrackRow
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The library, browsable four ways — Choir's port of `MusicBrowserActivity` and
 * the tab host it presided over.
 *
 * Selecting a tab swaps the list under a shared header; drilling into an album,
 * artist or playlist pushes a new screen. That hierarchy is the iPod idiom
 * PLAN.md asks for, and it survives the phase 5 restyle unchanged.
 */
@Composable
fun LibraryScreen(
    snapshot: LibrarySnapshot,
    selectedTab: LibraryTab,
    onTabSelected: (LibraryTab) -> Unit,
    onTrackSelected: (Int) -> Unit,
    onAlbumSelected: (Long) -> Unit,
    onArtistSelected: (Long) -> Unit,
    onPlaylistSelected: (Long) -> Unit,
    onShuffleAll: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current
    val contentPadding = PaddingValues(bottom = bottomPadding)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        ChoirHeader(
            title = stringResource(R.string.library_title),
            subtitle = if (snapshot.isLoading) null else snapshot.countLabel(selectedTab),
            actions = {
                IconAction(
                    icon = ChoirIcons.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    onClick = onSearch,
                )
                if (snapshot.tracks.isNotEmpty()) {
                    IconAction(
                        icon = ChoirIcons.Shuffle,
                        contentDescription = stringResource(R.string.library_shuffle_all),
                        onClick = onShuffleAll,
                    )
                }
            },
        )

        ChoirTabs(
            tabs = LibraryTab.entries.map { stringResource(it.labelRes) },
            selectedIndex = selectedTab.ordinal,
            onSelect = { onTabSelected(LibraryTab.entries[it]) },
        )

        when {
            snapshot.isLoading -> CenteredMessage(stringResource(R.string.library_loading))
            snapshot.isEmpty -> CenteredMessage(stringResource(R.string.library_empty))
            else -> when (selectedTab) {
                LibraryTab.TRACKS -> TracksTab(snapshot, onTrackSelected, contentPadding)
                LibraryTab.ALBUMS -> AlbumsTab(snapshot, onAlbumSelected, contentPadding)
                LibraryTab.ARTISTS -> ArtistsTab(snapshot, onArtistSelected, contentPadding)
                LibraryTab.PLAYLISTS -> PlaylistsTab(snapshot, onPlaylistSelected, contentPadding)
            }
        }
    }
}

@Composable
private fun TracksTab(
    snapshot: LibrarySnapshot,
    onTrackSelected: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    // Each tab keeps its own scroll position while the screen is alive.
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(snapshot.tracks, key = { _, track -> track.id }) { index, track ->
            TrackRow(track = track, onClick = { onTrackSelected(index) })
            RowDivider()
        }
    }
}

@Composable
private fun AlbumsTab(
    snapshot: LibrarySnapshot,
    onAlbumSelected: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    if (snapshot.albums.isEmpty()) {
        CenteredMessage(stringResource(R.string.albums_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(snapshot.albums, key = { it.id }) { album ->
            AlbumRow(album = album, onClick = { onAlbumSelected(album.id) })
            RowDivider()
        }
    }
}

@Composable
private fun ArtistsTab(
    snapshot: LibrarySnapshot,
    onArtistSelected: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    if (snapshot.artists.isEmpty()) {
        CenteredMessage(stringResource(R.string.artists_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(snapshot.artists, key = { it.id }) { artist ->
            ArtistRow(
                artist = artist,
                albumsLabel = pluralStringResource(
                    R.plurals.album_count,
                    artist.albumCount,
                    artist.albumCount,
                ),
                onClick = { onArtistSelected(artist.id) },
            )
            RowDivider()
        }
    }
}

@Composable
private fun PlaylistsTab(
    snapshot: LibrarySnapshot,
    onPlaylistSelected: (Long) -> Unit,
    contentPadding: PaddingValues,
) {
    if (snapshot.playlists.isEmpty()) {
        CenteredMessage(stringResource(R.string.playlists_empty))
        return
    }

    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        items(snapshot.playlists, key = { it.id }) { playlist ->
            PlaylistRow(playlist = playlist, onClick = { onPlaylistSelected(playlist.id) })
            RowDivider()
        }
    }
}

@Composable
private fun LibrarySnapshot.countLabel(tab: LibraryTab): String = when (tab) {
    LibraryTab.TRACKS -> pluralStringResource(R.plurals.track_count, tracks.size, tracks.size)
    LibraryTab.ALBUMS -> pluralStringResource(R.plurals.album_count, albums.size, albums.size)
    LibraryTab.ARTISTS -> pluralStringResource(R.plurals.artist_count, artists.size, artists.size)
    LibraryTab.PLAYLISTS ->
        pluralStringResource(R.plurals.playlist_count, playlists.size, playlists.size)
}

private val LibraryTab.labelRes: Int
    get() = when (this) {
        LibraryTab.TRACKS -> R.string.tab_tracks
        LibraryTab.ALBUMS -> R.string.tab_albums
        LibraryTab.ARTISTS -> R.string.tab_artists
        LibraryTab.PLAYLISTS -> R.string.tab_playlists
    }
