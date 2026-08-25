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
 * **A local source always wins.** If the file has words — in a sidecar or in
 * its own tags — the network is never asked, even when what is on the device is
 * plain text and a service might have offered a timed version of the same song.
 * Someone put those words next to that file, or in it; a lookup keyed on a
 * title and an artist is a guess about which recording this is, and a guess
 * does not get to overrule the thing itself. The practical half of the same
 * argument: the common case then never leaves the device at all.
 *
 * Between the two *local* sources, timed beats untimed — an embedded `SYLT`
 * frame wins over a plain-text sidecar, and a timed `.lrc` wins over an
 * untimed tag. Both are the file's own words, so there is nothing to defer to
 * and the more useful one is simply better.
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

        // Sidecar and tags first, and the better of the two wins. The tags are
        // only read when they could still change the answer: parsing them means
        // opening the audio file itself, and a synced sidecar has already won.
        val sidecar = sidecar(track)
        val local = if (sidecar != null && sidecar.isSynced) {
            sidecar
        } else {
            betterOf(sidecar, embedded(track))
        }

        // The network is asked only when the file itself had nothing at all.
        // Not "nothing timed": an untimed lyric on disk still beats a synced
        // one from a lookup, because the lookup is a guess about which
        // recording this is and the file is not.
        val found = local ?: fetched(track)

        cache.put(track.id, Result(found))
        found
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

/**
 * The better of two lyrics from equally trustworthy places.
 *
 * Timed beats untimed; anything beats nothing; [first] keeps the tie. Only ever
 * called with two *local* sources — a sidecar and a tag — because the choice
 * between the file and the network is not this question. That one is decided by
 * where the words came from rather than by what they contain, and is made in
 * [LyricsRepository.forTrack].
 *
 * Top-level and internal so it can be tested without a Context, which is the
 * only reason it is not a private method.
 */
internal fun betterOf(first: Lyrics?, second: Lyrics?): Lyrics? = when {
    first == null -> second
    second == null -> first
    first.isSynced -> first
    second.isSynced -> second
    else -> first
}
