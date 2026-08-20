// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.model

/**
 * The library as its files are actually arranged, rather than as its tags claim.
 *
 * Albums and artists are derived from tags, which is the right default and
 * useless for the half of a collection whose tags are wrong, missing, or in a
 * format the scanner could not read. A folder tree asks nothing of the file but
 * where it sits, so it is the one view that always works — and the only one
 * that can show a `.wv` the media scanner refused to index as audio at all.
 */
data class MusicFolder(
    /**
     * Slash-separated and volume-relative, with a trailing slash:
     * `Music/Nick Drake/`. The root is the empty string. This doubles as the
     * folder's identity in navigation, because it is stable across a rescan in
     * a way that no generated id would be.
     */
    val path: String,
    /** The last segment, for display. */
    val name: String,
    /** Immediate subfolders, case-insensitively by name. */
    val folders: List<MusicFolder> = emptyList(),
    /** Tracks sitting directly in this folder, in album order. */
    val tracks: List<Track> = emptyList(),
) {
    /** Everything below here, this folder's own tracks included. */
    val trackCount: Int by lazy { tracks.size + folders.sumOf(MusicFolder::trackCount) }

    val isEmpty: Boolean get() = trackCount == 0

    /**
     * Every track below here, depth-first: this folder's own first, then each
     * subfolder's in turn. That is the order a listener means by "play this
     * folder" — an album directory of discs plays disc 1 before disc 2.
     */
    fun allTracks(): List<Track> = buildList {
        addAll(tracks)
        folders.forEach { addAll(it.allTracks()) }
    }

    /** The folder at [path], or `null` if the tree no longer has one. */
    fun find(path: String): MusicFolder? {
        if (path == this.path) return this
        if (!path.startsWith(this.path)) return null
        return folders.firstNotNullOfOrNull { it.find(path) }
    }

    /**
     * This folder and each of its ancestors, root first — everything a
     * breadcrumb needs, without the screen having to walk the tree itself.
     */
    fun trailTo(path: String): List<MusicFolder> {
        if (path == this.path) return listOf(this)
        if (!path.startsWith(this.path)) return emptyList()
        val below = folders.firstNotNullOfOrNull {
            it.trailTo(path).takeIf(List<MusicFolder>::isNotEmpty)
        } ?: return emptyList()
        return listOf(this) + below
    }
}

/**
 * Groups tracks into a folder tree by [Track.relativePath].
 *
 * Intermediate folders are created whether or not they hold tracks themselves,
 * so `Music/Nick Drake/Pink Moon/` produces three levels even though only the
 * last one has audio in it. A track whose path is unknown — some volumes will
 * not say — lands at the root rather than being dropped.
 *
 * Note that this groups by path alone. Two storage volumes that both have a
 * `Music/` fold into one node; a device with an SD card sees its contents
 * merged with internal storage. Reaching an SD card properly means granting it
 * as a folder, which arrives as a tree of its own and never merges.
 */
fun List<Track>.toFolderTree(rootName: String): MusicFolder {
    val bySegments = HashMap<List<String>, MutableList<Track>>()
    for (track in this) {
        bySegments.getOrPut(segmentsOf(track.relativePath)) { mutableListOf() } += track
    }
    return buildFolder(emptyList(), rootName, bySegments)
}

/** Splits `/Music/Nick Drake/` into `[Music, Nick Drake]`, dropping blanks. */
internal fun segmentsOf(relativePath: String): List<String> =
    relativePath.split('/').filter(String::isNotBlank)

private fun buildFolder(
    prefix: List<String>,
    name: String,
    bySegments: Map<List<String>, List<Track>>,
): MusicFolder {
    // Everything strictly below this folder, keyed by the next segment down.
    val children = bySegments.keys
        .filter { it.size > prefix.size && it.subList(0, prefix.size) == prefix }
        .groupBy { it[prefix.size] }

    return MusicFolder(
        path = if (prefix.isEmpty()) "" else prefix.joinToString("/", postfix = "/"),
        name = name,
        folders = children.keys
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .map { segment -> buildFolder(prefix + segment, segment, bySegments) },
        tracks = bySegments[prefix].orEmpty().inFolderOrder(),
    )
}

/**
 * Filename order, near enough. Tracks in one folder are usually one album, so
 * a track number is the better key where the tags have one — and where they do
 * not, the filename is what the user sees in every other program.
 */
private fun List<Track>.inFolderOrder(): List<Track> =
    sortedWith(
        compareBy<Track> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.ifBlank { it.title } },
    )
