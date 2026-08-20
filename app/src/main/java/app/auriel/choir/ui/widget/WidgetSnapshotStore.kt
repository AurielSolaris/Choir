// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.Context
import androidx.core.content.edit

/**
 * Where [WidgetSnapshot] lives between one process and the next.
 *
 * Preferences rather than Room: this is read on the launcher's schedule, at the
 * moment a widget is drawn, sometimes before anything else in the app has
 * started. A file the platform has already mapped is the cheapest thing to
 * reach for, and the snapshot is a dozen short strings.
 *
 * It is deliberately *not* the source of truth for anything. The player is, and
 * this is only the last thing it said — so a stale snapshot is a cosmetic
 * problem, corrected by the next playback event, and never something the app
 * itself reads back.
 */
class WidgetSnapshotStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): WidgetSnapshot {
        val values = preferences.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        return WidgetSnapshot.decode(values)
    }

    fun write(snapshot: WidgetSnapshot) {
        val values = snapshot.encode()
        preferences.edit {
            // Cleared first: an absent key means absent, and leaving the
            // previous track's lyric behind would be worse than showing none.
            clear()
            values.forEach { (key, value) -> putString(key, value) }
        }
    }

    private companion object {
        const val FILE = "choir_widget_snapshot"
    }
}
