// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * The containers Choir can open: everything Media3 brings, and Choir's own.
 *
 * Media3's list is fixed and reasonable — it covers what Android guarantees —
 * but it decides which files exist as far as the player is concerned. A format
 * missing from it fails at `UnrecognizedInputFormatException`, before any
 * decoder is consulted, which is why an FFmpeg build alone does not make an
 * AIFF playable.
 *
 * Appended rather than prepended: Media3's extractors sniff the formats they
 * own with far more care than these do, and should get first refusal.
 */
@UnstableApi
class ChoirExtractorsFactory : ExtractorsFactory {

    private val defaults = DefaultExtractorsFactory()
        // Costs one extra pass over the head of a file and finds the tags on
        // the ones whose extension lied about what they are.
        .setConstantBitrateSeekingEnabled(true)

    override fun createExtractors(): Array<Extractor> =
        defaults.createExtractors() + choirExtractors()

    override fun createExtractors(
        uri: android.net.Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = defaults.createExtractors(uri, responseHeaders) + choirExtractors()

    private fun choirExtractors(): Array<Extractor> = arrayOf(
        AiffExtractor(),
        WavPackExtractor(),
        ApeExtractor(),
        AsfExtractor(),
    )
}
