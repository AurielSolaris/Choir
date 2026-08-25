// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.ui.widget

/**
 * What the widgets know about playback, and the only thing they read.
 *
 * A widget is not the app. It is drawn by the launcher, from `RemoteViews`,
 * often when Choir's own process is long dead — so it cannot hold a
 * `MediaController`, observe a `StateFlow`, or ask the player anything. What it
 * can do is read the last thing the player said, which is what this is.
 *
 * The snapshot is written by [app.auriel.choir.playback.WidgetPublisher] on the
 * player's own callbacks and persisted, so a widget drawn after a reboot shows
 * the track that was playing before it with a resume affordance, rather than an
 * empty box or a stale animation. Everything here is small and flat for that
 * reason: it survives in preferences, not in memory.
 *
 * [positionMs] is deliberately absent. Nothing here needs a position that keeps
 * moving — the transport buttons do not care, and the lyric line is
 * recalculated and rewritten by the publisher at the moment it changes, so the
 * widget never has to work out where in the song it is.
 */
data class WidgetSnapshot(
    /** The MediaStore id, or null when nothing has played yet. */
    val trackId: Long? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** The artwork's content URI as a string; decoded when a widget draws. */
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val isLiked: Boolean = false,
    /**
     * The synced lyric being sung, or null where the track has no synced words
     * — which is most tracks, and not a failure.
     */
    val lyricLine: String? = null,
    /** False once nothing is loaded, which is what the idle state turns on. */
    val hasTrack: Boolean = false,
) {

    /**
     * What the widgets should show, which is not the same question as whether
     * something is playing.
     *
     * A paused track is still the thing to draw: its name, its art, and a play
     * button. Only a player that has never been given anything, or has been
     * emptied, falls back to inviting the user into the app.
     */
    val isIdle: Boolean get() = !hasTrack || trackId == null

    fun encode(): Map<String, String> = buildMap {
        put(KEY_VERSION, VERSION.toString())
        trackId?.let { put(KEY_TRACK_ID, it.toString()) }
        put(KEY_TITLE, title)
        put(KEY_ARTIST, artist)
        put(KEY_ALBUM, album)
        artworkUri?.let { put(KEY_ARTWORK, it) }
        put(KEY_PLAYING, isPlaying.toString())
        put(KEY_LIKED, isLiked.toString())
        lyricLine?.let { put(KEY_LYRIC, it) }
        put(KEY_HAS_TRACK, hasTrack.toString())
    }

    companion object {

        /**
         * Bumped when a field's meaning changes rather than when one is added.
         *
         * A snapshot written by an older version and read by a newer one is the
         * ordinary case — the app updates while a widget sits on the home
         * screen — so an unreadable one is discarded and replaced at the next
         * playback event rather than migrated.
         */
        const val VERSION = 1

        private const val KEY_VERSION = "version"
        private const val KEY_TRACK_ID = "trackId"
        private const val KEY_TITLE = "title"
        private const val KEY_ARTIST = "artist"
        private const val KEY_ALBUM = "album"
        private const val KEY_ARTWORK = "artworkUri"
        private const val KEY_PLAYING = "isPlaying"
        private const val KEY_LIKED = "isLiked"
        private const val KEY_LYRIC = "lyricLine"
        private const val KEY_HAS_TRACK = "hasTrack"

        /** Nothing has played, so the widgets invite rather than report. */
        val Empty = WidgetSnapshot()

        fun decode(values: Map<String, String>): WidgetSnapshot {
            if (values[KEY_VERSION]?.toIntOrNull() != VERSION) return Empty

            return WidgetSnapshot(
                trackId = values[KEY_TRACK_ID]?.toLongOrNull(),
                title = values[KEY_TITLE].orEmpty(),
                artist = values[KEY_ARTIST].orEmpty(),
                album = values[KEY_ALBUM].orEmpty(),
                artworkUri = values[KEY_ARTWORK],
                isPlaying = values[KEY_PLAYING].toBoolean(),
                isLiked = values[KEY_LIKED].toBoolean(),
                lyricLine = values[KEY_LYRIC],
                hasTrack = values[KEY_HAS_TRACK].toBoolean(),
            )
        }
    }
}
