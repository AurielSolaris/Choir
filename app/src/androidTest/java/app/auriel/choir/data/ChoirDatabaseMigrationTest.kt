// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migrations, actually run.
 *
 * This is the one thing a JVM test cannot do for [ChoirDatabase]. The SQL in
 * `MIGRATION_1_2` and its two successors is hand-written, and nothing checks it
 * until it executes against real SQLite: a misspelt column or a missing index
 * is a compile-time success and an upgrade-time crash on somebody's phone,
 * taking their likes and playlists with it.
 *
 * Each test opens the database *at* an old version, writes the sort of row a
 * user would have there, migrates, and reads it back. `runMigrationsAndValidate`
 * compares the migrated schema against the exported one; the assertions on top
 * of that are about the data surviving, which a schema check cannot see.
 *
 * The migrations under test are [ChoirDatabase.MIGRATIONS] — the array the app
 * installs with, not a copy of the list, so a migration added later still runs
 * through this file.
 */
@RunWith(AndroidJUnit4::class)
class ChoirDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChoirDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migration_1_to_2_keeps_the_saved_queue_and_adds_liked_songs() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO queue_entries (position, trackId) VALUES (0, 42)")
            db.execSQL("INSERT INTO queue_entries (position, trackId) VALUES (1, 43)")
            db.execSQL(
                "INSERT INTO playback_state " +
                    "(id, queueIndex, positionMs, shuffleEnabled, repeatMode) " +
                    "VALUES (0, 1, 12000, 0, 0)",
            )
        }

        val db = migrateTo(2)

        assertEquals(listOf(42L, 43L), db.queueTrackIds())
        assertEquals(1, db.scalar("SELECT queueIndex FROM playback_state WHERE id = 0"))
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM liked_tracks"))
    }

    @Test
    fun migration_2_to_3_keeps_liked_songs_and_adds_playlists() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO liked_tracks (trackId, title, artist, durationMs, likedAt) " +
                    "VALUES (7, 'Pink Moon', 'Nick Drake', 128000, 1700000000)",
            )
        }

        val db = migrateTo(3)

        assertEquals(1, db.scalar("SELECT COUNT(*) FROM liked_tracks WHERE trackId = 7"))
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM playlists"))
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM playlist_members"))
    }

    @Test
    fun migration_3_to_4_keeps_playlists_and_adds_folders() {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO playlists (id, name, createdAt, updatedAt) " +
                    "VALUES (1, 'Evening', 1700000000, 1700000000)",
            )
            db.execSQL(
                "INSERT INTO playlist_members " +
                    "(playlistId, trackId, position, title, artist, durationMs) " +
                    "VALUES (1, 7, 0, 'Pink Moon', 'Nick Drake', 128000)",
            )
        }

        val db = migrateTo(4)

        assertEquals(1, db.scalar("SELECT COUNT(*) FROM playlist_members WHERE playlistId = 1"))
        assertEquals("Evening", db.text("SELECT name FROM playlists WHERE id = 1"))
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM folder_roots"))
        assertEquals(0, db.scalar("SELECT COUNT(*) FROM folder_files"))
    }

    /**
     * The upgrade a user who installed at 0.1.0 actually gets.
     *
     * Running the migrations one at a time can pass while the chain fails, so
     * this is the case that matters: one install, three schema changes, and a
     * queue that was saved before either of the tables it now sits beside
     * existed.
     */
    @Test
    fun migration_1_to_4_in_one_upgrade_keeps_everything() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL("INSERT INTO queue_entries (position, trackId) VALUES (0, 42)")
            db.execSQL(
                "INSERT INTO playback_state " +
                    "(id, queueIndex, positionMs, shuffleEnabled, repeatMode) " +
                    "VALUES (0, 0, 500, 1, 2)",
            )
        }

        val db = migrateTo(4)

        assertEquals(listOf(42L), db.queueTrackIds())
        assertEquals(1, db.scalar("SELECT shuffleEnabled FROM playback_state WHERE id = 0"))
        assertEquals(2, db.scalar("SELECT repeatMode FROM playback_state WHERE id = 0"))
    }

    /**
     * Deleting a playlist takes its members with it.
     *
     * The cascade is declared in `MIGRATION_2_3`'s SQL rather than inferred from
     * the entity, so it is only real if SQLite was told about it — and only
     * enforced with foreign keys switched on, which Room does per connection.
     */
    @Test
    fun deleting_a_playlist_cascades_to_its_members() {
        helper.createDatabase(NAME, 3).close()
        val db = migrateTo(4)

        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL(
            "INSERT INTO playlists (id, name, createdAt, updatedAt) VALUES (1, 'Evening', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO playlist_members " +
                "(playlistId, trackId, position, title, artist, durationMs) " +
                "VALUES (1, 7, 0, 'Pink Moon', 'Nick Drake', 128000)",
        )

        db.execSQL("DELETE FROM playlists WHERE id = 1")

        assertEquals(0, db.scalar("SELECT COUNT(*) FROM playlist_members"))
    }

    /**
     * `folder_files.trackId` is unique, and has to be.
     *
     * A folder track is addressed by an id derived from its document URI, and
     * the resolver looks a track up by that id alone. Two rows sharing one would
     * make which file plays a question of row order.
     */
    @Test
    fun the_folder_file_index_survives_the_migration_that_creates_it() {
        helper.createDatabase(NAME, 3).close()
        val db = migrateTo(4)

        db.execSQL(folderFileInsert(uri = "content://tree/a", trackId = 900))
        val duplicate = runCatching {
            db.execSQL(folderFileInsert(uri = "content://tree/b", trackId = 900))
        }

        assertTrue("a second row took the same trackId", duplicate.isFailure)
    }

    private fun migrateTo(version: Int): SupportSQLiteDatabase =
        helper.runMigrationsAndValidate(NAME, version, true, *ChoirDatabase.MIGRATIONS)

    private fun folderFileInsert(uri: String, trackId: Long) =
        "INSERT INTO folder_files (documentUri, trackId, treeUri, relativePath, " +
            "displayName, mimeType, title, artist, album, durationMs, trackNumber, " +
            "year, sizeBytes) VALUES ('$uri', $trackId, 'content://tree', 'Music/', " +
            "'probe.wv', 'audio/x-wavpack', 'Probe', 'Nobody', 'None', 1000, 1, 2026, 4096)"

    private fun SupportSQLiteDatabase.scalar(sql: String): Int =
        query(sql).use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun SupportSQLiteDatabase.text(sql: String): String? =
        query(sql).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun SupportSQLiteDatabase.queueTrackIds(): List<Long> =
        query("SELECT trackId FROM queue_entries ORDER BY position").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
