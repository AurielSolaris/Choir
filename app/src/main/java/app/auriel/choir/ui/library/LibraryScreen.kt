// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.data.LibrarySnapshot
import app.auriel.choir.data.playlist.PlaylistSummary
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.components.AlbumRow
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.ArtistRow
import app.auriel.choir.ui.components.ChoirHeader
import app.auriel.choir.ui.components.ChoirTabs
import app.auriel.choir.ui.components.IconAction
import app.auriel.choir.ui.components.LikeState
import app.auriel.choir.ui.components.LikedSongsRow
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
 * Choir is built around, and it survives the coming restyle unchanged.
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
    onLikedSelected: () -> Unit,
    onShuffleAll: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onTrackLongPress: (Int) -> Unit,
    onNewPlaylist: () -> Unit,
    onImportFile: () -> Unit,
    onImportLegacy: () -> Unit,
    playlists: List<PlaylistSummary>,
    legacyPlaylistCount: Int,
    likes: LikeState,
    likedCount: Int,
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
            subtitle = if (snapshot.isLoading) {
                null
            } else {
                countLabel(selectedTab, snapshot, playlists.size)
            },
            actions = {
                // The playlists tab trades shuffle-all, which means nothing
                // there, for the one action it does need.
                if (selectedTab == LibraryTab.PLAYLISTS) {
                    IconAction(
                        icon = ChoirIcons.Add,
                        contentDescription = stringResource(R.string.cd_new_playlist),
                        onClick = onNewPlaylist,
                    )
                } else if (snapshot.tracks.isNotEmpty()) {
                    IconAction(
                        icon = ChoirIcons.Shuffle,
                        contentDescription = stringResource(R.string.library_shuffle_all),
                        onClick = onShuffleAll,
                    )
                }
                IconAction(
                    icon = ChoirIcons.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    onClick = onSearch,
                )
                IconAction(
                    icon = ChoirIcons.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    onClick = onSettings,
                )
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
                LibraryTab.TRACKS ->
                    TracksTab(snapshot, onTrackSelected, onTrackLongPress, likes, contentPadding)

                LibraryTab.ALBUMS -> AlbumsTab(snapshot, onAlbumSelected, contentPadding)
                LibraryTab.ARTISTS -> ArtistsTab(snapshot, onArtistSelected, contentPadding)
                LibraryTab.PLAYLISTS -> PlaylistsTab(
                    playlists = playlists,
                    likedCount = likedCount,
                    legacyCount = legacyPlaylistCount,
                    onLikedSelected = onLikedSelected,
                    onPlaylistSelected = onPlaylistSelected,
                    onImportFile = onImportFile,
                    onImportLegacy = onImportLegacy,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

@Composable
private fun TracksTab(
    snapshot: LibrarySnapshot,
    onTrackSelected: (Int) -> Unit,
    onTrackLongPress: (Int) -> Unit,
    likes: LikeState,
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
            TrackRow(
                track = track,
                onClick = { onTrackSelected(index) },
                onLongClick = { onTrackLongPress(index) },
                likes = likes,
            )
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

/**
 * Liked Songs, then Choir's own playlists, then the ways to get more of them.
 *
 * There is no empty state here any more, and no apology about MediaStore: the
 * platform's playlist tables are gone from this screen entirely, so the tab
 * always has at least Liked Songs and a way to make something new.
 */
@Composable
private fun PlaylistsTab(
    playlists: List<PlaylistSummary>,
    likedCount: Int,
    legacyCount: Int,
    onLikedSelected: () -> Unit,
    onPlaylistSelected: (Long) -> Unit,
    onImportFile: () -> Unit,
    onImportLegacy: () -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        item(key = "liked") {
            LikedSongsRow(
                countLabel = if (likedCount == 0) {
                    stringResource(R.string.liked_songs_none)
                } else {
                    pluralStringResource(R.plurals.track_count, likedCount, likedCount)
                },
                onClick = onLikedSelected,
            )
            RowDivider()
        }

        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                name = playlist.name,
                countLabel = pluralStringResource(
                    R.plurals.track_count,
                    playlist.trackCount,
                    playlist.trackCount,
                ),
                onClick = { onPlaylistSelected(playlist.id) },
            )
            RowDivider()
        }

        item(key = "import-file") {
            ActionRow(
                icon = ChoirIcons.Import,
                label = stringResource(R.string.playlist_import),
                onClick = onImportFile,
            )
        }

        // Only on the devices that still have any — Android 10 and older.
        if (legacyCount > 0) {
            item(key = "import-legacy") {
                ActionRow(
                    icon = ChoirIcons.Import,
                    label = stringResource(R.string.playlist_import_legacy, legacyCount),
                    onClick = onImportLegacy,
                )
            }
        }
    }
}

/** A row that does something rather than opening something. */
@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalChoirColors.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
        )
    }
}

@Composable
private fun countLabel(
    tab: LibraryTab,
    snapshot: LibrarySnapshot,
    playlistCount: Int,
): String = when (tab) {
    LibraryTab.TRACKS ->
        pluralStringResource(R.plurals.track_count, snapshot.tracks.size, snapshot.tracks.size)

    LibraryTab.ALBUMS ->
        pluralStringResource(R.plurals.album_count, snapshot.albums.size, snapshot.albums.size)

    LibraryTab.ARTISTS ->
        pluralStringResource(R.plurals.artist_count, snapshot.artists.size, snapshot.artists.size)

    // Liked Songs is always there, so the tab never shows "0 playlists".
    LibraryTab.PLAYLISTS -> (playlistCount + 1).let {
        pluralStringResource(R.plurals.playlist_count, it, it)
    }
}

private val LibraryTab.labelRes: Int
    get() = when (this) {
        LibraryTab.TRACKS -> R.string.tab_tracks
        LibraryTab.ALBUMS -> R.string.tab_albums
        LibraryTab.ARTISTS -> R.string.tab_artists
        LibraryTab.PLAYLISTS -> R.string.tab_playlists
    }
