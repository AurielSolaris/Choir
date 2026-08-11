// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import app.auriel.choir.data.settings.LyricsProviderId
import app.auriel.choir.data.settings.ProviderSettings

/**
 * What Choir sends when it asks for a lyric — and it is exactly this, nothing
 * else. No device id, no library contents, no listening history.
 */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
) {
    val durationSeconds: Long get() = durationMs / 1000
}

/**
 * A service that might know the words.
 *
 * Returns the raw document — usually LRC, sometimes plain prose. Deciding what
 * it means is the LRC parser's job, so that a provider returning timestamps and
 * one returning a paragraph both come out the far end as [app.auriel.choir.data.lyrics.Lyrics].
 */
interface LyricsProvider {
    val id: LyricsProviderId

    /** Whether this provider has what it needs — a key, a URL — to be asked. */
    fun isConfigured(settings: ProviderSettings): Boolean

    /** Blocking; callers are already on an IO dispatcher. */
    fun fetch(query: LyricsQuery, settings: ProviderSettings): String?
}
