// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import app.auriel.choir.core.MusicLog

/**
 * Whether this build has an FFmpeg audio decoder in it, discovered at runtime.
 *
 * Media3's FFmpeg extension is not published to any Maven repository — Google
 * ships it as source to be built against the NDK, so whether it is present is a
 * property of how the APK was assembled, not of the source tree. Choir must
 * therefore compile and run either way, which rules out referring to any of the
 * extension's classes directly.
 *
 * Two class names are tried. The first is the official one; the second is the
 * prebuilt community port, which the same code drives because it deliberately
 * mirrors the upstream API.
 */
object FfmpegSupport {

    private const val TAG = "FfmpegSupport"

    private const val OFFICIAL_RENDERER = "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"
    private const val OFFICIAL_LIBRARY = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    private const val NEXTLIB_RENDERER =
        "io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegAudioRenderer"
    private const val NEXTLIB_LIBRARY =
        "io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary"

    private val candidates = listOf(
        OFFICIAL_RENDERER to OFFICIAL_LIBRARY,
        NEXTLIB_RENDERER to NEXTLIB_LIBRARY,
    )

    /**
     * The renderer class to instantiate, or `null` if this build has none.
     *
     * Resolved once. A class either is on the classpath or is not, and the
     * answer cannot change while the process lives.
     */
    val rendererClass: Class<*>? by lazy { findRenderer() }

    /** True when a track this device cannot decode natively still has a chance. */
    val isAvailable: Boolean get() = rendererClass != null

    private fun findRenderer(): Class<*>? {
        for ((rendererName, libraryName) in candidates) {
            val renderer = runCatching { Class.forName(rendererName) }.getOrNull() ?: continue

            // Finding the class is not the same as being able to use it: the
            // Java side can be in the APK while the .so for this ABI is not.
            // The extension answers that question itself, so ask.
            if (!nativeLibraryLoads(libraryName)) {
                MusicLog.w(TAG, "$rendererName is present but its native library did not load")
                continue
            }

            MusicLog.i(TAG, "FFmpeg audio decoding available via $rendererName")
            return renderer
        }

        MusicLog.i(TAG, "no FFmpeg decoder in this build; platform decoders only")
        return null
    }

    /**
     * Calls the extension's own `FfmpegLibrary.isAvailable()`, which is what
     * triggers the `System.loadLibrary` and reports honestly if it fails.
     *
     * A missing library class is treated as a yes rather than a no: some builds
     * expose only the renderer, and the alternative is refusing to use a
     * decoder that would have worked.
     */
    private fun nativeLibraryLoads(libraryName: String): Boolean {
        val library = runCatching { Class.forName(libraryName) }.getOrNull() ?: return true
        return runCatching {
            library.getMethod("isAvailable").invoke(null) as? Boolean
        }.onFailure {
            MusicLog.w(TAG, "could not ask $libraryName whether it loaded", it)
        }.getOrNull() ?: false
    }
}
