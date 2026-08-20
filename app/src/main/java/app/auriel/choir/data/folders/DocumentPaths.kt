// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import app.auriel.choir.playback.AudioFormats

/**
 * Turning what a documents provider says about a file into the same
 * volume-relative path MediaStore would have used for it.
 *
 * This matters because the folder tree has two sources — the indexed library
 * and the folders the user granted — and they only merge into one tree if both
 * describe a file's location the same way. Everything here is pure string work
 * so it can be tested without a provider.
 */
object DocumentPaths {

    /**
     * The relative path a granted tree starts at: `primary:Music/Rock` becomes
     * `Music/Rock/`, and the whole volume, `primary:`, becomes the empty root.
     *
     * Returns `null` when the document id carries no path at all. The storage
     * provider's ids are `volume:path`, which is documented nowhere and
     * unchanged since KitKat, but other providers are free to use opaque ids —
     * Downloads uses `msf:1000000012` — and a number is not a folder name. The
     * caller falls back to the tree's display name and walks from there, which
     * works for every provider because it never parses an id again.
     */
    fun relativePathOfTreeDocumentId(documentId: String?): String? {
        val id = documentId?.trim().orEmpty()
        val colon = id.indexOf(':')
        if (colon < 0) return null

        val path = id.substring(colon + 1).trim('/')
        if (path.isEmpty()) return ""
        // An opaque numeric id is not a path, whatever it is separated by.
        if (path.all(Char::isDigit)) return null
        return "$path/"
    }

    /** Appends one folder to a relative path, keeping the trailing slash. */
    fun childOf(relativePath: String, name: String): String =
        relativePath.trimEnd('/').let { parent ->
            if (parent.isEmpty()) "$name/" else "$parent/$name/"
        }

    /**
     * Whether a document is worth opening as music.
     *
     * The MIME type is asked first here, the opposite way round from
     * [AudioFormats.identify], because a documents provider states the type it
     * knows rather than guessing one from the name — and where it does not
     * know, it says `application/octet-stream`, which is exactly the case the
     * extension is there to rescue. A folder full of `.wv` files is the whole
     * reason this feature exists, and they arrive typed as nothing at all.
     */
    fun isAudio(displayName: String?, mimeType: String?): Boolean {
        val mime = mimeType?.trim()?.lowercase()?.substringBefore(';').orEmpty()
        if (mime.startsWith("audio/")) return true
        if (AudioFormats.identify(displayName, mimeType) != null) return true
        return false
    }

    /**
     * The marker that means "there is no music here as far as anything is
     * concerned". The media scanner has honoured it since Android 1.0; a folder
     * browser that ignored it would show the podcast cache, the voice memos and
     * every game's sound effects.
     */
    const val NO_MEDIA = ".nomedia"
}
