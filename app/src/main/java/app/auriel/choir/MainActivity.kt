// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.auriel.choir.playback.PlaybackConnection
import app.auriel.choir.ui.ChoirApp
import org.koin.android.ext.android.inject

/**
 * Choir's only activity. It hosts the Compose tree and owns the window on the
 * playback service: the [MediaController][androidx.media3.session.MediaController]
 * is connected while the UI is visible and let go when it is not — playback
 * itself lives in the service and is unaffected.
 */
class MainActivity : ComponentActivity() {

    private val playback: PlaybackConnection by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ChoirApp() }
    }

    override fun onStart() {
        super.onStart()
        playback.connect()
    }

    override fun onStop() {
        playback.release()
        super.onStop()
    }
}
