// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

/**
 * MIME types Media3 has no constant for, because it has no extractor for them.
 *
 * Media3's `MimeTypes` names every format it can open and stops there, so the
 * containers Choir opens itself have to be named here. The strings are the ones
 * the wider world uses — `audio/x-ape` is what `file(1)`, MediaStore and every
 * tagger call a Monkey's Audio file — because they also travel out through
 * `MediaItem` extras and back in through [AudioFormats].
 *
 * They matter to two places that must agree: the extractor that publishes a
 * track's format, and `FfmpegLibrary.getCodecName`, which turns that MIME type
 * into the name of an FFmpeg decoder. A typo in either is a silent failure to
 * play, so both read from here.
 */
object ChoirMimeTypes {

    /** Monkey's Audio. */
    const val AUDIO_APE = "audio/x-ape"

    /** WavPack. */
    const val AUDIO_WAVPACK = "audio/x-wavpack"

    /** Windows Media Audio, versions 1 and 2 — the ordinary lossy kind. */
    const val AUDIO_WMA = "audio/x-ms-wma"

    /** Windows Media Audio Professional. */
    const val AUDIO_WMA_PRO = "audio/x-ms-wmapro"

    /** Windows Media Audio Lossless. */
    const val AUDIO_WMA_LOSSLESS = "audio/x-ms-wmalossless"

    /** Windows Media Audio Voice. */
    const val AUDIO_WMA_VOICE = "audio/x-ms-wmavoice"
}
