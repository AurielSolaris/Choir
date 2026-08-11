// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.auriel.choir.data.queue.PlaybackStateEntity
import app.auriel.choir.data.queue.QueueDao
import app.auriel.choir.data.queue.QueueEntryEntity

/**
 * Choir's local database.
 *
 * v0.1.0 stores only the play queue. Liked songs (PLAN.md phase 3) and custom
 * playlists (phase 4) land here later; the library itself is never mirrored —
 * MediaStore stays the single source of truth for what audio exists.
 */
@Database(
    entities = [QueueEntryEntity::class, PlaybackStateEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChoirDatabase : RoomDatabase() {

    abstract fun queueDao(): QueueDao

    companion object {
        private const val NAME = "choir.db"

        fun build(context: Context): ChoirDatabase =
            Room.databaseBuilder(context.applicationContext, ChoirDatabase::class.java, NAME)
                // The queue is a convenience, not user data worth a migration
                // path this early. Later phases hold real content and will
                // migrate properly.
                .fallbackToDestructiveMigration()
                .build()
    }
}
