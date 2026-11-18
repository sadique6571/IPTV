package com.sadique.iptv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub auto-update: checks the latest public release of sadique6571/IPTV
 * (repo is public, so no token or API key is needed) and installs it on the
 * user's say-so. Nothing downloads or installs without the user tapping
 * Download in the update dialog.
 *
 * Release tag convention (set by .github/workflows/android.yml):
 * v<versionName>.<versionCode>, e.g. v2.0.12 — the versionCode is the LAST
 * dot segment. Bump versionCode in app/build.gradle.kts for every build
 * users should be offered.
 *
 * All network calls MUST run off the main thread (MainActivity uses its
 * `imports` executor). Network/parse failures throw IOException; callers
 * decide whether to stay silent (automatic check) or toast (manual check).
 */
internal object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/sadique6571/IPTV/releases/latest"
    private const val APK_ASSET_NAME = "IPTV-debug.apk"
    private const val PREFS_NAME = "iptv_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

    data class Update(val tag: String, val versionCode: Long, val downloadUrl: String)

    /** True when a check is due: never checked, or 24h since the last one. */
    fun isCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last >= CHECK_INTERVAL_MS
    }

    /** Records a completed check (call after a successful fetch only). */
    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * Queries the latest release. Returns the update when the release's
     * versionCode is newer than the installed one, null when up to date or
     * when the release has no usable APK asset.
     */
    @Throws(IOException::class)
    fun fetchLatest(context: Context): Update? {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            // GitHub's API rejects requests without a User-Agent (403).
            connection.setRequestProperty("User-Agent", "IPTV-App")
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub API: HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = try {
                JSONObject(body)
            } catch (error: JSONException) {
                throw IOException("Bad release response: ${error.message}")
            }
            val tag = json.optString("tag_name", "")
            val remoteCode = parseVersionCode(tag) ?: return null
            if (remoteCode <= installedVersionCode(context)) return null
            val assets = json.optJSONArray("assets") ?: return null
            var downloadUrl: String? = null
            var firstApk: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (url.isEmpty()) continue
                if (firstApk == null && name.endsWith(".apk", ignoreCase = true)) firstApk = url
                if (name == APK_ASSET_NAME) {
                    downloadUrl = url
                    break
                }
            }
            val finalUrl = downloadUrl ?: firstApk ?: return null
            return Update(tag, remoteCode, finalUrl)
        } finally {
            connection.disconnect()
        }
    }

    /** "v2.0.12" -> 12. Returns null when the tag carries no version code. */
    internal fun parseVersionCode(tag: String): Long? {
        val clean = tag.trim().removePrefix("v")
        val last = clean.substringAfterLast('.', "")
        if (last.isEmpty()) return null
        return last.toLongOrNull()
    }

    @Suppress("DEPRECATION")
    private fun installedVersionCode(context: Context): Long {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getPackageInfo(context.packageName, 0)
        }
        // longVersionCode only exists on API 28+; older devices use versionCode.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    /**
     * Downloads the release APK into cacheDir/updates/ (served to the
     * installer through FileProvider). Calls [onProgress] with 0..100 on the
     * calling (worker) thread whenever the percentage changes; no calls when
     * the server gives no content length. Returns the downloaded file.
     */
    @Throws(IOException::class)
    fun downloadApk(context: Context, update: Update, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, APK_ASSET_NAME)
        val connection = URL(update.downloadUrl).openConnection() as HttpURLConnection
        try {
            // browser_download_url redirects to the release asset host.
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "IPTV-App")
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download: HTTP $code")
            val total = connection.contentLengthLong
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return out
        } finally {
            connection.disconnect()
        }
    }

    /** Fires the system package installer for a downloaded APK. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
