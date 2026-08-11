// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.ui.components.AlbumRow
import app.auriel.choir.ui.components.ArtistRow
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.SearchField
import app.auriel.choir.ui.components.SectionLabel
import app.auriel.choir.ui.components.TrackRow
import app.auriel.choir.ui.library.SearchResults
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * Instant search across the library — the AOSP `QueryBrowserActivity`.
 *
 * Results filter as you type and are grouped by kind.
 */
@Composable
fun SearchScreen(
    results: SearchResults,
    onQueryChanged: (String) -> Unit,
    onBack: () -> Unit,
    onTrackSelected: (Int) -> Unit,
    onAlbumSelected: (Long) -> Unit,
    onArtistSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
) {
    val colors = LocalChoirColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            // The keyboard opens immediately here, and results are useless
            // underneath it.
            .imePadding(),
    ) {
        SearchField(
            query = results.query,
            hint = stringResource(R.string.search_hint),
            onQueryChanged = onQueryChanged,
            onBack = onBack,
        )

        when {
            results.query.isBlank() ->
                CenteredMessage(stringResource(R.string.search_prompt))

            results.isEmpty ->
                CenteredMessage(stringResource(R.string.search_empty, results.query.trim()))

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
            ) {
                if (results.tracks.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.section_tracks)) }
                    itemsIndexed(
                        results.tracks,
                        key = { _, track -> "track:${track.id}" },
                    ) { index, track ->
                        TrackRow(track = track, onClick = { onTrackSelected(index) })
                        RowDivider()
                    }
                }

                if (results.albums.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.section_albums)) }
                    items(results.albums, key = { "album:${it.id}" }) { album ->
                        AlbumRow(album = album, onClick = { onAlbumSelected(album.id) })
                        RowDivider()
                    }
                }

                if (results.artists.isNotEmpty()) {
                    item { SectionLabel(stringResource(R.string.section_artists)) }
                    items(results.artists, key = { "artist:${it.id}" }) { artist ->
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
        }
    }
}
