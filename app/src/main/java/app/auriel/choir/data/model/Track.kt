// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * Where a track's bytes are, and therefore how to open them.
 *
 * Until v0.4.0 there was only one answer and it was implicit in the id. Folder
 * browsing added a second: the media scanner refuses to index `.wv` and `.tta`
 * as audio at all — it types them `application/octet-stream` with
 * `media_type=0` — so the only way to reach those files is a folder the user
 * granted, and a document URI to open it with.
 */
sealed interface TrackSource {

    /** A row in `MediaStore.Audio.Media`, addressed by its id. */
    data object Indexed : TrackSource

    /**
     * A file inside a folder tree the user granted, addressed by its document
     * URI. Held as a [String] rather than a [Uri] so that a [Track] stays a
     * plain value: constructible, comparable and testable without the Android
     * framework loaded.
     */
    data class Folder(val documentUri: String) : TrackSource
}

/**
 * One audio file, as MediaStore knows it — or, since v0.4.0, as a granted
 * folder does.
 *
 * Deliberately holds no `DATA` path: on scoped storage (minSdk 29) the raw file
 * path is unreliable and often unreadable. Everything — playback, artwork —
 * goes through the URI [source] resolves to.
 */
data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val artistId: Long,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val trackNumber: Int,
    val year: Int,
    /**
     * The file's own name, `probe.wv`. Carried because it is the only reliable
     * clue to the format for the files MediaStore's scanner could not read —
     * those arrive typed as `application/octet-stream`.
     */
    val displayName: String = "",
    val mimeType: String = "",
    /**
     * The folder the file sits in, relative to its storage volume and with a
     * trailing slash: `Music/Nick Drake/`. MediaStore keeps this per row, so it
     * costs one more column rather than a second query, and it is what the
     * folder tree is built out of. Empty when the volume would not say.
     */
    val relativePath: String = "",
    val source: TrackSource = TrackSource.Indexed,
) {
    /**
     * False when MediaStore could not work out how long the track is, which it
     * writes as a null duration for every format it cannot parse. Playing the
     * file may still work; the seek bar just has nothing to scale to until the
     * decoder reports a length of its own.
     */
    val hasKnownDuration: Boolean get() = durationMs > 0L

    /** True for a track reached through a granted folder rather than the index. */
    val isFromFolder: Boolean get() = source is TrackSource.Folder

    /** Where the folder tree files this track, as `Music/Nick Drake/probe.wv`. */
    val path: String get() = relativePath + displayName

    val contentUri: Uri
        get() = when (val source = source) {
            TrackSource.Indexed -> ContentUris.withAppendedId(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                id,
            )
            is TrackSource.Folder -> Uri.parse(source.documentUri)
        }

    /**
     * The album-art collection knows nothing about a file it never indexed, so
     * a folder track has no artwork URI to offer and the caller draws its
     * placeholder instead.
     */
    val albumArtUri: Uri?
        get() = if (source is TrackSource.Folder || albumId <= 0L) null else albumArtUri(albumId)

    companion object {
        /**
         * Legacy album-art collection. It is undocumented but still populated on
         * every Android release Choir supports, and unlike
         * `ContentResolver.loadThumbnail` it does not require the file to be
         * readable — a useful fallback when a track lives outside our scope.
         */
        // Resolved on first use, not at class-init: Track is a plain value type
        // and must stay constructible without the Android framework loaded.
        private val ALBUM_ART_BASE_URI: Uri by lazy {
            Uri.parse("content://media/external/audio/albumart")
        }

        fun albumArtUri(albumId: Long): Uri =
            ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId)
    }
}
