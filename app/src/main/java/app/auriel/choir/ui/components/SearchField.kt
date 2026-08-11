// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.auriel.choir.R
import app.auriel.choir.ui.ChoirIcons
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * The search bar, shared by the search screen and the picker.
 *
 * A back arrow, a bare text field and a clear key over a hairline rule — no box,
 * no fill, no placeholder chrome beyond the hint itself.
 */
@Composable
fun SearchField(
    query: String,
    hint: String,
    onQueryChanged: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
) {
    val colors = LocalChoirColors.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    if (autoFocus) {
        // Typing is the only reason to be here, so take focus at once.
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            icon = ChoirIcons.ArrowBack,
            contentDescription = stringResource(R.string.cd_back),
            onClick = onBack,
        )

        BasicTextField(
            value = query,
            onValueChange = onQueryChanged,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onBackground),
            cursorBrush = SolidColor(colors.onBackground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp)
                .focusRequester(focusRequester),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.divider,
                    )
                }
                field()
            },
        )

        if (query.isNotEmpty()) {
            IconAction(
                icon = ChoirIcons.Close,
                contentDescription = stringResource(R.string.cd_clear_search),
                onClick = { onQueryChanged("") },
            )
        }
    }

    HorizontalDivider(
        thickness = 1.dp,
        color = colors.divider,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
