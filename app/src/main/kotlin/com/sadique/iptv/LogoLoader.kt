package com.sadique.iptv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Small async channel-logo loader: memory LRU cache plus a disk cache under
 * cacheDir. No image-library dependency — logos are tiny and few are on screen
 * at once. A failed logo simply leaves the letter tile behind it visible.
 *
 * Two logo forms are supported: an http(s) URL (downloaded and cached), and
 * "asset:<drawable-name>" (a PNG bundled in the APK, e.g. "asset:logo_hstv"
 * for channels whose logo exists nowhere online).
 */
internal object LogoLoader {
    private const val MAX_LOGO_BYTES = 512 * 1024
    private const val MAX_DIMENSION = 192
    private val memoryCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var pool: ExecutorService = Executors.newFixedThreadPool(6)

    // maxDimension: the hero banner loads posters larger (512) than
    // the 192px tiles — the cache key carries the size so a small
    // bitmap is never served to a large target (or vice versa).
    fun load(context: Context, url: String, target: ImageView, maxDimension: Int = MAX_DIMENSION) {
        target.tag = url
        if (url.isEmpty()) return
        val cacheKey = "$url|$maxDimension"
        memoryCache.get(cacheKey)?.let {
            if (target.tag == url) target.setImageBitmap(it)
            return
        }
        // The pool is shut down when the activity is really destroyed (Back
        // out). Reopening the app in the same process reuses this singleton,
        // so a dead pool must be recreated — otherwise the first logo load
        // throws RejectedExecutionException and crashes the app.
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(6)
        val appContext = context.applicationContext
        pool.execute {
            try {
                val bitmap = fetch(appContext, url, maxDimension) ?: return@execute
                memoryCache.put(cacheKey, bitmap)
                target.post { if (target.tag == url) target.setImageBitmap(bitmap) }
            } catch (_: IOException) {
                // Logo stays blank; the letter tile behind it remains visible.
            }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    private fun fetch(context: Context, url: String, maxDimension: Int): Bitmap? {
        // Bundled manual logo — no network, no disk cache needed.
        if (url.startsWith("asset:")) return fetchAsset(context, url.substringAfter("asset:"), maxDimension)
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host == null) return null
        val cacheDir = File(context.cacheDir, "logos").apply { mkdirs() }
        val file = File(cacheDir, sha256(url) + ".img")
        if (file.exists() && file.length() in 1..MAX_LOGO_BYTES.toLong()) {
            decode(file.readBytes(), maxDimension)?.let { return it }
            file.delete()
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        // Some CDNs (notably Indian telecom image hosts) reject requests
        // without a browser User-Agent, so send one plus an image Accept
        // header; follow redirects explicitly.
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
        )
        connection.setRequestProperty("Accept", "image/*")
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        try {
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > MAX_LOGO_BYTES) return null
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val bitmap = decode(bytes, maxDimension) ?: return null
            runCatching { FileOutputStream(file).use { it.write(bytes) } }
            return bitmap
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Loads a bundled drawable by name ("asset:logo_hstv" -> R.drawable.logo_hstv),
     * downsampled to the same MAX_DIMENSION as network logos. Returns null when
     * the drawable does not exist, so the letter tile behind stays visible.
     */
    private fun fetchAsset(context: Context, name: String, maxDimension: Int): Bitmap? {
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (resId == 0) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    private fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
