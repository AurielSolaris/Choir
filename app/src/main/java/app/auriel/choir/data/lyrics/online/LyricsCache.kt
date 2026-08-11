// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import android.content.Context
import app.auriel.choir.core.MusicLog
import java.io.File

/**
 * Fetched lyrics, kept on disk.
 *
 * Two reasons, and the second matters more: a track is only ever fetched once,
 * and once fetched its words are available offline for good. A lyric you have
 * seen should not disappear on the train.
 *
 * Keyed by the same title/artist/duration fingerprint the library uses to
 * re-link likes and playlists, so a MediaStore renumber does not orphan the
 * cache either.
 *
 * A miss is recorded as an empty file. Without that, three services get asked
 * the same unanswerable question every time the screen is opened.
 */
class LyricsCache(context: Context, private val now: () -> Long = System::currentTimeMillis) {

    private val directory = File(context.cacheDir, DIRECTORY)

    /**
     * @return the stored document, an empty string for a remembered miss, or
     *   null if this track has never been asked about.
     */
    fun read(fingerprint: String): String? {
        val file = fileFor(fingerprint)
        if (!file.exists()) return null

        // Misses expire; hits do not. Lyrics get uploaded, and a track that had
        // none last month may have some now.
        if (file.length() == 0L && now() - file.lastModified() > MISS_TTL_MS) {
            file.delete()
            return null
        }

        return runCatching { file.readText() }.getOrNull()
    }

    fun write(fingerprint: String, lyrics: String) {
        runCatching {
            directory.mkdirs()
            fileFor(fingerprint).writeText(lyrics)
        }.onFailure { MusicLog.d(TAG, "could not cache lyrics: ${it.message}") }
    }

    fun writeMiss(fingerprint: String) {
        runCatching {
            directory.mkdirs()
            fileFor(fingerprint).writeText("")
        }.onFailure { MusicLog.d(TAG, "could not record a lyric miss: ${it.message}") }
    }

    /** Offered in settings, because a cache nobody can clear is a liability. */
    fun clear() {
        runCatching { directory.deleteRecursively() }
    }

    fun sizeBytes(): Long =
        runCatching { directory.walkTopDown().filter(File::isFile).sumOf(File::length) }
            .getOrDefault(0L)

    /**
     * Hashed, not spelled out: the fingerprint contains a track title and an
     * artist, and file names in a cache directory are readable by anything with
     * a debugger attached.
     */
    private fun fileFor(fingerprint: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.lrc")
    }

    private companion object {
        const val TAG = "LyricsCache"
        const val DIRECTORY = "lyrics"

        /** A week: long enough to stop hammering, short enough to catch uploads. */
        const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
