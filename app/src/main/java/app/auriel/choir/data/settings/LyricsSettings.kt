// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.settings

/**
 * The lyric services Choir can ask, in the order it asks them.
 *
 * Genius is deliberately absent. Its API returns a *link* to a song page, never
 * the words — every app that shows Genius lyrics scrapes the page, which breaks
 * whenever the markup changes and is against their terms. Shipping a provider
 * that cannot work is worse than not shipping one.
 */
enum class LyricsProviderId {
    /** Free, no account, no key, and it serves real synced LRC. The default. */
    LRCLIB,

    /**
     * Free and unofficial: NetEase's own app endpoints, which have no terms
     * covering this use and could change without notice. Off by default, and
     * worth having anyway — its catalogue of non-English music, and of synced
     * lyrics for it, is far deeper than LRCLIB's.
     */
    NETEASE,

    /** Needs a key. The free tier returns a portion of the lyric, not all of it. */
    MUSIXMATCH,

    /** Whatever the user points it at. */
    CUSTOM,
}

/** One service's configuration. */
data class ProviderSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    /** Custom only; the others know their own endpoints. */
    val baseUrl: String = "",
)

/**
 * Everything about fetching lyrics over the network.
 *
 * [onlineEnabled] is the master switch and defaults to off. Choir does hold the
 * INTERNET permission — it has to, for this feature to exist at all — so the
 * honest promise is not "it cannot" but "it does not, unless you say so".
 * Nothing here reaches the network while this is false.
 */
data class LyricsSettings(
    val onlineEnabled: Boolean = false,
    /** Default on: fetching lyrics is not worth someone's mobile data by surprise. */
    val unmeteredOnly: Boolean = true,
    val providers: Map<LyricsProviderId, ProviderSettings> = DEFAULT_PROVIDERS,
    /**
     * The order services are asked in, as the user arranged it.
     *
     * The default runs free-and-synced before keyed before whatever was plugged
     * in, which is what most people would pick — but not everyone, which is why
     * it moves. Someone whose library is mostly one language may well want the
     * service that knows it asked first.
     */
    val providerOrder: List<LyricsProviderId> = LyricsProviderId.entries.toList(),
) {
    fun provider(id: LyricsProviderId): ProviderSettings = providers[id] ?: ProviderSettings()

    /** Services to try, in the user's order, best first. */
    val activeProviders: List<LyricsProviderId>
        get() = if (!onlineEnabled) emptyList() else orderedProviders.filter { provider(it).enabled }

    /**
     * [providerOrder], repaired.
     *
     * A stored order is a list of names written by an older version of Choir,
     * so it can be missing an entry this version knows about or naming one it
     * does not. Anything unrecognised is dropped and anything missing is
     * appended in declaration order — a provider added in an update turns up at
     * the end rather than vanishing, and a saved order never has to be reset.
     */
    val orderedProviders: List<LyricsProviderId>
        get() {
            val known = providerOrder.filter { it in LyricsProviderId.entries }.distinct()
            return known + LyricsProviderId.entries.filterNot { it in known }
        }

    companion object {
        val DEFAULT_PROVIDERS: Map<LyricsProviderId, ProviderSettings> = mapOf(
            // Enabled by default *within* the feature, so that turning the
            // master switch on does something useful without further setup.
            LyricsProviderId.LRCLIB to ProviderSettings(enabled = true),
            // Off until asked for: it is an undocumented endpoint belonging to
            // someone else's app, and that is the user's call to make.
            LyricsProviderId.NETEASE to ProviderSettings(enabled = false),
            LyricsProviderId.MUSIXMATCH to ProviderSettings(enabled = false),
            LyricsProviderId.CUSTOM to ProviderSettings(enabled = false),
        )
    }
}
