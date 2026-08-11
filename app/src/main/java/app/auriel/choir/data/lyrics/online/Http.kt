// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data.lyrics.online

import app.auriel.choir.BuildConfig

import app.auriel.choir.core.MusicLog
import java.net.HttpURLConnection
import java.net.URL

/**
 * The smallest HTTP client that will do.
 *
 * `HttpURLConnection` rather than OkHttp: this makes a handful of small GET
 * requests, and Media3's OkHttp is an implementation detail of the player that
 * the lyric code has no business reaching into.
 *
 * Everything about it is deliberately narrow — GET only, HTTPS only, a hard cap
 * on the response size, short timeouts, no cookies, no redirects off HTTPS.
 * This is the only code in Choir that talks to anyone.
 */
internal object Http {

    private const val TAG = "Http"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 8_000

    /** A lyric is text. Anything this large is not one. */
    private const val MAX_RESPONSE_BYTES = 512 * 1024

    /**
     * Identifies Choir honestly; LRCLIB asks for it and it costs nothing.
     *
     * Read from the build rather than typed out, because a hardcoded version
     * goes stale the first time one is released and then quietly lies.
     */
    private val USER_AGENT =
        "Choir/${BuildConfig.VERSION_NAME} (https://github.com/AurielSolaris/Choir)"

    /**
     * @return the body on 200, or null on anything else — a 404 for a track
     *   nobody has lyrics for is the normal case, not an error worth surfacing.
     */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String? {
        // Refuse plaintext outright rather than relying on the manifest to.
        if (!url.startsWith("https://", ignoreCase = true)) {
            MusicLog.w(TAG, "refusing a non-HTTPS request")
            return null
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json, text/plain")
                headers.forEach(::setRequestProperty)
            }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                MusicLog.d(TAG, "request returned $code")
                return null
            }

            connection.inputStream.use { stream ->
                val buffer = ByteArray(16 * 1024)
                // Bytes first, decoded once at the end: a chunk boundary lands
                // in the middle of a multi-byte character often enough that
                // decoding per chunk would corrupt accented text.
                val bytes = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (bytes.size() + read > MAX_RESPONSE_BYTES) {
                        MusicLog.w(TAG, "response exceeded the size cap; discarding")
                        return null
                    }
                    bytes.write(buffer, 0, read)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
        } catch (e: Exception) {
            // No network, DNS failure, timeout, TLS problem — all the same to a
            // caller that simply has no lyrics to show.
            MusicLog.d(TAG, "request failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** Percent-encodes a query parameter value. */
internal fun String.urlEncoded(): String =
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
