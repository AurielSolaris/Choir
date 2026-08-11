// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.playlist

import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.model.Track
import app.auriel.choir.data.relinksFor
import kotlinx.coroutines.flow.Flow

/**
 * Choir's own playlists.
 *
 * Everything here is ordinary local data — no MediaStore involvement at all,
 * which is the point: the platform's playlist tables are deprecated and, since
 * Android 11, permanently empty to a third-party app.
 */
class PlaylistRepository(
    private val dao: PlaylistDao,
    private val now: () -> Long = System::currentTimeMillis,
) {

    val playlists: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun members(playlistId: Long): Flow<List<PlaylistMemberEntity>> = dao.observeMembers(playlistId)

    suspend fun name(playlistId: Long): String? = dao.playlist(playlistId)?.name

    /**
     * Makes a playlist, or returns the existing one if the name is taken.
     *
     * Names are not unique by constraint — two playlists may genuinely deserve
     * the same name — but creating a second "Late night" when one already
     * exists is almost always a repeated tap rather than an intention.
     */
    suspend fun create(name: String): Long {
        val trimmed = name.trim().ifBlank { UNTITLED }
        dao.idOfName(trimmed)?.let { return it }

        val timestamp = now()
        return dao.insertPlaylist(
            PlaylistEntity(name = trimmed, createdAt = timestamp, updatedAt = timestamp),
        )
    }

    /**
     * Makes a playlist that is definitely new, adding "(2)", "(3)" … if the
     * name is taken.
     *
     * Import uses this rather than [create]: importing a file called
     * `Deep cuts.m3u` when a "Deep cuts" already exists must not silently
     * append to it, which looks exactly like the playlist having doubled.
     */
    suspend fun createDistinct(name: String): Long {
        val base = name.trim().ifBlank { UNTITLED }

        var candidate = base
        var suffix = 1
        while (dao.idOfName(candidate) != null) {
            suffix++
            candidate = "$base ($suffix)"
        }

        val timestamp = now()
        return dao.insertPlaylist(
            PlaylistEntity(name = candidate, createdAt = timestamp, updatedAt = timestamp),
        )
    }

    suspend fun rename(playlistId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        dao.rename(playlistId, trimmed, now())
    }

    suspend fun delete(playlistId: Long) = dao.deletePlaylist(playlistId)

    /** Appends to the end, which is what "add to playlist" means everywhere. */
    suspend fun add(playlistId: Long, tracks: List<Track>) {
        if (tracks.isEmpty()) return

        val start = dao.lastPosition(playlistId) + 1
        dao.insertMembers(
            tracks.mapIndexed { offset, track ->
                PlaylistMemberEntity(
                    playlistId = playlistId,
                    trackId = track.id,
                    position = start + offset,
                    title = track.title,
                    artist = track.artist,
                    durationMs = track.durationMs,
                )
            },
        )
        dao.touch(playlistId, now())
        MusicLog.d(TAG, "added ${tracks.size} tracks to playlist $playlistId")
    }

    /**
     * Removes one entry, then closes the gap it left.
     *
     * Positions could be left sparse — the list is ordered, not indexed — but a
     * contiguous run means an import or a repair can rely on the numbering.
     */
    suspend fun remove(playlistId: Long, memberId: Long) {
        dao.deleteMember(memberId)
        dao.reorder(playlistId, dao.members(playlistId).map(PlaylistMemberEntity::id), now())
    }

    /** Moves the entry at [from] to [to], carrying everything between it along. */
    suspend fun move(playlistId: Long, from: Int, to: Int) {
        val ids = dao.members(playlistId).map(PlaylistMemberEntity::id).toMutableList()
        if (from !in ids.indices || to !in ids.indices || from == to) return

        ids.add(to, ids.removeAt(from))
        dao.reorder(playlistId, ids, now())
    }

    /**
     * Writes an order the UI has already settled on, used when a drag ends.
     * Ids not in this playlist are ignored rather than trusted.
     */
    suspend fun applyOrder(playlistId: Long, memberIdsInOrder: List<Long>) {
        val known = dao.members(playlistId).mapTo(HashSet(), PlaylistMemberEntity::id)
        val ordered = memberIdsInOrder.filter { it in known }
        if (ordered.size != known.size) return

        dao.reorder(playlistId, ordered, now())
    }

    /**
     * Follows members onto their tracks' new ids after a library renumber, the
     * same way likes do. Never deletes: a member whose file is missing today
     * may be back tomorrow.
     */
    suspend fun reconcile(library: List<Track>) {
        if (library.isEmpty()) return

        val relinks = relinksFor(dao.allMembers(), library)
        if (relinks.isEmpty()) return

        dao.applyRelinks(relinks)
        MusicLog.i(TAG, "re-pointed ${relinks.size} playlist entries after a library renumber")
    }

    private companion object {
        const val TAG = "PlaylistRepository"
        const val UNTITLED = "Untitled playlist"
    }
}

/**
 * Resolves stored members against the library, keeping playlist order and
 * dropping entries whose track is not currently readable.
 */
fun playlistTracksIn(
    members: List<PlaylistMemberEntity>,
    library: List<Track>,
): List<PlaylistTrack> {
    if (members.isEmpty() || library.isEmpty()) return emptyList()

    val byId = library.associateBy(Track::id)
    return members.mapNotNull { member ->
        byId[member.trackId]?.let { PlaylistTrack(member.id, it) }
    }
}

/** A track together with the playlist entry it sits in, so removal is unambiguous. */
data class PlaylistTrack(val memberId: Long, val track: Track)
