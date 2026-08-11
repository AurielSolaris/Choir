// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.auriel.choir.data.model.Track

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
                .build(),
        )
        .build()

fun List<Track>.toMediaItems(): List<MediaItem> = map(Track::toMediaItem)

/** The MediaStore id a media item was built from, or `null` if it isn't ours. */
fun MediaItem.trackIdOrNull(): Long? = mediaId.toLongOrNull()
