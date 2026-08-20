// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.folders

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocumentPathsTest {

    @Test
    fun `a storage document id gives the same path MediaStore would use`() {
        assertEquals("Music/Rock/", DocumentPaths.relativePathOfTreeDocumentId("primary:Music/Rock"))
    }

    @Test
    fun `a whole volume is the root, not a folder called nothing`() {
        assertEquals("", DocumentPaths.relativePathOfTreeDocumentId("primary:"))
        assertEquals("", DocumentPaths.relativePathOfTreeDocumentId("1A2B-3C4D:/"))
    }

    @Test
    fun `an opaque provider id is not mistaken for a path`() {
        // Downloads hands out ids like this; a number is not a folder name.
        assertNull(DocumentPaths.relativePathOfTreeDocumentId("msf:1000000012"))
        assertNull(DocumentPaths.relativePathOfTreeDocumentId("12345"))
        assertNull(DocumentPaths.relativePathOfTreeDocumentId(null))
    }

    @Test
    fun `descending a level keeps the trailing slash`() {
        assertEquals("Music/", DocumentPaths.childOf("", "Music"))
        assertEquals("Music/Rock/", DocumentPaths.childOf("Music/", "Rock"))
        assertEquals("Music/Rock/", DocumentPaths.childOf("Music", "Rock"))
    }

    @Test
    fun `anything typed as audio is audio`() {
        assertTrue(DocumentPaths.isAudio("song.mp3", "audio/mpeg"))
        assertTrue(DocumentPaths.isAudio("song", "audio/flac; charset=binary"))
    }

    @Test
    fun `a known extension is audio even when the provider says plain data`() {
        // The whole reason folder browsing exists: this is exactly what the
        // storage provider says about the formats the scanner cannot read.
        assertTrue(DocumentPaths.isAudio("probe.wv", "application/octet-stream"))
        assertTrue(DocumentPaths.isAudio("probe.ape", "application/octet-stream"))
        assertTrue(DocumentPaths.isAudio("probe.tta", null))
    }

    @Test
    fun `everything else in the folder is left alone`() {
        assertFalse(DocumentPaths.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(DocumentPaths.isAudio("album.log", "text/plain"))
        assertFalse(DocumentPaths.isAudio("notes.txt", "application/octet-stream"))
        assertFalse(DocumentPaths.isAudio(".nomedia", "application/octet-stream"))
    }
}
