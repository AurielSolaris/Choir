// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.documentTrackId
import app.auriel.choir.data.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A granted tree as the folder screen lists it. */
data class FolderRoot(
    val treeUri: String,
    val name: String,
    val trackCount: Int = 0,
    /** The provider would not answer: card ejected, or the grant revoked. */
    val unavailable: Boolean = false,
)

/**
 * The folders the user granted, and the music inside them.
 *
 * Choir asks for no file-system permission — not `MANAGE_EXTERNAL_STORAGE`, not
 * even legacy storage. A folder is reached only because the user picked it in
 * the system document picker and the platform handed over a grant for that
 * subtree and nothing else. Asking for the whole file system, to find the
 * handful of files the media scanner mis-typed, is the kind of permission a
 * music player should never need.
 *
 * What was found is kept in Room and served from there. Scanning is not free —
 * one cursor per directory, and one file opened per new track to read its tags
 * — so the tree survives a restart and a rescan re-reads only what changed.
 */
class FolderRepository(
    private val context: Context,
    private val dao: FoldersDao,
    private val scanner: FolderScanner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** One scan at a time; two would fight over the same rows. */
    private val scanLock = Mutex()

    /**
     * Trees the provider would not answer for, as of the last scan. Held rather
     * than inferred from an empty file list, because a granted folder with no
     * music in it is a perfectly ordinary thing and an ejected SD card is not.
     */
    private var unreachable: Set<String> = emptySet()

    private val _roots = MutableStateFlow<List<FolderRoot>>(emptyList())

    /** The granted trees, as last scanned. */
    val roots: StateFlow<List<FolderRoot>> = _roots.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /**
     * Every audio file found in a granted folder, served from the database so
     * it is there the instant the app opens rather than after a scan.
     */
    fun observeFiles(): Flow<List<Track>> =
        dao.observeFiles().map { files -> files.map(FolderFileEntity::toTrack) }

    /**
     * Remembers a folder the user picked, and takes a grant that survives a
     * reboot.
     *
     * @return the folder's name, or `null` if it could not be taken — a URI
     *   that did not come from `OPEN_DOCUMENT_TREE`, or a tree that would not
     *   open once it had been granted.
     */
    suspend fun add(treeUri: Uri): String? = withContext(ioDispatcher) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            MusicLog.w(TAG, "could not persist access to $treeUri", e)
            return@withContext null
        }

        val scanned = scanLock.withLock { store(scanner.scan(treeUri)) }
        if (scanned.unavailable) {
            MusicLog.w(TAG, "granted folder $treeUri could not be read")
            return@withContext null
        }

        val name = scanned.rootName.ifBlank { treeUri.lastPathSegment.orEmpty() }
        dao.insertRoot(
            FolderRootEntity(
                treeUri = treeUri.toString(),
                name = name,
                addedAt = System.currentTimeMillis(),
            ),
        )
        publishRoots()
        MusicLog.i(TAG, "added folder $name: ${scanned.files.size} files")
        name
    }

    /** Forgets a folder, and hands the grant back to the platform. */
    suspend fun remove(treeUri: String) = withContext(ioDispatcher) {
        dao.removeRootAndFiles(treeUri)
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Already gone — revoked in Settings, or the volume unmounted.
            MusicLog.d(TAG, "no grant to release for $treeUri")
        }
        publishRoots()
    }

    /**
     * Rereads every granted folder.
     *
     * There is no watching for changes *inside* a tree. `DocumentsProvider`
     * exposes no reliable change notification for a subtree, and polling a
     * folder of thousands of files would cost more than it is worth. So this is
     * called when the library loads and from the Folders screen, where the user
     * knows they have just copied something in.
     */
    suspend fun refresh() = withContext(ioDispatcher) {
        scanLock.withLock {
            val roots = dao.roots()
            if (roots.isEmpty()) {
                publishRoots()
                return@withLock
            }

            _isScanning.value = true
            try {
                unreachable = roots
                    .map { root -> store(scanner.scan(Uri.parse(root.treeUri)), root.name) }
                    .filter(ScannedTree::unavailable)
                    .mapTo(mutableSetOf(), ScannedTree::treeUri)
            } finally {
                _isScanning.value = false
            }
            publishRoots()
        }
    }

    /**
     * Writes what a scan found, reusing the tags already read for any file that
     * has not changed size since last time.
     *
     * A tree that would not open is left alone rather than emptied. An ejected
     * SD card must not delete the record of what is on it — the folder comes
     * back with the card, and in the meantime the screen can say so.
     */
    private suspend fun store(scanned: ScannedTree, fallbackName: String = ""): ScannedTree {
        val named = if (scanned.rootName.isBlank() || scanned.unavailable) {
            scanned.copy(rootName = fallbackName)
        } else {
            scanned
        }
        if (named.unavailable) return named

        val known = dao.filesIn(named.treeUri).associateBy(FolderFileEntity::documentUri)
        val rows = named.files.map { file ->
            val existing = known[file.documentUri]
            val tags = if (existing != null && existing.sizeBytes == file.sizeBytes) {
                existing.tags()
            } else {
                readTags(Uri.parse(file.documentUri))
            }
            file.toEntity(treeUri = named.treeUri, tags = tags)
        }
        dao.replaceFilesIn(named.treeUri, rows)
        return named
    }

    /** Recomputes the list the folder screen shows, from what is in the database. */
    private suspend fun publishRoots() {
        _roots.value = dao.roots().map { root ->
            FolderRoot(
                treeUri = root.treeUri,
                name = root.name,
                trackCount = dao.filesIn(root.treeUri).size,
                unavailable = root.treeUri in unreachable,
            )
        }
    }

    /**
     * Reads what the platform can tell us about a file, which for the formats
     * that brought us here is nothing at all.
     *
     * `MediaMetadataRetriever` is the same parser the media scanner uses, so a
     * WavPack file it refused to index is a file this cannot read either. That
     * is not a reason to skip the call — an unindexed `.flac` in a `.nomedia`
     * folder reads perfectly — only a reason to expect [FolderTags.NONE] and
     * fall back to the filename without treating it as an error.
     */
    private fun readTags(uri: Uri): FolderTags {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            FolderTags(
                title = retriever.string(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.string(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    .ifBlank { retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = retriever.string(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationMs = retriever.string(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    .toLongOrNull() ?: 0L,
                // Written as "3" by some taggers and "3/12" by others.
                trackNumber = retriever.string(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                    .substringBefore('/')
                    .toIntOrNull() ?: 0,
                year = retriever.string(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    .take(4)
                    .toIntOrNull() ?: 0,
            )
        } catch (e: Exception) {
            // Expected, and the reason folder browsing exists: this is what the
            // platform does with every format it has no parser for.
            MusicLog.d(TAG, "no readable tags in $uri: ${e.message}")
            FolderTags.NONE
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun MediaMetadataRetriever.string(key: Int): String =
        runCatching { extractMetadata(key) }.getOrNull().orEmpty().trim()

    private companion object {
        const val TAG = "FolderRepository"
    }
}

/** The tags already stored for a file, so an unchanged one is not reopened. */
private fun FolderFileEntity.tags(): FolderTags = FolderTags(
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    trackNumber = trackNumber,
    year = year,
)

private fun ScannedFile.toEntity(treeUri: String, tags: FolderTags): FolderFileEntity =
    FolderFileEntity(
        documentUri = documentUri,
        trackId = documentTrackId(documentUri),
        treeUri = treeUri,
        relativePath = relativePath,
        displayName = displayName,
        mimeType = mimeType,
        title = tags.title,
        artist = tags.artist,
        album = tags.album,
        durationMs = tags.durationMs,
        trackNumber = tags.trackNumber,
        year = tags.year,
        sizeBytes = sizeBytes,
    )
