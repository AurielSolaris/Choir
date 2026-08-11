// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import app.auriel.choir.core.MusicLog
import app.auriel.choir.data.settings.LyricsProviderId
import app.auriel.choir.data.settings.ProviderSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * LRCLIB — free, no account, no key, and the only one of these that reliably
 * hands back properly synced LRC.
 *
 * Asked first for that reason, and the one enabled by default once someone
 * turns the feature on. https://lrclib.net/docs
 */
internal class LrclibProvider(
    private val http: (String, Map<String, String>) -> String? = { url, headers ->
        Http.get(url, headers)
    },
) : LyricsProvider {

    override val id = LyricsProviderId.LRCLIB

    // Nothing to configure, which is rather the point of it.
    override fun isConfigured(settings: ProviderSettings) = true

    override fun fetch(query: LyricsQuery, settings: ProviderSettings): String? {
        // The exact-match endpoint first: given a duration it returns the
        // right edition rather than whichever remaster was uploaded first.
        exact(query)?.let { return it }
        return search(query)
    }

    private fun exact(query: LyricsQuery): String? {
        val url = buildString {
            append(BASE)
            append("/api/get")
            append("?artist_name=").append(query.artist.urlEncoded())
            append("&track_name=").append(query.title.urlEncoded())
            append("&album_name=").append(query.album.urlEncoded())
            append("&duration=").append(query.durationSeconds)
        }
        return http(url, emptyMap())?.let { lyricsFrom(JSONObject(it)) }
    }

    /**
     * The fallback, for when the tags disagree with LRCLIB's copy — a different
     * album name, a duration a second out. Candidates are filtered back down by
     * length so a live version does not get pinned to a studio track.
     */
    private fun search(query: LyricsQuery): String? {
        val url = buildString {
            append(BASE)
            append("/api/search")
            append("?artist_name=").append(query.artist.urlEncoded())
            append("&track_name=").append(query.title.urlEncoded())
        }
        val body = http(url, emptyMap()) ?: return null

        val results = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var best: String? = null

        for (index in 0 until results.length()) {
            val candidate = results.optJSONObject(index) ?: continue
            val seconds = candidate.optDouble("duration", -1.0).toLong()
            // Two seconds of slack: encoders and databases disagree by about
            // that much, and more than that is a different recording.
            if (seconds >= 0 && kotlin.math.abs(seconds - query.durationSeconds) > 2) continue

            val lyrics = lyricsFrom(candidate) ?: continue
            // A synced result ends the search; a plain one is held in case
            // nothing better turns up.
            if (lyrics.contains('[')) return lyrics
            if (best == null) best = lyrics
        }
        return best
    }

    private fun lyricsFrom(json: JSONObject): String? {
        if (json.optBoolean("instrumental", false)) return null

        val synced = json.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = json.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return synced ?: plain
    }

    private companion object {
        const val BASE = "https://lrclib.net"
    }
}

/**
 * NetEase Cloud Music, through the endpoints its own clients use.
 *
 * Two calls: search for the track, then ask for the lyric by song id. Both are
 * undocumented and neither is promised to anyone, which is why this is off
 * until switched on — but its synced-lyric coverage of Mandarin, Cantonese,
 * Japanese and Korean music is better than anything else free.
 *
 * The response bodies come back as JSON with a `code` field that is 200 on
 * success and, unhelpfully, also 200 on "found nothing", so an empty result is
 * checked for rather than inferred.
 */
internal class NeteaseProvider(
    private val http: (String, Map<String, String>) -> String? = { url, headers ->
        Http.get(url, headers)
    },
) : LyricsProvider {

    override val id = LyricsProviderId.NETEASE

    override fun isConfigured(settings: ProviderSettings) = true

    override fun fetch(query: LyricsQuery, settings: ProviderSettings): String? {
        val songId = search(query) ?: return null
        return lyric(songId)
    }

    /**
     * The best-matching song id, or null.
     *
     * Results are filtered by duration for the same reason LRCLIB's are: the
     * first hit for a popular song is as likely to be a cover or a live cut as
     * the recording in hand.
     */
    private fun search(query: LyricsQuery): Long? {
        val terms = "${query.title} ${query.artist}".trim()
        val url = "$BASE/search/get?type=1&limit=$SEARCH_LIMIT&s=${terms.urlEncoded()}"
        val body = http(url, HEADERS) ?: return null

        return runCatching {
            val songs = JSONObject(body)
                .optJSONObject("result")
                ?.optJSONArray("songs")
                ?: return null

            var fallback: Long? = null
            for (index in 0 until songs.length()) {
                val song = songs.optJSONObject(index) ?: continue
                val id = song.optLong("id", 0L).takeIf { it > 0L } ?: continue

                // NetEase reports duration in milliseconds, under a key named
                // for neither.
                val seconds = song.optLong("duration", -1L) / 1000L
                if (seconds >= 0 && kotlin.math.abs(seconds - query.durationSeconds) <= 2) {
                    return id
                }
                if (fallback == null) fallback = id
            }
            fallback
        }.getOrNull()
    }

    private fun lyric(songId: Long): String? {
        // lv and tv select the original lyric and its translation; -1 asks for
        // the newest version of each. Only the original is used.
        val body = http("$BASE/song/lyric?id=$songId&lv=-1&kv=-1&tv=-1", HEADERS) ?: return null

        return runCatching {
            val json = JSONObject(body)
            // `lrc` is the synced document; `klyric` is karaoke-timed and comes
            // in NetEase's own format, which is not LRC and is left alone.
            json.optJSONObject("lrc")?.optString("lyric")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.takeIf(::hasActualWords)
        }.getOrNull()
    }

    /**
     * Whether a document contains a lyric or an apology.
     *
     * NetEase does not answer "no lyrics" with an empty body or an error. It
     * answers with a perfectly well-formed LRC document containing the single
     * line `[00:00.00]暂无歌词` — "no lyrics yet" — and instrumentals get
     * `纯音乐，请欣赏`, "instrumental, please enjoy". Both parse, both render,
     * and both would sit on screen looking exactly like the words to the song.
     */
    private fun hasActualWords(lyric: String): Boolean =
        lyric.lineSequence()
            .map { TIMESTAMP.replace(it, "").trim() }
            .filter { it.isNotEmpty() }
            .any { it !in PLACEHOLDERS }

    private companion object {
        const val BASE = "https://music.163.com/api"
        const val SEARCH_LIMIT = 10

        val TIMESTAMP = Regex("""\[[^]]*]""")

        /** What NetEase says instead of nothing. */
        val PLACEHOLDERS = setOf(
            "暂无歌词",
            "纯音乐，请欣赏",
            "纯音乐,请欣赏",
        )

        /**
         * The endpoint answers with an error to requests that do not look like
         * they came from a browser on its own site. Sending a plausible
         * referer is the whole of what is needed; no cookie, no account, and
         * nothing identifying the user.
         */
        val HEADERS = mapOf("Referer" to "https://music.163.com")
    }
}

