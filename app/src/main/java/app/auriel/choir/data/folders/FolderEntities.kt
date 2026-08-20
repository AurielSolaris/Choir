// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One folder tree the user granted Choir access to.
 *
 * The row is a bookmark, not a copy: the actual permission lives in the
 * platform's persisted-URI grants, and this table only remembers which trees to
 * ask about at startup. Both must be released together when a folder is
 * removed, which is why [FolderRepository] owns the pair rather than the DAO.
 *
 * [name] is the folder's display name as the provider gave it, kept so the
 * screen can list a folder that is currently unreachable — an ejected SD card —
 * by name instead of by an opaque URI.
 */
@Entity(tableName = "folder_roots")
data class FolderRootEntity(
    @PrimaryKey val treeUri: String,
    val name: String,
    val addedAt: Long,
)

/**
 * One audio file found inside a granted tree.
 *
 * This is the single place Choir keeps a copy of anything the library holds,
 * and it is worth being clear about why. Everywhere else, MediaStore is the
 * truth and the database stores only references back to it. These files are the
 * ones MediaStore refuses to have an opinion about — a `.wv` is not audio as
 * far as the platform is concerned — so there is no truth to defer to. Choir's
 * own scan is all there is.
 *
 * Keeping it also means the tags are read once rather than on every launch:
 * learning a file's title means opening it, and a granted folder can hold
 * thousands. A rescan re-reads only what it has not seen before.
 */
@Entity(
    tableName = "folder_files",
    indices = [Index("treeUri"), Index(value = ["trackId"], unique = true)],
)
data class FolderFileEntity(
    @PrimaryKey val documentUri: String,
    /** Derived from [documentUri]; see [app.auriel.choir.data.documentTrackId]. */
    val trackId: Long,
    /** Which grant this file was found under, so removing a folder removes its files. */
    val treeUri: String,
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    /** Changes when the file is re-tagged or replaced, which is when to read it again. */
    val sizeBytes: Long,
)

@Dao
interface FoldersDao {

    @Query("SELECT * FROM folder_roots ORDER BY addedAt ASC")
    fun observeRoots(): Flow<List<FolderRootEntity>>

    @Query("SELECT * FROM folder_roots ORDER BY addedAt ASC")
    suspend fun roots(): List<FolderRootEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoot(root: FolderRootEntity)

    @Query("DELETE FROM folder_roots WHERE treeUri = :treeUri")
    suspend fun deleteRoot(treeUri: String)

    @Query("SELECT * FROM folder_files")
    fun observeFiles(): Flow<List<FolderFileEntity>>

    @Query("SELECT * FROM folder_files WHERE treeUri = :treeUri")
    suspend fun filesIn(treeUri: String): List<FolderFileEntity>

    /** Resolving a saved queue, which happens in the service with no UI awake. */
    @Query("SELECT * FROM folder_files WHERE trackId IN (:trackIds)")
    suspend fun filesByTrackId(trackIds: List<Long>): List<FolderFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<FolderFileEntity>)

    @Query("DELETE FROM folder_files WHERE treeUri = :treeUri")
    suspend fun deleteFilesIn(treeUri: String)

    /**
     * Replaces everything known about one tree, in one transaction.
     *
     * All at once because a half-written folder is worse than a stale one: a
     * list that briefly loses the track someone is looking at, or a saved queue
     * restored against a table mid-rewrite, are both visible to the user.
     */
    @Transaction
    suspend fun replaceFilesIn(treeUri: String, files: List<FolderFileEntity>) {
        deleteFilesIn(treeUri)
        if (files.isNotEmpty()) insertFiles(files)
    }

    @Transaction
    suspend fun removeRootAndFiles(treeUri: String) {
        deleteFilesIn(treeUri)
        deleteRoot(treeUri)
    }
}
