// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.auriel.choir.data.model.Track

/** Keys for the two facts Media3 has no field of its own for. */
private const val EXTRA_DISPLAY_NAME = "app.auriel.choir.DISPLAY_NAME"
private const val EXTRA_MIME_TYPE = "app.auriel.choir.MIME_TYPE"

/**
 * Conversions between the library model and what the player consumes.
 *
 * The MediaStore id doubles as the media id, which is what lets a saved queue be
 * resolved back into tracks after a restart.
 */
fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(contentUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri)
                .setTrackNumber(trackNumber.takeIf { it > 0 })
                .setRecordingYear(year.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                // Carried so that when playback fails the app can say *which*
                // format it could not read, instead of a shrug. Extras are the
                // only part of the metadata that crosses to a MediaController
                // intact; MediaItem's own mimeType lives in the local config,
                // which the session does not send.
                .setExtras(
                    Bundle().apply {
                        putString(EXTRA_DISPLAY_NAME, displayName)
                        putString(EXTRA_MIME_TYPE, mimeType)
                    },
                )
                .build(),
        )
        .build()

/**
 * What Choir knows about this item's file format, or `null` for anything not
 * queued by Choir — another app's item, or one from a build before the extras
 * were added.
 */
fun MediaItem.audioFormat(): AudioFormats.Format? {
    val extras = mediaMetadata.extras ?: return null
    return AudioFormats.identify(
        displayName = extras.getString(EXTRA_DISPLAY_NAME),
        mimeType = extras.getString(EXTRA_MIME_TYPE),
    )
}

fun List<Track>.toMediaItems(): List<MediaItem> = map(Track::toMediaItem)

/** The MediaStore id a media item was built from, or `null` if it isn't ours. */
fun MediaItem.trackIdOrNull(): Long? = mediaId.toLongOrNull()
