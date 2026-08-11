// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.MediaStoreRepository
import app.auriel.choir.data.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reading and writing `.m3u` / `.m3u8` playlist files.
 *
 * Goes through the storage-access framework — the user picks the file, Choir
 * gets a URI for that one file and nothing else. No broad file permission is
 * requested, and none is needed.
 */
class PlaylistFiles(
    private val context: Context,
    private val mediaStore: MediaStoreRepository,
    private val playlists: PlaylistRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** What an import did, so the UI can say something true about it. */
    data class ImportResult(
        val playlistId: Long,
        val name: String,
        val imported: Int,
        val missing: Int,
    )

    /**
     * Imports a playlist file into a new playlist named after it.
     *
     * Entries whose files are not in the library are counted and dropped rather
     * than stored: a member Choir cannot resolve *now* would look identical to
     * one that has gone missing, and pretending otherwise makes the reconcile
     * pass guess about tracks that were never there.
     */
    suspend fun import(uri: Uri, library: List<Track>): ImportResult? = withContext(ioDispatcher) {
        val text = read(uri) ?: return@withContext null
        val entries = M3u.parse(text)
        if (entries.isEmpty()) return@withContext null

        val paths = mediaStore.relativePaths()
        val tracks = resolveM3u(entries, library) { paths[it.id].orEmpty() }

        val name = displayName(uri)?.substringBeforeLast('.')?.trim()?.ifBlank { null }
            ?: DEFAULT_NAME
        val playlistId = playlists.createDistinct(name)
        playlists.add(playlistId, tracks)

        MusicLog.i(TAG, "imported ${tracks.size}/${entries.size} entries into '$name'")
        ImportResult(
            playlistId = playlistId,
            name = name,
            imported = tracks.size,
            missing = entries.size - tracks.size,
        )
    }

    /** Writes a playlist out in the extended form, with `#EXTINF` metadata. */
    suspend fun export(uri: Uri, tracks: List<Track>): Boolean = withContext(ioDispatcher) {
        if (tracks.isEmpty()) return@withContext false

        val paths = mediaStore.relativePaths()
        val text = M3u.write(tracks) { paths[it.id].orEmpty() }

        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            MusicLog.w(TAG, "could not write playlist to $uri", e)
            false
        }
    }

    /**
     * Copies whatever MediaStore is still willing to hand over into Choir's own
     * playlists — a one-way door out of the platform's deprecated tables.
     *
     * Empty on Android 11 and newer, which is exactly why Choir owns playlists
     * now. It stays for the Android 10 devices where those rows still exist.
     */
    suspend fun importFromMediaStore(): Int = withContext(ioDispatcher) {
        val legacy = mediaStore.queryPlaylists()
        if (legacy.isEmpty()) return@withContext 0

        var imported = 0
        for (playlist in legacy) {
            val tracks = mediaStore.queryPlaylistTracks(playlist.id)
            if (tracks.isEmpty()) continue

            playlists.add(playlists.create(playlist.name), tracks)
            imported++
        }
        MusicLog.i(TAG, "imported $imported playlists from MediaStore")
        imported
    }

    private fun read(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        MusicLog.w(TAG, "could not read playlist from $uri", e)
        null
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val TAG = "PlaylistFiles"
        const val DEFAULT_NAME = "Imported playlist"
    }
}
