// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import app.auriel.choir.data.folders.FolderFileEntity
import app.auriel.choir.data.folders.FoldersDao
import app.auriel.choir.data.folders.toTrack
import app.auriel.choir.data.model.Track

/**
 * The one question [TrackResolver] asks of the indexed library.
 *
 * Narrowed to an interface so that resolving ids can be tested without the
 * Android framework: [MediaStoreRepository] cannot even be constructed off a
 * device, since its content URIs are stubs that come back null.
 */
fun interface IndexedTracks {
    suspend fun byIds(ids: List<Long>): List<Track>
}

/**
 * Turns stored track ids back into tracks, from whichever source has them.
 *
 * The saved queue is a list of ids and nothing else, and since v0.4.0 those ids
 * can come from either of two places: MediaStore, or a folder the user granted.
 * The sign says which — [documentTrackId] issues negative ids precisely so this
 * question never needs a lookup to answer — so each source is asked only about
 * the ids that could possibly be its own.
 *
 * This matters most in the playback service, which restores the queue with no
 * UI awake and no library loaded, and must not have to walk a folder tree
 * before it can play the song that was playing when the phone was locked.
 */
class TrackResolver(
    private val indexed: IndexedTracks,
    private val folders: FoldersDao,
) {

    /**
     * Resolves [ids] in the order given, dropping the ones whose file has gone.
     *
     * A missing track is left out rather than substituted: a queue that plays
     * something nobody chose is worse than one that is a track shorter.
     */
    suspend fun byIds(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()

        val (folderIds, indexedIds) = ids.distinct().partition { it < 0L }

        val byId = HashMap<Long, Track>(ids.size)
        if (indexedIds.isNotEmpty()) {
            indexed.byIds(indexedIds).associateByTo(byId, Track::id)
        }
        if (folderIds.isNotEmpty()) {
            folders.filesByTrackId(folderIds)
                .map(FolderFileEntity::toTrack)
                .associateByTo(byId, Track::id)
        }

        return ids.mapNotNull(byId::get)
    }
}
