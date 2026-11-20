package com.sadique.iptv

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Download engine (v2.0.35): resolves a fresh direct mp4 for a movie
 * or series episode and downloads it with HttpURLConnection + Range
 * resume. Runs on the dedicated download executor, never on `imports`
 * (a 35-minute download would block every search/details call behind
 * it). A .part file keeps partial progress; the index entry is created
 * when the download starts, so an interruption leaves a resumable
 * entry instead of losing the progress.
 */
private const val DOWNLOAD_MARGIN_BYTES = 50L * 1024 * 1024
private const val DOWNLOAD_UNKNOWN_MIN_BYTES = 500L * 1024 * 1024

/** Resolves a fresh direct (non-DASH) mp4 for download: the signed URL
 * is valid for hours, so it is resolved at download start, never
 * stored. Returns null when only DASH streams exist. Blocking — call
 * off the main thread. */
internal fun MainActivity.resolveDownloadStream(
    subjectId: String,
    quality: Int,
    season: Int,
    episode: Int,
    known: MovieBox.Details,
): MovieBox.MovieStream? {
    val details = if (subjectId == known.movie.subjectId) {
        known
    } else {
        movieBox.details(subjectId)
    }
    val streams = if (details.movie.isSeries) {
        movieBox.episodeStreams(details, season, episode)
    } else {
        movieBox.streams(details)
    }
    val direct = streams.filter { !it.isDash }
    return direct.firstOrNull { it.resolution == quality } ?: direct.firstOrNull()
}

/** Downloads one file with Range resume: a .part file keeps partial
 * progress, so an interrupted download continues where it stopped
 * instead of starting over. Runs on the download executor. */
internal fun MainActivity.downloadFile(entry: DownloadedMovie, stream: MovieBox.MovieStream) {
    val dir = downloadsDir()
    val partFile = File(dir, entry.fileName + ".part")
    val finalFile = File(dir, entry.fileName)
    var downloaded = if (partFile.exists()) partFile.length() else 0L
    // A stale .part at least as big as the file itself is corrupt —
    // start over instead of appending past the end.
    if (stream.sizeBytes > 0 && downloaded >= stream.sizeBytes) downloaded = 0L
    // Free-space check: the remaining bytes + a 50 MB margin, or
    // 500 MB when the size is unknown.
    val remaining = if (stream.sizeBytes > 0) {
        (stream.sizeBytes - downloaded).coerceAtLeast(0L)
    } else {
        0L
    }
    val needBytes = if (stream.sizeBytes > 0) {
        remaining + DOWNLOAD_MARGIN_BYTES
    } else {
        DOWNLOAD_UNKNOWN_MIN_BYTES
    }
    if (dir.usableSpace < needBytes) {
        runOnUiThread {
            downloadActive = false
            removeDownloadEntry(entry.key)
            partFile.delete()
            toast("Jagah kam hai — download ke liye ${formatSize(needBytes)} chahiye.")
            movieDetailsStatus.text = "Download nahi ho saki: storage mein jagah kam hai."
        }
        return
    }
    var connection: HttpURLConnection? = null
    try {
        connection = URL(stream.url).openConnection() as HttpURLConnection
        for ((header, value) in stream.headers) connection.setRequestProperty(header, value)
        if (downloaded > 0) connection.setRequestProperty("Range", "bytes=$downloaded-")
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.instanceFollowRedirects = true
        connection.connect()
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_OK && downloaded > 0) {
            // Server ignored Range — restart from zero.
            downloaded = 0L
            partFile.delete()
        } else if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw IOException("Server ne jawab nahi diya (HTTP $code).")
        }
        val total = if (stream.sizeBytes > 0) {
            stream.sizeBytes
        } else {
            connection.getHeaderField("Content-Length")?.toLongOrNull()?.let { it + downloaded } ?: 0L
        }
        var lastUiUpdate = 0L
        FileOutputStream(partFile, downloaded > 0).use { out ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (downloadCancelFlag.get()) {
                        runOnUiThread {
                            downloadActive = false
                            removeDownloadEntry(entry.key)
                            partFile.delete()
                            toast("Download cancel kar di.")
                            if (moviesOpen && moviesTab == MoviesTab.DOWNLOADS) renderDownloads()
                        }
                        return
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    downloaded += count
                    val now = System.currentTimeMillis()
                    if (now - lastUiUpdate > 500) {
                        lastUiUpdate = now
                        val done = downloaded
                        runOnUiThread { updateDownloadProgress(entry, done, total) }
                    }
                }
            }
        }
        // Done: move the .part file to its final name and mark complete.
        val moved = partFile.renameTo(finalFile) || runCatching {
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
            true
        }.getOrDefault(false)
        if (moved) {
            upsertDownloadEntry(
                entry.copy(
                    complete = true,
                    sizeBytes = if (total > 0) total else downloaded,
                    downloadedAt = System.currentTimeMillis(),
                ),
            )
        }
        runOnUiThread {
            downloadActive = false
            if (moved) {
                toast("Download mukammal — ${entry.title}")
                movieDetailsStatus.text = "Download mukammal."
            } else {
                toast("Download mukammal hui lekin file save nahi ho saki.")
                movieDetailsStatus.text = "Download save nahi ho saki."
            }
            if (moviesOpen && moviesTab == MoviesTab.DOWNLOADS) renderDownloads()
        }
    } catch (error: IOException) {
        // Interrupted (network dropped / app killed): the .part file and
        // the index entry stay, so the user can Resume from the
        // Downloads tab instead of losing the progress.
        runOnUiThread {
            downloadActive = false
            toast("Download ruk gayi — Downloads tab se Resume karein.")
            if (moviesOpen && moviesTab == MoviesTab.DOWNLOADS) renderDownloads()
        }
    } finally {
        connection?.disconnect()
    }
}

private fun MainActivity.updateDownloadProgress(entry: DownloadedMovie, done: Long, total: Long) {
    val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
    downloadProgressBar?.progress = percent
    val text = if (total > 0) {
        "${formatSize(done)} / ${formatSize(total)} · $percent%"
    } else {
        "${formatSize(done)} download ho raha hai…"
    }
    downloadProgressLabel?.text = text
    if (moviesDetailsPane.visibility == View.VISIBLE) {
        movieDetailsStatus.text = "Download: $text"
    }
}

