// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which permissions Choir needs depends on the platform level:
 *
 *  - API 29-32: `READ_EXTERNAL_STORAGE` (scoped storage, MediaStore-backed)
 *  - API 33+:   `READ_MEDIA_AUDIO`, plus `POST_NOTIFICATIONS` for the media
 *               notification the playback service posts
 *
 * PLAN.md phase 6 adds `MANAGE_EXTERNAL_STORAGE` for full-library scanning; for
 * v0.1.0 everything comes from MediaStore, which needs neither.
 */
object Permissions {

    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** Permissions to request at startup, in the order they should be asked for. */
    val startupPermissions: Array<String>
        get() = buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    fun hasAudioAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
}
