// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.picker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.auriel.choir.R
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.components.CenteredMessage
import app.auriel.choir.ui.components.RowDivider
import app.auriel.choir.ui.components.SearchField
import app.auriel.choir.ui.components.TrackRow
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The audio picker another app sees — Choir's port of AOSP's `MusicPicker`.
 *
 * One filterable list and nothing else: no tabs, no playback, no mini player.
 * Tapping a row answers the caller's intent and closes.
 */
@Composable
fun MusicPickerScreen(
    state: PickerUiState,
    onQueryChanged: (String) -> Unit,
    onCancel: () -> Unit,
    onPicked: (Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalChoirColors.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding(),
    ) {
        SearchField(
            query = state.query,
            hint = stringResource(R.string.picker_hint),
            onQueryChanged = onQueryChanged,
            onBack = onCancel,
            // The list matters more than the field here: a picker usually opens
            // to browse, not to type.
            autoFocus = false,
        )

        when {
            state.isLoading -> CenteredMessage(stringResource(R.string.library_loading))
            state.tracks.isEmpty() && state.query.isNotBlank() ->
                CenteredMessage(stringResource(R.string.search_empty, state.query.trim()))
            state.tracks.isEmpty() -> CenteredMessage(stringResource(R.string.library_empty))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding(),
                ),
            ) {
                items(state.tracks, key = { it.id }) { track ->
                    TrackRow(track = track, onClick = { onPicked(track) })
                    RowDivider()
                }
            }
        }
    }
}
