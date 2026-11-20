package com.sadique.iptv

import android.app.ActivityManager
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
 * Small async channel-logo / movie-poster loader: memory LRU cache plus
 * a disk cache under cacheDir. No image-library dependency — logos are
 * tiny and few are on screen at once. A failed logo simply leaves the
 * letter tile behind it visible.
 *
 * Two logo forms are supported: an http(s) URL (downloaded and cached), and
 * "asset:<drawable-name>" (a PNG bundled in the APK, e.g. "asset:logo_hstv"
 * for channels whose logo exists nowhere online).
 *
 * Speed (v2.0.36): the memory cache is sized from the device's memory
 * class (1/8 of the heap, 16–64 MB) instead of a fixed 8 MB, so a home
 * full of posters no longer evicts itself on every render; in-flight
 * requests are deduplicated (the same poster asked twice downloads
 * once); connections are kept alive instead of disconnected after every
 * poster (no fresh TLS handshake per image); and off-screen posters
 * load on a separate 2-thread pool so they never queue behind — or
 * delay — the visible ones (see load's `background` flag and prefetch).
 */
internal object LogoLoader {
    private const val MAX_LOGO_BYTES = 512 * 1024
    private const val MAX_DIMENSION = 192
    private const val CONNECT_TIMEOUT_MS = 6000
    private const val READ_TIMEOUT_MS = 10000
    private const val MIN_CACHE_MB = 16
    private const val MAX_CACHE_MB = 64

    // Lazily sized from ActivityManager.memoryClass on first use (needs
    // a Context): 1/8 of the app's heap, clamped to 16–64 MB. A 512px
    // hero poster is ~1.5 MB and a 192px tile ~0.15 MB, so the old fixed
    // 8 MB cache held only a few posters before evicting.
    private var memoryCache: LruCache<String, Bitmap>? = null

    private fun cache(context: Context): LruCache<String, Bitmap> {
        synchronized(this) {
            memoryCache?.let { return it }
            val activityManager = context.getSystemService(ActivityManager::class.java)
            val memoryClassMb = activityManager?.memoryClass ?: 128
            val cacheBytes = (memoryClassMb / 8).coerceIn(MIN_CACHE_MB, MAX_CACHE_MB) * 1024 * 1024
            return object : LruCache<String, Bitmap>(cacheBytes) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            }.also { memoryCache = it }
        }
    }

    private var pool: ExecutorService = Executors.newFixedThreadPool(6)
    private var prefetchPool: ExecutorService = Executors.newFixedThreadPool(2)

    // In-flight requests (cacheKey -> waiting targets): a poster asked
    // twice while its first download is running attaches to the same
    // fetch instead of downloading twice. Guarded by `pending` itself.
    private val pending = HashMap<String, MutableList<ImageView>>()

    // maxDimension: the hero banner loads posters larger (512) than
    // the 192px tiles — the cache key carries the size so a small
    // bitmap is never served to a large target (or vice versa).
    // background: off-screen posters run on the small prefetch pool so
    // they never queue behind (or delay) the visible ones.
    fun load(
        context: Context,
        url: String,
        target: ImageView,
        maxDimension: Int = MAX_DIMENSION,
        background: Boolean = false,
    ) {
        target.tag = url
        if (url.isEmpty()) return
        val cacheKey = "$url|$maxDimension"
        cache(context).get(cacheKey)?.let {
            if (target.tag == url) target.setImageBitmap(it)
            return
        }
        if (!claimFetch(cacheKey, target)) return
        executor(background).execute {
            runFetch(context.applicationContext, cacheKey, url, maxDimension)
        }
    }

    /** Warms the cache for posters with no target yet (e.g. the next
     * hero poster) — same dedup, same prefetch pool, no ImageView
     * attached. A URL already in the memory cache is skipped. */
    fun prefetch(context: Context, urls: List<String>, maxDimension: Int = MAX_DIMENSION) {
        val appContext = context.applicationContext
        for (url in urls) {
            if (url.isEmpty()) continue
            val cacheKey = "$url|$maxDimension"
            if (cache(appContext).get(cacheKey) != null) continue
            if (!claimFetch(cacheKey, null)) continue
            executor(background = true).execute {
                runFetch(appContext, cacheKey, url, maxDimension)
            }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
        prefetchPool.shutdownNow()
    }

    // Returns true when this caller owns the fetch; false when the
    // request attached to an in-flight one (or was already pending).
    private fun claimFetch(cacheKey: String, target: ImageView?): Boolean {
        synchronized(pending) {
            val waiting = pending[cacheKey]
            if (waiting != null) {
                if (target != null) waiting.add(target)
                return false
            }
            pending[cacheKey] =
                if (target != null) mutableListOf(target) else mutableListOf()
            return true
        }
    }

    private fun executor(background: Boolean): ExecutorService {
        // The pools are shut down when the activity is really destroyed
        // (Back out). Reopening the app in the same process reuses this
        // singleton, so a dead pool must be recreated — otherwise the
        // first logo load throws RejectedExecutionException and crashes
        // the app.
        if (background) {
            if (prefetchPool.isShutdown) prefetchPool = Executors.newFixedThreadPool(2)
            return prefetchPool
        }
        if (pool.isShutdown) pool = Executors.newFixedThreadPool(6)
        return pool
    }

    private fun runFetch(context: Context, cacheKey: String, url: String, maxDimension: Int) {
        try {
            val bitmap = fetch(context, url, maxDimension)
            val targets = synchronized(pending) { pending.remove(cacheKey) } ?: emptyList()
            if (bitmap == null) return
            cache(context).put(cacheKey, bitmap)
            for (target in targets) {
                target.post { if (target.tag == url) target.setImageBitmap(bitmap) }
            }
        } catch (_: IOException) {
            // Logo stays blank; the letter tile behind it remains visible.
            synchronized(pending) { pending.remove(cacheKey) }
        }
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
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        // No disconnect() here on purpose: the input stream is closed
        // by `use` below, which returns the connection to the JVM's
        // keep-alive pool — disconnecting after every poster forced a
        // fresh TCP + TLS handshake for the next image from the same
        // host, which is most of what made poster loading feel slow.
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
