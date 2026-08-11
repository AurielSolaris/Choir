// SPDX-FileCopyrightText: 2026 AurielSolaris
// SPDX-License-Identifier: GPL-3.0-or-later

package app.auriel.choir.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.auriel.choir.core.MusicLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Loads album artwork from a content URI.
 *
 * Choir has no image-loading dependency and does not need one: artwork is a
 * handful of small square bitmaps, decoded downsampled and held in a bounded
 * memory cache. Missing artwork is the common case, not an error — callers draw
 * their own placeholder when this returns `null`.
 */
class AlbumArtLoader(private val context: Context) {

    private data class Key(val uri: Uri, val sizePx: Int)

    /** Bounded by decoded bitmap bytes, capped at an eighth of the heap. */
    private val cache = object : LruCache<Key, ImageBitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: Key, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /**
     * Returns artwork at roughly [sizePx] on its shorter edge, or `null` when
     * there is none to show.
     */
    suspend fun load(uri: Uri?, sizePx: Int): ImageBitmap? {
        if (uri == null || sizePx <= 0) return null

        val key = Key(uri, sizePx)
        cache.get(key)?.let { return it }

        val bitmap = withContext(Dispatchers.IO) { decode(uri, sizePx) } ?: return null
        return bitmap.asImageBitmap().also { cache.put(key, it) }
    }

    private fun decode(uri: Uri, sizePx: Int): Bitmap? = try {
        // Pass one reads the header only, to choose a sample size.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, sizePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }
    } catch (e: FileNotFoundException) {
        null // Overwhelmingly just "this album has no cover".
    } catch (e: IOException) {
        MusicLog.w(TAG, "could not read artwork at $uri", e)
        null
    } catch (e: SecurityException) {
        MusicLog.w(TAG, "artwork at $uri is out of scope", e)
        null
    } catch (e: OutOfMemoryError) {
        MusicLog.w(TAG, "out of memory decoding artwork at $uri")
        cache.evictAll()
        null
    }

    private companion object {
        const val TAG = "AlbumArtLoader"

        /** Largest power of two that keeps the decoded bitmap at or above [target]. */
        fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
            var sample = 1
            var smallerEdge = minOf(width, height)
            while (smallerEdge / 2 >= target) {
                smallerEdge /= 2
                sample *= 2
            }
            return sample
        }

        fun cacheSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 8)
                .coerceIn(4L * 1024 * 1024, Int.MAX_VALUE.toLong())
                .toInt()
    }
}
