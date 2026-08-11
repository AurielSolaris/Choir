// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import app.auriel.choir.data.settings.ProviderSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val QUERY = LyricsQuery(
    title = "505",
    artist = "Arctic Monkeys",
    album = "Favourite Worst Nightmare",
    durationMs = 253_000,
)

/** Records what was requested and replies with whatever the test wants. */
private class FakeHttp(private val responses: Map<String, String> = emptyMap()) {
    val requested = mutableListOf<String>()
    var lastHeaders: Map<String, String> = emptyMap()

    val get: (String, Map<String, String>) -> String? = { url, headers ->
        requested += url
        lastHeaders = headers
        responses.entries.firstOrNull { url.contains(it.key) }?.value
    }
}

class NeteaseProviderTest {

    private fun searchResult(vararg songs: Pair<Long, Long>): String {
        val entries = songs.joinToString(",") { (id, durationMs) ->
            """{"id":$id,"name":"505","duration":$durationMs}"""
        }
        return """{"code":200,"result":{"songs":[$entries]}}"""
    }

    @Test
    fun `searches, then fetches the lyric for the song it found`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1234L to 253_000L),
                "/song/lyric" to """{"lrc":{"lyric":"[00:01.00]Timed"}}""",
            ),
        )

        val result = NeteaseProvider(http.get).fetch(QUERY, ProviderSettings())

        assertEquals("[00:01.00]Timed", result)
        assertEquals(2, http.requested.size)
        assertTrue(http.requested[1].contains("id=1234"))
    }

    /**
     * NetEase reports duration in milliseconds. Comparing it to seconds without
     * dividing would reject every correct match, and the failure is silent —
     * the provider would simply never find anything.
     */
    @Test
    fun `matches on duration, in the units NetEase actually reports`() {
        val http = FakeHttp(
            mapOf(
                // The right song is second; the first is a five-minute remix.
                "/search/get" to searchResult(111L to 300_000L, 222L to 252_500L),
                "/song/lyric" to """{"lrc":{"lyric":"Words"}}""",
            ),
        )

        NeteaseProvider(http.get).fetch(QUERY, ProviderSettings())

        assertTrue(http.requested[1].contains("id=222"), http.requested[1])
    }

    @Test
    fun `falls back to the first result when nothing matches on length`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(111L to 400_000L, 222L to 500_000L),
                "/song/lyric" to """{"lrc":{"lyric":"Words"}}""",
            ),
        )

        NeteaseProvider(http.get).fetch(QUERY, ProviderSettings())

        assertTrue(http.requested[1].contains("id=111"))
    }

    @Test
    fun `sends the referer the endpoint insists on, and nothing else`() {
        val http = FakeHttp(mapOf("/search/get" to searchResult(1L to 253_000L)))

        NeteaseProvider(http.get).fetch(QUERY, ProviderSettings())

        assertEquals(mapOf("Referer" to "https://music.163.com"), http.lastHeaders)
        assertTrue(http.requested.first().startsWith("https://"))
    }

    @Test
    fun `a search with no results asks for no lyric`() {
        val http = FakeHttp(mapOf("/search/get" to """{"code":200,"result":{"songs":[]}}"""))

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
        assertEquals(1, http.requested.size)
    }

    @Test
    fun `an empty lyric body is nothing, not an empty pane`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to """{"lrc":{"lyric":""}}""",
            ),
        )

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    /**
     * Verified against the live endpoint: asked for a track it has no lyric
     * for, NetEase returns HTTP 200 with a well-formed one-line LRC document
     * saying "no lyrics yet". Nothing about the response is an error, so a
     * parser that only checks for blanks shows the apology as the song.
     */
    @Test
    fun `the no-lyrics placeholder is not mistaken for a lyric`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to """{"lrc":{"version":1,"lyric":"[00:00.00]暂无歌词"}}""",
            ),
        )

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `the instrumental placeholder is not mistaken for a lyric either`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to """{"lrc":{"lyric":"[00:00.00]纯音乐，请欣赏"}}""",
            ),
        )

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `a real lyric that happens to contain a placeholder line still counts`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to
                    """{"lrc":{"lyric":"[00:00.00]暂无歌词\n[00:23.92]Real words here"}}""",
            ),
        )

        assertEquals(
            "[00:00.00]暂无歌词\n[00:23.92]Real words here",
            NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()),
        )
    }

    /** The shape a real answer takes, taken from the live endpoint. */
    @Test
    fun `a genuine synced lyric comes back whole`() {
        val lyric = "[00:00.000] 作词 : 方文山\\n[00:23.929]塞纳河畔 左岸的咖啡"
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to """{"lrc":{"version":2,"lyric":"$lyric"}}""",
            ),
        )

        val result = NeteaseProvider(http.get).fetch(QUERY, ProviderSettings())
        assertTrue(result!!.contains("塞纳河畔"))
    }

    @Test
    fun `the karaoke lyric is left alone, being a format of its own`() {
        val http = FakeHttp(
            mapOf(
                "/search/get" to searchResult(1L to 253_000L),
                "/song/lyric" to """{"klyric":{"lyric":"[0,100](0,10,0)Not LRC"}}""",
            ),
        )

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `nonsense in place of JSON is declined rather than thrown`() {
        val http = FakeHttp(mapOf("/search/get" to "<html>blocked</html>"))

        assertNull(NeteaseProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `needs no configuring, having nothing to configure`() {
        assertTrue(NeteaseProvider().isConfigured(ProviderSettings()))
    }
}

class LrclibProviderTest {

    @Test
    fun `synced lyrics are preferred to the plain copy of the same song`() {
        val http = FakeHttp(
            mapOf(
                "/api/get" to """
                    {"syncedLyrics":"[00:01.00]Timed","plainLyrics":"Untimed"}
                """.trimIndent(),
            ),
        )

        val result = LrclibProvider(http.get).fetch(QUERY, ProviderSettings())

        assertEquals("[00:01.00]Timed", result)
    }

    @Test
    fun `plain lyrics are accepted when there is nothing timed`() {
        val http = FakeHttp(mapOf("/api/get" to """{"plainLyrics":"Untimed words"}"""))

        assertEquals("Untimed words", LrclibProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `the exact lookup sends the duration, which is what picks the right edition`() {
        val http = FakeHttp(mapOf("/api/get" to """{"plainLyrics":"x"}"""))

        LrclibProvider(http.get).fetch(QUERY, ProviderSettings())

        val url = http.requested.single()
        assertTrue(url.startsWith("https://"))
        assertTrue(url.contains("duration=253"))
        assertTrue(url.contains("track_name=505"))
        assertTrue(url.contains("artist_name=Arctic%20Monkeys"))
    }

    @Test
    fun `an instrumental is reported as having no lyrics, not as empty ones`() {
        val http = FakeHttp(
            mapOf("/api/get" to """{"instrumental":true,"plainLyrics":""}"""),
        )

        assertNull(LrclibProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `a failed exact lookup falls back to search`() {
        val http = FakeHttp(
            mapOf(
                "/api/search" to """
                    [{"duration":253.0,"syncedLyrics":"[00:02.00]Found by search"}]
                """.trimIndent(),
            ),
        )

        val result = LrclibProvider(http.get).fetch(QUERY, ProviderSettings())

        assertEquals("[00:02.00]Found by search", result)
        assertEquals(2, http.requested.size)
    }

    @Test
    fun `a search result of the wrong length is refused`() {
        // A six-minute live version is not the album track, whatever it is called.
        val http = FakeHttp(
            mapOf("/api/search" to """[{"duration":380.0,"syncedLyrics":"[00:01.00]Live"}]"""),
        )

        assertNull(LrclibProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `a second of drift is close enough`() {
        val http = FakeHttp(
            mapOf("/api/search" to """[{"duration":254.0,"syncedLyrics":"[00:01.00]Close"}]"""),
        )

        assertEquals("[00:01.00]Close", LrclibProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `a synced result later in the list beats a plain one earlier`() {
        val http = FakeHttp(
            mapOf(
                "/api/search" to """
                    [{"duration":253.0,"plainLyrics":"Untimed"},
                     {"duration":253.0,"syncedLyrics":"[00:01.00]Timed"}]
                """.trimIndent(),
            ),
        )

        assertEquals("[00:01.00]Timed", LrclibProvider(http.get).fetch(QUERY, ProviderSettings()))
    }

    @Test
    fun `nonsense in the response is survived rather than thrown`() {
        val http = FakeHttp(mapOf("/api/get" to "not json at all"))

        assertNull(runCatching { LrclibProvider(http.get).fetch(QUERY, ProviderSettings()) }.getOrNull())
    }

    @Test
    fun `it needs no configuration at all`() {
        assertTrue(LrclibProvider().isConfigured(ProviderSettings()))
    }
}

class MusixmatchProviderTest {

    private val settings = ProviderSettings(enabled = true, apiKey = "abc123")

    @Test
    fun `a successful response gives the words`() {
        val http = FakeHttp(
            mapOf(
                "matcher.lyrics.get" to """
                    {"message":{"header":{"status_code":200},
                     "body":{"lyrics":{"lyrics_body":"The words"}}}}
                """.trimIndent(),
            ),
        )

        assertEquals("The words", MusixmatchProvider(http.get).fetch(QUERY, settings))
    }

    @Test
    fun `the free tier's trailing notice is not shown as part of the lyric`() {
        val http = FakeHttp(
            mapOf(
                "matcher.lyrics.get" to """
                    {"message":{"header":{"status_code":200},
                     "body":{"lyrics":{"lyrics_body":"The words\n\n*** This Lyrics is NOT for Commercial use ***"}}}}
                """.trimIndent(),
            ),
        )

        assertEquals("The words", MusixmatchProvider(http.get).fetch(QUERY, settings))
    }

    @Test
    fun `a non-200 status inside the body is treated as no result`() {
        val http = FakeHttp(
            mapOf("matcher.lyrics.get" to """{"message":{"header":{"status_code":401}}}"""),
        )

        assertNull(MusixmatchProvider(http.get).fetch(QUERY, settings))
    }

    @Test
    fun `without a key it is not asked at all`() {
        assertFalse(MusixmatchProvider().isConfigured(ProviderSettings(enabled = true)))
        assertTrue(MusixmatchProvider().isConfigured(settings))
    }
}

class CustomProviderTest {

    private val settings = ProviderSettings(enabled = true, baseUrl = "https://lyrics.example/api")

    @Test
    fun `a plain text response is taken as the lyric`() {
        val http = FakeHttp(mapOf("lyrics.example" to "[00:01.00]From my own server"))

        assertEquals(
            "[00:01.00]From my own server",
            CustomProvider(http.get).fetch(QUERY, settings),
        )
    }

    @Test
    fun `a JSON response is understood in any of the usual shapes`() {
        val synced = FakeHttp(mapOf("lyrics.example" to """{"syncedLyrics":"[00:01.00]A"}"""))
        val plain = FakeHttp(mapOf("lyrics.example" to """{"plainLyrics":"B"}"""))
        val bare = FakeHttp(mapOf("lyrics.example" to """{"lyrics":"C"}"""))

        assertEquals("[00:01.00]A", CustomProvider(synced.get).fetch(QUERY, settings))
        assertEquals("B", CustomProvider(plain.get).fetch(QUERY, settings))
        assertEquals("C", CustomProvider(bare.get).fetch(QUERY, settings))
    }

    @Test
    fun `the key travels as a bearer token, never in the URL`() {
        val http = FakeHttp(mapOf("lyrics.example" to "words"))

        CustomProvider(http.get).fetch(QUERY, settings.copy(apiKey = "secret"))

        assertEquals("Bearer secret", http.lastHeaders["Authorization"])
        assertFalse(http.requested.single().contains("secret"))
    }

    @Test
    fun `an endpoint that already has a query string keeps it`() {
        val http = FakeHttp(mapOf("lyrics.example" to "words"))

        CustomProvider(http.get).fetch(QUERY, settings.copy(baseUrl = "https://lyrics.example/api?v=2"))

        assertTrue(http.requested.single().contains("?v=2&artist="))
    }

    @Test
    fun `a plaintext endpoint is refused before anything is sent`() {
        assertFalse(CustomProvider().isConfigured(ProviderSettings(baseUrl = "http://insecure")))
        assertTrue(CustomProvider().isConfigured(ProviderSettings(baseUrl = "https://secure")))
    }
}
