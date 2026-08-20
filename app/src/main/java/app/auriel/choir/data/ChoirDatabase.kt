// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.auriel.choir.data.folders.FolderFileEntity
import app.auriel.choir.data.folders.FolderRootEntity
import app.auriel.choir.data.folders.FoldersDao
import app.auriel.choir.data.likes.LikedTrackEntity
import app.auriel.choir.data.likes.LikesDao
import app.auriel.choir.data.playlist.PlaylistDao
import app.auriel.choir.data.playlist.PlaylistEntity
import app.auriel.choir.data.playlist.PlaylistMemberEntity
import app.auriel.choir.data.queue.PlaybackStateEntity
import app.auriel.choir.data.queue.QueueDao
import app.auriel.choir.data.queue.QueueEntryEntity

/**
 * Choir's local database.
 *
 * Holds the play queue, the liked-songs list and the playlists. The library
 * itself is never mirrored — MediaStore stays the single source of truth for
 * what audio exists, and every row here is a reference back to it.
 */
@Database(
    entities = [
        QueueEntryEntity::class,
        PlaybackStateEntity::class,
        LikedTrackEntity::class,
        PlaylistEntity::class,
        PlaylistMemberEntity::class,
        FolderRootEntity::class,
        FolderFileEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class ChoirDatabase : RoomDatabase() {

    abstract fun queueDao(): QueueDao

    abstract fun likesDao(): LikesDao

    abstract fun playlistDao(): PlaylistDao

    abstract fun foldersDao(): FoldersDao

    companion object {
        private const val NAME = "choir.db"

        /** v0.3.0 added liked songs. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `liked_tracks` (" +
                        "`trackId` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`likedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`trackId`))",
                )
            }
        }

        /** v0.3.0 added Choir's own playlists. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlists` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `playlist_members` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlistId` INTEGER NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`playlistId`) REFERENCES `playlists`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_playlist_members_playlistId` " +
                        "ON `playlist_members` (`playlistId`)",
                )
            }
        }

        /**
         * v0.4.0 added folder browsing: the trees the user granted, and what
         * was found inside them.
         *
         * `folder_files` is the one table that holds library data rather than
         * references to it — see [FolderFileEntity] for why there is nothing to
         * refer to. Dropping it would only cost a rescan.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_roots` (" +
                        "`treeUri` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`treeUri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_files` (" +
                        "`documentUri` TEXT NOT NULL, " +
                        "`trackId` INTEGER NOT NULL, " +
                        "`treeUri` TEXT NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`trackNumber` INTEGER NOT NULL, " +
                        "`year` INTEGER NOT NULL, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`documentUri`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_folder_files_treeUri` " +
                        "ON `folder_files` (`treeUri`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_folder_files_trackId` " +
                        "ON `folder_files` (`trackId`)",
                )
            }
        }

        fun build(context: Context): ChoirDatabase =
            Room.databaseBuilder(context.applicationContext, ChoirDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                // Likes are user data now, so upgrades migrate properly rather
                // than starting over. A *downgrade* only happens when someone
                // sideloads backwards, and there is no schema to migrate to.
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
