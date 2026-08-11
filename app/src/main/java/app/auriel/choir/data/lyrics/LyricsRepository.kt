// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.fingerprint
import app.auriel.choir.data.lyrics.online.LyricsQuery
import app.auriel.choir.data.lyrics.online.OnlineLyricsSource
import app.auriel.choir.data.lyrics.tags.EmbeddedLyricsReader
import app.auriel.choir.data.lyrics.tags.readUpTo
import app.auriel.choir.data.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds a track's words.
 *
 * Three places are searched, in this order of authority:
 *
 *  1. **A `.lrc` sidecar** next to the audio file. Someone put it there on
 *     purpose, and it is usually synced.
 *  2. **The file's own tags** — ID3v2 `USLT`/`SYLT`/`TXXX`, or a Vorbis
 *     `LYRICS` field.
 *  3. **A service the user opted in to**, if they did. Off by default; see
 *     [OnlineLyricsSource] for every condition that has to hold first.
 *
 * A synced source always beats an unsynced one regardless of where it came
 * from, so an embedded `SYLT` frame wins over a plain-text sidecar. The local
 * sources are tried first and the network last — not only because a file on the
 * device is more trustworthy than a guess from a database, but because the
 * common case should never leave the device at all.
 */
class LyricsRepository(
    private val context: Context,
    private val online: OnlineLyricsSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Flipping back and forth between two tracks should not re-parse either.
    private val cache = LruCache<Long, Result>(CACHE_ENTRIES)

    /** Either the words, or a definite answer that there are none. */
    class Result(val lyrics: Lyrics?)

    suspend fun forTrack(track: Track): Lyrics? = withContext(ioDispatcher) {
        cache.get(track.id)?.let { return@withContext it.lyrics }

        val local = sidecar(track).pickBetter(::embedded, track)
        // The network is asked only when the device has nothing timed to offer:
        // a synced lyric already on disk is better than anything a lookup could
        // return, and not asking is always the cheaper answer.
        val found = if (local != null && local.isSynced) local else local.pickBetter(::fetched, track)

        cache.put(track.id, Result(found))
        found
    }

    /**
     * Keeps [this] unless it is unsynced and the fallback turns out to be
     * timed, in which case the timed one wins.
     */
    private inline fun Lyrics?.pickBetter(fallback: (Track) -> Lyrics?, track: Track): Lyrics? {
        if (this != null && isSynced) return this
        val other = fallback(track)
        return when {
            other == null -> this
            this == null -> other
            other.isSynced -> other
            else -> this
        }
    }

    // --- Sidecars ------------------------------------------------------------

    /**
     * Looks for `<track file name>.lrc` in the same folder.
     *
     * Resolved through MediaStore rather than by opening a path: on scoped
     * storage the `DATA` column is still reported but the file behind it cannot
     * be opened, so the folder is matched on `RELATIVE_PATH` and the sidecar
     * fetched by its own content URI. A `.lrc` file only turns up here if the
     * media scanner has indexed it, which it does for shared storage.
     */
    private fun sidecar(track: Track): Lyrics? {
        val location = locate(track) ?: return null
        val stem = location.displayName.substringBeforeLast('.', location.displayName)

        for (extension in SIDECAR_EXTENSIONS) {
            val uri = findFile(location.relativePath, "$stem.$extension") ?: continue
            val text = read(uri) ?: continue

            LrcParser.parse(text, LyricsSource.SIDECAR)?.let {
                MusicLog.d(TAG, "sidecar $stem.$extension for ${track.id}, synced=${it.isSynced}")
                return it
            }
        }
        return null
    }

    private class Location(val relativePath: String, val displayName: String)

    private fun locate(track: Track): Location? = query(
        uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection = arrayOf(
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
        ),
        selection = "${MediaStore.Audio.Media._ID} = ?",
        args = arrayOf(track.id.toString()),
    ) { cursor ->
        val relativePath = cursor.getString(0).orEmpty()
        val displayName = cursor.getString(1).orEmpty()
        if (relativePath.isBlank() || displayName.isBlank()) null
        else Location(relativePath, displayName)
    }

    private fun findFile(relativePath: String, displayName: String): Uri? = query(
        // The Files collection, not Audio: a .lrc is not media.
        uri = MediaStore.Files.getContentUri(VOLUME),
        projection = arrayOf(MediaStore.Files.FileColumns._ID),
        selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND " +
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
        args = arrayOf(relativePath, displayName),
    ) { cursor ->
        android.content.ContentUris.withAppendedId(
            MediaStore.Files.getContentUri(VOLUME),
            cursor.getLong(0),
        )
    }

    private fun <T> query(
        uri: Uri,
        projection: Array<String>,
        selection: String,
        args: Array<String>,
        read: (android.database.Cursor) -> T?,
    ): T? = try {
        context.contentResolver.query(uri, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }
    } catch (e: Exception) {
        // A denied or unavailable provider means no lyrics, not a broken screen.
        MusicLog.d(TAG, "lyrics lookup failed: ${e.message}")
        null
    }

    private fun read(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Decoded as UTF-8 with replacement rather than strictly: plenty of
            // .lrc files in the wild are in a legacy codepage, and a mangled
            // accent is better than no lyric.
            stream.readUpTo(MAX_SIDECAR_BYTES).toString(Charsets.UTF_8)
        }
    } catch (e: Exception) {
        MusicLog.d(TAG, "could not read $uri: ${e.message}")
        null
    }

    // --- Embedded ------------------------------------------------------------

    private fun embedded(track: Track): Lyrics? {
        val raw = try {
            context.contentResolver.openInputStream(track.contentUri)
                ?.use(EmbeddedLyricsReader::read)
        } catch (e: Exception) {
            MusicLog.d(TAG, "could not read tags of ${track.id}: ${e.message}")
            null
        } ?: return null

        return when (raw) {
            is RawLyrics.Timed ->
                Lyrics(raw.lines, isSynced = true, source = LyricsSource.EMBEDDED)

            // Taggers routinely store a whole LRC document in a text frame, so
            // this gets one more look before being called prose.
            is RawLyrics.Text -> LrcParser.parse(raw.value, LyricsSource.EMBEDDED)
        }
    }

    // --- Online --------------------------------------------------------------

    private fun fetched(track: Track): Lyrics? {
        val document = online.fetch(
            query = LyricsQuery(
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs,
            ),
            fingerprint = track.fingerprint(),
        ) ?: return null

        return LrcParser.parse(document, LyricsSource.ONLINE)
    }

    private companion object {
        const val TAG = "LyricsRepository"
        const val VOLUME = "external"
        const val CACHE_ENTRIES = 24
        const val MAX_SIDECAR_BYTES = 1024 * 1024

        /** `.lrc` is the timed one; `.txt` is a plain lyric sheet. */
        val SIDECAR_EXTENSIONS = listOf("lrc", "txt")
    }
}
