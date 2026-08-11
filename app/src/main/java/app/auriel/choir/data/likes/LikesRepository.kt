// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.likes

import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.relinksFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Liked songs: the one list Choir keeps on the user's behalf.
 *
 * Manual curation only, by design — there is no play count, no
 * scoring and nothing implicit. A track is liked because someone said so.
 */
class LikesRepository(
    private val dao: LikesDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Every like, newest first. */
    val liked: Flow<List<LikedTrackEntity>> = dao.observeAll()

    /** Just the ids, for rows that only need to know whether to draw a heart. */
    val likedIds: Flow<Set<Long>> = liked.map { rows ->
        rows.mapTo(HashSet(rows.size), LikedTrackEntity::trackId)
    }

    /** Flips a track's liked state and reports where it landed. */
    suspend fun toggle(track: Track): Boolean {
        val liked = !dao.isLiked(track.id)
        setLiked(track, liked)
        return liked
    }

    suspend fun setLiked(track: Track, liked: Boolean) {
        if (liked) {
            dao.insert(
                LikedTrackEntity(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                    likedAt = now(),
                ),
            )
        } else {
            dao.delete(track.id)
        }
    }

    /**
     * Follows likes onto their tracks' new ids after MediaStore has renumbered
     * the library. Cheap and idempotent when nothing has moved, which is the
     * usual case, so it is safe to run on every library change.
     */
    suspend fun reconcile(library: List<Track>) {
        // An empty library means the permission was refused or the scan has not
        // finished, not that every liked track is gone.
        if (library.isEmpty()) return

        val relinks = relinksFor(dao.all(), library)
        if (relinks.isEmpty()) return

        dao.applyRelinks(relinks)
        MusicLog.i(TAG, "re-pointed ${relinks.size} likes after a library renumber")
    }

    private companion object {
        const val TAG = "LikesRepository"
    }
}
