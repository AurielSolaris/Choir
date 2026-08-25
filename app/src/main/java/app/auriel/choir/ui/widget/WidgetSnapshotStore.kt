// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

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

    /**
     * The snapshot, and every later version of it.
     *
     * A widget cannot read this once and keep it. Glance recomposes a running
     * session in place, so anything captured by value at composition time is
     * frozen for as long as that session lives — the widget would show whatever
     * was true when it was first drawn and never change again, which is exactly
     * the bug this flow exists to remove.
     *
     * Both ends are in Choir's process — the publisher writes, the Glance
     * session composes — so a preference listener is enough; nothing here has
     * to cross to the launcher.
     */
    fun snapshots(): Flow<WidgetSnapshot> = callbackFlow {
        trySend(read())

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
        // One write clears the file and puts a dozen keys back, so the listener
        // fires a dozen times for what is one change.
        .conflate()
        .distinctUntilChanged()

    private companion object {
        const val FILE = "choir_widget_snapshot"
    }
}
