package com.sadique.iptv

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Downloads index (v2.0.35): the saved-downloads list for
 * MoviesDownloads.kt — a small JSON array in SharedPreferences
 * ("downloads"), the same pattern as MovieStore, plus the downloads
 * directory and the file/key naming. Stream URLs are NEVER stored
 * here: MovieBox links are signed and expire within hours, so a
 * Resume re-resolves from the subjectId at tap time.
 */
internal data class DownloadedMovie(
    val key: String,
    val subjectId: String,
    val title: String,
    val year: String,
    val posterUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val quality: Int,
    val season: Int,
    val episode: Int,
    val isSeries: Boolean,
    val downloadedAt: Long,
    val complete: Boolean,
)

private const val DOWNLOADS_PREFS = "downloads"
private const val DOWNLOADS_KEY = "saved"

private fun MainActivity.downloadPrefs() =
    getSharedPreferences(DOWNLOADS_PREFS, Context.MODE_PRIVATE)

/** App-specific downloads dir (external when available — movie files
 * are 1-4 GB — internal storage as a fallback). No permission needed. */
internal fun MainActivity.downloadsDir(): File {
    val dir = getExternalFilesDir(null)?.let { File(it, "downloads") }
        ?: File(filesDir, "downloads")
    dir.mkdirs()
    return dir
}

internal fun MainActivity.downloadedMovies(): List<DownloadedMovie> {
    val list = mutableListOf<DownloadedMovie>()
    try {
        val raw = downloadPrefs().getString(DOWNLOADS_KEY, null) ?: return list
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("key", "")
            if (key.isEmpty()) continue
            list += DownloadedMovie(
                key = key,
                subjectId = o.optString("id", ""),
                title = o.optString("title", ""),
                year = o.optString("year", ""),
                posterUrl = o.optString("poster", ""),
                fileName = o.optString("file", ""),
                sizeBytes = o.optLong("size", 0L),
                quality = o.optInt("q", 0),
                season = o.optInt("se", 0),
                episode = o.optInt("ep", 0),
                isSeries = o.optBoolean("series", false),
                downloadedAt = o.optLong("ts", 0L),
                complete = o.optBoolean("done", false),
            )
        }
    } catch (_: Exception) {
        // Corrupt prefs — start empty rather than crashing.
    }
    return list
}

internal fun MainActivity.upsertDownloadEntry(entry: DownloadedMovie) {
    val list = downloadedMovies().toMutableList()
    list.removeAll { it.key == entry.key }
    list.add(0, entry)
    persistDownloads(list)
}

internal fun MainActivity.removeDownloadEntry(key: String) {
    val list = downloadedMovies().toMutableList()
    if (list.removeAll { it.key == key }) persistDownloads(list)
}

private fun MainActivity.persistDownloads(list: List<DownloadedMovie>) {
    try {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("key", e.key)
                put("id", e.subjectId)
                put("title", e.title)
                put("year", e.year)
                put("poster", e.posterUrl)
                put("file", e.fileName)
                put("size", e.sizeBytes)
                put("q", e.quality)
                put("se", e.season)
                put("ep", e.episode)
                put("series", e.isSeries)
                put("ts", e.downloadedAt)
                put("done", e.complete)
            })
        }
        downloadPrefs().edit().putString(DOWNLOADS_KEY, arr.toString()).apply()
    } catch (_: Exception) {
        // Best-effort; a failed index write must not break playback.
    }
}

internal fun downloadKey(subjectId: String, quality: Int, season: Int, episode: Int) =
    "$subjectId|$quality|$season|$episode"

internal fun downloadFileName(subjectId: String, quality: Int, season: Int, episode: Int): String {
    val safeId = subjectId.filter { it.isLetterOrDigit() }.take(24).ifEmpty { "movie" }
    return "dl_${safeId}_${quality}_${season}_${episode}.mp4"
}

