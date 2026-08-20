// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.auriel.choir.core.MusicLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** One audio file found inside a granted tree. */
data class ScannedFile(
    /** The document URI to open it with, as a string. */
    val documentUri: String,
    /** Volume-relative and slash-terminated, so it lands in the same tree as an indexed track. */
    val relativePath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** What one granted tree turned out to hold. */
data class ScannedTree(
    val treeUri: String,
    val rootPath: String,
    val rootName: String,
    val files: List<ScannedFile> = emptyList(),
    /**
     * True when the provider would not answer — the SD card was ejected, or
     * the grant was revoked in Settings. Distinguished from "no music here" so
     * the screen can say which of the two happened instead of showing an empty
     * folder and letting the user assume their files are gone.
     */
    val unavailable: Boolean = false,
)

/**
 * Walks a folder the user granted, and finds the music in it.
 *
 * This is the half of the library MediaStore cannot supply. The media scanner
 * decides what counts as audio, and it decides wrongly: a `.wv` or a `.tta` is
 * filed as `application/octet-stream` with `media_type=0`, which makes it
 * invisible to `MediaStore.Audio.Media` no matter what permission is held.
 * Walking a granted tree asks the provider what is actually there, so those
 * files exist again — and with v0.4.0's demuxers, play.
 *
 * `DocumentsContract` is used directly rather than `DocumentFile`, which issues
 * a separate query per attribute per file; one cursor per directory reads a
 * thousand-file folder in a fraction of the time.
 */
class FolderScanner(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Every audio file below [treeUri], with the folders marked `.nomedia`
     * left out.
     *
     * Never throws: an unreadable tree comes back [ScannedTree.unavailable],
     * because a folder the user granted last month and an SD card they took
     * out this morning are the same call as far as this is concerned.
     */
    suspend fun scan(treeUri: Uri): ScannedTree = withContext(ioDispatcher) {
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: IllegalArgumentException) {
            MusicLog.w(TAG, "not a tree uri: $treeUri", e)
            return@withContext ScannedTree(treeUri.toString(), "", "", unavailable = true)
        }

        val rootName = displayNameOf(treeUri, rootId) ?: rootId.substringAfterLast('/')
        val rootPath = DocumentPaths.relativePathOfTreeDocumentId(rootId)
            ?: DocumentPaths.childOf("", rootName)

        val files = mutableListOf<ScannedFile>()
        val queue = ArrayDeque(listOf(Directory(rootId, rootPath, depth = 0)))
        val visited = HashSet<String>()
        var readAnything = false

        while (queue.isNotEmpty() && files.size < MAX_FILES) {
            coroutineContext.ensureActive()

            val directory = queue.removeFirst()
            if (!visited.add(directory.documentId)) continue

            val children = childrenOf(treeUri, directory.documentId)
                ?: continue // Unreadable subfolder: skip it, keep the rest.
            readAnything = true

            // Checked before anything is kept, so a marker beside the files it
            // covers still hides them — the media scanner's own rule.
            if (children.any { !it.isDirectory && it.name.equals(DocumentPaths.NO_MEDIA, true) }) {
                MusicLog.d(TAG, "skipping ${directory.path}: .nomedia")
                continue
            }

            for (child in children) {
                if (child.isDirectory) {
                    if (directory.depth < MAX_DEPTH) {
                        queue += Directory(
                            documentId = child.documentId,
                            path = DocumentPaths.childOf(directory.path, child.name),
                            depth = directory.depth + 1,
                        )
                    }
                } else if (DocumentPaths.isAudio(child.name, child.mimeType)) {
                    files += ScannedFile(
                        documentUri = DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, child.documentId)
                            .toString(),
                        relativePath = directory.path,
                        displayName = child.name,
                        mimeType = child.mimeType,
                        sizeBytes = child.size,
                    )
                }
            }
        }

        if (files.size >= MAX_FILES) {
            MusicLog.w(TAG, "stopped at $MAX_FILES files in $treeUri")
        }
        MusicLog.d(TAG, "scanned $treeUri: ${files.size} files under $rootPath")

        ScannedTree(
            treeUri = treeUri.toString(),
            rootPath = rootPath,
            rootName = rootName,
            files = files,
            unavailable = !readAnything,
        )
    }

    private data class Directory(val documentId: String, val path: String, val depth: Int)

    private data class Child(
        val documentId: String,
        val name: String,
        val mimeType: String,
        val size: Long,
    ) {
        val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    /** One cursor for a whole directory, or `null` if the provider refused. */
    private fun childrenOf(treeUri: Uri, documentId: String): List<Child>? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = mutableListOf<Child>()
        try {
            context.contentResolver.query(uri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    children += Child(
                        documentId = id,
                        name = cursor.getString(1).orEmpty(),
                        mimeType = cursor.getString(2).orEmpty(),
                        size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                    )
                }
            } ?: return null
        } catch (e: SecurityException) {
            // The grant is gone: revoked in Settings, or lost with the volume.
            MusicLog.i(TAG, "no longer permitted to read $uri")
            return null
        } catch (e: Exception) {
            MusicLog.w(TAG, "could not list $uri", e)
            return null
        }
        return children
    }

    private fun displayNameOf(treeUri: Uri, documentId: String): String? = try {
        context.contentResolver.query(
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        MusicLog.d(TAG, "no display name for $documentId: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "FolderScanner"

        /**
         * Deep enough for any real music collection, shallow enough that a
         * provider looping a directory back on itself cannot run forever. The
         * visited set catches that too; this is the cheaper of the two guards.
         */
        const val MAX_DEPTH = 12

        /** A ceiling, not a target: the whole of a granted tree is held in memory. */
        const val MAX_FILES = 20_000

        val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
