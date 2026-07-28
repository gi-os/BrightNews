package com.lightrss.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Feed images, downloaded on demand and kept on the phone.
 *
 * Nothing is fetched until a row or article that needs the image is on screen, so image hosts are
 * contacted for the articles you actually look at rather than for every article in the database.
 * Bitmaps are downsampled to the width the screen needs and converted to greyscale for the Light
 * Phone display before they are cached, which also keeps them small in memory.
 */
class ArticleImageStore(
    private val cacheDir: File,
    private val download: suspend (String) -> ByteArray?,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = mutableMapOf<String, Deferred<Bitmap?>>()
    private val inFlightLock = Mutex()
    private val diskLock = Mutex()

    /** A cached bitmap if one is already in memory, without touching disk or the network. */
    fun peek(url: String, targetWidthPx: Int): Bitmap? = memory.get(cacheKey(url, targetWidthPx))

    /**
     * Cached bitmap, or a freshly downloaded one. Concurrent callers asking for the same image
     * share a single download. Returns null when the image is unavailable or unusable.
     */
    suspend fun load(url: String, targetWidthPx: Int): Bitmap? {
        if (!RssParser.isUsableImageUrl(url)) return null
        val key = cacheKey(url, targetWidthPx)
        memory.get(key)?.let { return it }

        val work = inFlightLock.withLock {
            inFlight[key] ?: scope.async { fetch(url, key, targetWidthPx) }.also { inFlight[key] = it }
        }
        return try {
            work.await()
        } finally {
            inFlightLock.withLock { if (inFlight[key] === work) inFlight.remove(key) }
        }
    }

    /** Drops every downloaded image. Articles keep their text. */
    fun clear() {
        memory.evictAll()
        runCatching { cacheDir.listFiles()?.forEach { it.delete() } }
    }

    private suspend fun fetch(url: String, key: String, targetWidthPx: Int): Bitmap? {
        memory.get(key)?.let { return it }
        val file = File(cacheDir, "$key.jpg")
        if (file.exists()) {
            val fromDisk = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            if (fromDisk != null) {
                file.setLastModified(System.currentTimeMillis())
                memory.put(key, fromDisk)
                return fromDisk
            }
        }

        val bytes = runCatching { download(url) }.getOrNull() ?: return null
        val bitmap = decodeGreyscale(bytes, targetWidthPx) ?: return null
        diskLock.withLock {
            runCatching {
                cacheDir.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                trimCache(keep = file)
            }
        }
        memory.put(key, bitmap)
        return bitmap
    }

    /** Deletes the oldest cached images once the folder grows past its budget. */
    private fun trimCache(keep: File) {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= MAX_DISK_CACHE_BYTES) return
            if (file.absolutePath == keep.absolutePath) continue
            total -= file.length()
            file.delete()
        }
    }

    private fun decodeGreyscale(bytes: ByteArray, targetWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth < MIN_IMAGE_EDGE_PX || bounds.outHeight < MIN_IMAGE_EDGE_PX) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidthPx) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            // Greyscale output needs no alpha, and 16-bit pixels quarter the cache footprint.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null

        val scaled = if (decoded.width > targetWidthPx) {
            val height = (decoded.height.toFloat() * targetWidthPx / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetWidthPx, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        val grey = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.RGB_565)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        Canvas(grey).drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle()
        return grey
    }

    private fun cacheKey(url: String, targetWidthPx: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "${digest.take(32)}_$targetWidthPx"
    }

    companion object {
        /** Width used for list thumbnails, in pixels. */
        const val THUMBNAIL_WIDTH_PX = 220

        /** Width used for images inside the reader, matching the Light Phone III panel. */
        const val READER_WIDTH_PX = 1080

        private const val MEMORY_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_DISK_CACHE_BYTES = 24L * 1024 * 1024
        private const val MIN_IMAGE_EDGE_PX = 32
    }
}

/**
 * Loads [url] through [store] once the composable is on screen. Returns null while loading,
 * when images are switched off, or when the image could not be fetched.
 */
@Composable
fun rememberArticleImage(store: ArticleImageStore?, url: String, targetWidthPx: Int): State<Bitmap?> {
    val state = remember(url, targetWidthPx, store) {
        mutableStateOf(store?.peek(url, targetWidthPx))
    }
    LaunchedEffect(url, targetWidthPx, store) {
        if (store == null || url.isBlank() || state.value != null) return@LaunchedEffect
        state.value = store.load(url, targetWidthPx)
    }
    return state
}
