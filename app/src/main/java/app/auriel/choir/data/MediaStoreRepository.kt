// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import android.content.Context
import android.database.Cursor
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.auriel.choir.core.MusicLog
import app.auriel.choir.core.MusicUtils
import app.auriel.choir.core.Permissions
import app.auriel.choir.data.model.Playlist
import app.auriel.choir.data.model.Track
import app.auriel.choir.playback.AudioFormats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the on-device audio library out of MediaStore.
 *
 * This replaces the cursor/CursorAdapter machinery the AOSP browsers used: the
 * cursor is drained into immutable [Track] values on a background dispatcher and
 * handed to the UI as a plain list, so nothing downstream has to think about
 * cursor lifetimes or `requery()`.
 */
class MediaStoreRepository(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * All music on the device, title-sorted.
     *
     * Returns an empty list rather than throwing when the read permission is
     * missing or the provider is unavailable — the caller shows an empty
     * library, which is the honest result in both cases.
     */
    suspend fun queryTracks(): List<Track> = withContext(ioDispatcher) {
        if (!Permissions.hasAudioAccess(context)) {
            MusicLog.i(TAG, "queryTracks skipped: no audio permission")
            return@withContext emptyList()
        }

        queryTracks(
            uri = COLLECTION_URI,
            projection = TRACK_PROJECTION,
            selection = SELECTION,
            sortOrder = TITLE_SORT_ORDER,
            idColumnName = MediaStore.Audio.Media._ID,
        )
    }

    /**
     * The tracks on a MediaStore playlist, in the order the playlist gives them.
     */
    @Suppress("DEPRECATION") // See queryPlaylists.
    suspend fun queryPlaylistTracks(playlistId: Long): List<Track> = withContext(ioDispatcher) {
        if (!Permissions.hasAudioAccess(context)) return@withContext emptyList()

        queryTracks(
            uri = MediaStore.Audio.Playlists.Members.getContentUri(VOLUME, playlistId),
            projection = MEMBER_PROJECTION,
            selection = null,
            sortOrder = MediaStore.Audio.Playlists.Members.PLAY_ORDER,
            // On the members table `_ID` is the membership row, not the track.
            idColumnName = MediaStore.Audio.Playlists.Members.AUDIO_ID,
        )
    }

    /**
     * Playlists MediaStore is willing to tell us about.
     *
     * Expect this to be empty on Android 11 and newer: the playlist tables were
     * deprecated and then closed off to other apps' rows. Choir's own playlists
     * live in Room instead; this query survives only as a way to import
     * playlists made before the platform closed the collection off.
     */
    // Deprecated on purpose: this *is* the deprecated collection, and reading it
    // is the only way to see playlists made before the platform closed it off.
    @Suppress("DEPRECATION")
    suspend fun queryPlaylists(): List<Playlist> = withContext(ioDispatcher) {
        if (!Permissions.hasAudioAccess(context)) return@withContext emptyList()

        val playlists = mutableListOf<Playlist>()
        try {
            context.contentResolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Playlists._ID, MediaStore.Audio.Playlists.NAME),
                null,
                null,
                "${MediaStore.Audio.Playlists.NAME} COLLATE NOCASE ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists.NAME)
                while (cursor.moveToNext()) {
                    playlists += Playlist(
                        id = cursor.getLong(idColumn),
                        name = cursor.getString(nameColumn).orEmpty().ifBlank { UNTITLED },
                    )
                }
            }
        } catch (e: Exception) {
            // Deprecated collection: several OEM builds fail this query outright
            // rather than returning nothing. An empty playlist tab is fine.
            MusicLog.i(TAG, "playlists unavailable on this platform: ${e.message}")
            return@withContext emptyList()
        }
        playlists
    }

    /**
     * Every track's storage-relative path, as `Music/Album/01 Track.mp3`.
     *
     * Only `.m3u` import and export need this. Playback never does — it goes
     * through content URIs — but a playlist file has to name its tracks in a
     * way another program can recognise, and a path is the only thing the
     * format understands.
     */
    suspend fun relativePaths(): Map<Long, String> =
        queryTracks()
            .filter { it.displayName.isNotBlank() }
            .associate { it.id to it.path }

    /** Looks up a subset of tracks by id, used when restoring a saved queue. */
    suspend fun tracksByIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val byId = queryTracks().associateBy(Track::id)
        // Preserve the caller's ordering; drop ids whose files have since gone.
        return ids.mapNotNull(byId::get)
    }

    /**
     * Emits once immediately, then again whenever the audio collection changes
     * — a new download, a deleted file, a completed media scan.
     */
    fun observeTracks(): Flow<List<Track>> = callbackFlow {
        // The observer fires on MediaStore's thread and must not block, so it
        // only pokes a conflated channel; the collector below does the querying.
        val refresh = Channel<Unit>(Channel.CONFLATED)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                MusicLog.d(TAG, "MediaStore changed: $uri")
                refresh.trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(COLLECTION_URI, true, observer)

        launch {
            for (signal in refresh) {
                send(queryTracks())
            }
        }
        refresh.trySend(Unit)

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    /**
     * Shared cursor drain. The audio table and the playlist-members table expose
     * the same track columns under the same names; only the id column and the
     * ordering differ.
     */
    private fun queryTracks(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        sortOrder: String?,
        idColumnName: String,
    ): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)
                ?.use { cursor -> cursor.drainInto(tracks, idColumnName) }
        } catch (e: SecurityException) {
            MusicLog.w(TAG, "query of $uri denied by the platform", e)
            return emptyList()
        } catch (e: IllegalArgumentException) {
            MusicLog.e(TAG, "query of $uri rejected the projection", e)
            return emptyList()
        }

        MusicLog.d(TAG, "query of $uri returned ${tracks.size} tracks")
        return tracks
    }

    private fun Cursor.drainInto(into: MutableList<Track>, idColumnName: String) {
        val idColumn = getColumnIndexOrThrow(idColumnName)
        val titleColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val artistIdColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID)
        val albumColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val trackColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        val yearColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
        val nameColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val mimeColumn = getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
        // Not thrown on: the playlist-members view mirrors the audio columns,
        // but it is the platform's deprecated table and OEM builds have been
        // known to drop one. A track with no folder simply sorts to the root.
        val pathColumn = getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)

        while (moveToNext()) {
            val displayName = getString(nameColumn).orEmpty()
            val mimeType = getString(mimeColumn).orEmpty()

            into += Track(
                id = getLong(idColumn),
                title = titleFor(
                    storedTitle = getString(titleColumn),
                    displayName = displayName,
                    mimeType = mimeType,
                    durationMs = getLong(durationColumn),
                ),
                artist = MusicUtils.tagOrFallback(getString(artistColumn), UNKNOWN_ARTIST),
                artistId = getLong(artistIdColumn),
                album = MusicUtils.tagOrFallback(getString(albumColumn), UNKNOWN_ALBUM),
                albumId = getLong(albumIdColumn),
                durationMs = getLong(durationColumn),
                // MediaStore encodes multi-disc albums as disc*1000 + track.
                trackNumber = getInt(trackColumn) % 1000,
                year = getInt(yearColumn),
                displayName = displayName,
                mimeType = mimeType,
                relativePath = folderOf(if (pathColumn >= 0) getString(pathColumn) else null),
            )
        }
    }

    /**
     * `RELATIVE_PATH` as the folder tree wants it: `Music/Nick Drake/`, with
     * one trailing slash and no leading one. MediaStore is usually already in
     * that shape, but not on every volume and not on every OEM build.
     */
    private fun folderOf(relativePath: String?): String {
        val path = relativePath.orEmpty().trim().trim('/')
        return if (path.isEmpty()) "" else "$path/"
    }

    /**
     * For a file the scanner could read, MediaStore's title came out of the
     * tags and is the right thing to show.
     *
     * For one it could not — an AIFF, a WMA — there were no tags to read, so it
     * falls back to the filename with the extension chopped off. That produces
     * three rows all called "probe" out of `probe.aiff`, `probe.wma` and
     * `probe.ac3`. Putting the extension back is the difference between a list
     * the user can act on and one they cannot.
     *
     * A missing duration is the giveaway, and a better one than the format
     * table: it means the scanner opened the file and learned nothing, whatever
     * the extension said it should have found.
     */
    private fun titleFor(
        storedTitle: String?,
        displayName: String,
        mimeType: String,
        durationMs: Long,
    ): String {
        val title = storedTitle.orEmpty()
        val scannerRead = durationMs > 0L && !AudioFormats.isScannerBlind(displayName, mimeType)
        if (title.isNotBlank() && scannerRead) return title

        val stem = displayName.substringBeforeLast('.', displayName)
        return when {
            displayName.isBlank() -> title.ifBlank { UNTITLED }
            // Only when the "title" is the bare filename, so a properly tagged
            // file in an exotic format keeps the name its author gave it.
            title.isBlank() || title == stem -> displayName
            else -> title
        }
    }

    private companion object {
        const val TAG = "MediaStoreRepository"
        const val UNTITLED = "Untitled"
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"
        const val VOLUME = "external"

        val COLLECTION_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val TRACK_COLUMNS = arrayOf(
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ARTIST_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.RELATIVE_PATH,
        )

        val TRACK_PROJECTION = arrayOf(MediaStore.Audio.Media._ID, *TRACK_COLUMNS)

        @Suppress("DEPRECATION") // See queryPlaylists.
        val MEMBER_PROJECTION = arrayOf(
            MediaStore.Audio.Playlists.Members.AUDIO_ID,
            *TRACK_COLUMNS,
        )

        /**
         * `IS_MUSIC` excludes ringtones, alarms and notification sounds, exactly
         * as the AOSP TrackBrowser did.
         *
         * There used to be a `DURATION > 0` guard here too, meant to drop the
         * empty stub rows MediaStore keeps for files it could not read. It did
         * that, and it also silently deleted every AIFF, WMA and AC-3 track from
         * the library — checking on device, the scanner records those with a
         * null duration precisely *because* it cannot parse them. The guard was
         * hiding the formats Choir most needs to show.
         *
         * Size does the same job without the collateral damage: a stub row for a
         * file that no longer exists has no bytes behind it, while an unparsable
         * album track has millions.
         */
        /** Smaller than any real song, larger than any placeholder. */
        const val MINIMUM_TRACK_BYTES = 4096

        const val SELECTION = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
            "${MediaStore.Audio.Media.SIZE} > $MINIMUM_TRACK_BYTES"

        const val TITLE_SORT_ORDER = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
    }
}