/**
 * Musixmatch, for people who have a key.
 *
 * Worth being straight about: the free developer tier returns only a portion of
 * each lyric and forbids commercial use. Choir sends the key it is given and
 * shows what comes back — the terms are between the user and Musixmatch.
 */
internal class MusixmatchProvider(
    private val http: (String, Map<String, String>) -> String? = { url, headers ->
        Http.get(url, headers)
    },
) : LyricsProvider {

    override val id = LyricsProviderId.MUSIXMATCH

    override fun isConfigured(settings: ProviderSettings) = settings.apiKey.isNotBlank()

    override fun fetch(query: LyricsQuery, settings: ProviderSettings): String? {
        val url = buildString {
            append(BASE)
            append("/matcher.lyrics.get")
            append("?q_track=").append(query.title.urlEncoded())
            append("&q_artist=").append(query.artist.urlEncoded())
            append("&apikey=").append(settings.apiKey.urlEncoded())
        }
        val body = http(url, emptyMap()) ?: return null

        return runCatching {
            val message = JSONObject(body).getJSONObject("message")
            val status = message.getJSONObject("header").optInt("status_code", 0)
            if (status != 200) {
                MusicLog.d(TAG, "musixmatch returned status $status")
                return null
            }
            message.getJSONObject("body")
                .getJSONObject("lyrics")
                .optString("lyrics_body")
                .takeIf { it.isNotBlank() }
                // The free tier appends its own notice to the words.
                ?.substringBefore("***")
                ?.trim()
        }.getOrNull()
    }

    private companion object {
        const val TAG = "MusixmatchProvider"
        const val BASE = "https://api.musixmatch.com/ws/1.1"
    }
}

/**
 * Whatever the user points Choir at.
 *
 * The contract is deliberately dull: a GET with `artist`, `title`, `album` and
 * `duration` query parameters, answered with either LRC as plain text or JSON
 * carrying a `syncedLyrics`, `plainLyrics` or `lyrics` field. That covers
 * self-hosted LRCLIB instances, which is the case that actually comes up.
 */
internal class CustomProvider(
    private val http: (String, Map<String, String>) -> String? = { url, headers ->
        Http.get(url, headers)
    },
) : LyricsProvider {

    override val id = LyricsProviderId.CUSTOM

    override fun isConfigured(settings: ProviderSettings) =
        settings.baseUrl.startsWith("https://", ignoreCase = true)

    override fun fetch(query: LyricsQuery, settings: ProviderSettings): String? {
        val base = settings.baseUrl.trimEnd('/')
        val url = buildString {
            append(base)
            append(if (base.contains('?')) "&" else "?")
            append("artist=").append(query.artist.urlEncoded())
            append("&title=").append(query.title.urlEncoded())
            append("&album=").append(query.album.urlEncoded())
            append("&duration=").append(query.durationSeconds)
        }

        val headers = if (settings.apiKey.isNotBlank()) {
            mapOf("Authorization" to "Bearer ${settings.apiKey}")
        } else {
            emptyMap()
        }

        val body = http(url, headers)?.takeIf { it.isNotBlank() } ?: return null
        return asLyrics(body)
    }

    /** Accepts either shape rather than making the user's server match ours. */
    private fun asLyrics(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed

        return runCatching {
            val json = JSONObject(trimmed)
            listOf("syncedLyrics", "plainLyrics", "lyrics")
                .firstNotNullOfOrNull { json.optString(it).takeIf { v -> v.isNotBlank() } }
        }.getOrNull() ?: trimmed
    }
}
