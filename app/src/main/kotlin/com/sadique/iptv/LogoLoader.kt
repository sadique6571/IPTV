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
 */
internal object LogoLoader {
    private const val MAX_LOGO_BYTES = 512 * 1024
    private const val MAX_DIMENSION = 192
    private val memoryCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool: ExecutorService = Executors.newFixedThreadPool(3)

    fun load(context: Context, url: String, target: ImageView) {
        target.tag = url
        if (url.isEmpty()) return
        memoryCache.get(url)?.let {
            if (target.tag == url) target.setImageBitmap(it)
            return
        }
        val appContext = context.applicationContext
        pool.execute {
            try {
                val bitmap = fetch(appContext, url) ?: return@execute
                memoryCache.put(url, bitmap)
                target.post { if (target.tag == url) target.setImageBitmap(bitmap) }
            } catch (_: IOException) {
                // Logo stays blank; the letter tile behind it remains visible.
            }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    private fun fetch(context: Context, url: String): Bitmap? {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host == null) return null
        val cacheDir = File(context.cacheDir, "logos").apply { mkdirs() }
        val file = File(cacheDir, sha256(url) + ".img")
        if (file.exists() && file.length() in 1..MAX_LOGO_BYTES.toLong()) {
            decode(file.readBytes())?.let { return it }
            file.delete()
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
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
            val bitmap = decode(bytes) ?: return null
            runCatching { FileOutputStream(file).use { it.write(bytes) } }
            return bitmap
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
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
