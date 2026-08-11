// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * One audio file, as MediaStore knows it.
 *
 * Deliberately holds no `DATA` path: on scoped storage (minSdk 29) the raw file
 * path is unreliable and often unreadable. Everything — playback, artwork —
 * goes through content URIs derived from [id] and [albumId].
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
) {
    /**
     * False when MediaStore could not work out how long the track is, which it
     * writes as a null duration for every format it cannot parse. Playing the
     * file may still work; the seek bar just has nothing to scale to until the
     * decoder reports a length of its own.
     */
    val hasKnownDuration: Boolean get() = durationMs > 0L

    val contentUri: Uri
        get() = ContentUris.withAppendedId(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            id,
        )

    val albumArtUri: Uri
        get() = albumArtUri(albumId)

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
