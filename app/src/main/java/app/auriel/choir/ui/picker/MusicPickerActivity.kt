// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.picker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.auriel.choir.data.model.Track
import app.auriel.choir.ui.permission.PermissionGate
import app.auriel.choir.ui.theme.ChoirTheme
import org.koin.androidx.compose.koinViewModel

/**
 * Answers other apps' requests for an audio file — the AOSP `MusicPicker`.
 *
 * Registered for `GET_CONTENT` and `PICK` on audio MIME types, so Choir shows
 * up wherever Android asks the user to choose a song: a ringtone setting, an
 * attachment, a video editor.
 *
 * Separate from [app.auriel.choir.MainActivity] on purpose. A picker is a modal
 * errand for someone else's task: it never starts playback, never shows the mini
 * player, and returns as soon as a choice is made.
 */
class MusicPickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cancelled unless a track is actually picked, so backing out reports
        // the right thing to the caller.
        setResult(Activity.RESULT_CANCELED)

        setContent {
            ChoirTheme {
                PermissionGate {
                    val viewModel: MusicPickerViewModel = koinViewModel()
                    val state by viewModel.state.collectAsStateWithLifecycle()

                    LaunchedEffect(Unit) { viewModel.start() }

                    MusicPickerScreen(
                        state = state,
                        onQueryChanged = viewModel::onQueryChanged,
                        onCancel = { finish() },
                        onPicked = ::deliver,
                    )
                }
            }
        }
    }

    private fun deliver(track: Track) {
        val uri = track.contentUri
        val result = Intent()
            .setData(uri)
            // The caller cannot read a MediaStore URI on our say-so alone; the
            // grant flag is what actually lets them open it.
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        // ACTION_PICK callers may expect the URI in the "extra" slot too.
        if (intent?.action == Intent.ACTION_PICK) {
            result.putExtra(Intent.EXTRA_STREAM, uri)
        }

        setResult(Activity.RESULT_OK, result)
        finish()
    }
}
