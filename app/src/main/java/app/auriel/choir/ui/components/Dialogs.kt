// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import app.auriel.choir.ui.theme.LocalChoirColors

/**
 * A dialog that asks for one line of text — naming a playlist, renaming one.
 *
 * Opens with the keyboard up and the existing text selected, so renaming is one
 * gesture rather than a tap, a long press and a drag.
 */
@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String = "",
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChoirColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var value by remember {
        mutableStateOf(TextFieldValue(initialValue, TextRange(0, initialValue.length)))
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    val submit = {
        val trimmed = value.text.trim()
        if (trimmed.isNotEmpty()) onConfirm(trimmed)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.onSurface,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    cursorColor = colors.onSurface,
                    focusedIndicatorColor = colors.onSurface,
                    unfocusedIndicatorColor = colors.divider,
                ),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = value.text.isNotBlank()) {
                Text(text = confirmLabel, color = colors.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = cancelLabel, color = colors.muted)
            }
        },
    )
}

/**
 * One line of feedback that gets out of the way by itself.
 *
 * The platform toast rather than a snackbar: a snackbar needs a Scaffold and a
 * host that would sit under the mini player, and nothing here is worth undoing.
 */
@Composable
fun Toast(message: String, onShown: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(message) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        onShown()
    }
}

/** A yes-or-no dialog for the one action that cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalChoirColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted,
            )
        },
        titleContentColor = colors.onSurface,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = colors.onSurface)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = cancelLabel, color = colors.muted)
            }
        },
    )
}
