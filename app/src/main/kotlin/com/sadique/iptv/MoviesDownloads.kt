package com.sadique.iptv

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.IOException

/**
 * Mobile-only movie downloads (v2.0.35) — the user did not approve TV
 * downloads, so the Downloads tab and the Download button exist only
 * when isTv is false.
 *
 * Mechanism: plain HttpURLConnection with Range resume on a dedicated
 * single-thread executor — NOT ExoPlayer's DownloadManager, which needs
 * a foreground service + notification + manifest entry for one active
 * download. Range resume covers interruptions with far less code: a
 * .part file keeps partial progress, and a Resume re-resolves the
 * stream (signed MovieBox URLs expire within hours, so a stored URL is
 * never trusted) and continues from the .part size. One active download
 * at a time; free space is checked before starting. Files live in the
 * app-specific external dir (no permission needed); the index is a
 * small JSON array in SharedPreferences ("downloads"), the same pattern
 * as MovieStore (see DownloadStore.kt). Downloaded movies play in the
 * main player through a file:// URL (PlayerController's
 * DefaultDataSource handles it).
 *
 * Limits: a download runs while the app process is alive — if Android
 * kills the app mid-download, it does NOT auto-resume; the Downloads
 * tab offers Resume. DASH-only titles cannot be downloaded (a DASH
 * manifest is not one file) and say so.
 */
/** Starts a download of the movie / series episode open in the details
 * pane (mobile only — the button is GONE on TV). Resolves the stream
 * fresh, picks the selected quality (direct mp4 only), checks free
 * space, then downloads on the dedicated executor. */
internal fun MainActivity.startMovieDownload() {
    val details = currentMovieDetails
    if (details == null) {
        toast("Details abhi load ho rahe hain — thori dair baad try karein.")
        return
    }
    if (details.movie.isSeries && selectedEpisode <= 0) {
        toast("Pehle episode chunein.")
        return
    }
    if (downloadActive) {
        toast("Aik download pehle se chal rahi hai — us ke mukammal hone ka intezar karein.")
        return
    }
    val subjectId = selectedDubId ?: details.movie.subjectId
    val quality = selectedQuality
    val season = if (details.movie.isSeries) selectedSeason else 0
    val episode = if (details.movie.isSeries) selectedEpisode else 0
    val key = downloadKey(subjectId, quality, season, episode)
    val saved = downloadedMovies()
    if (saved.any { it.key == key && it.complete }) {
        toast("Ye movie pehle se download hai.")
        return
    }
    if (saved.any { it.key == key && !it.complete }) {
        // Interrupted download — resume it instead of starting over.
        resumeDownload(key)
        return
    }
    downloadActive = true
    downloadActiveKey = key
    downloadCancelFlag.set(false)
    movieDetailsStatus.text = "Download shuru ho rahi hai…"
    downloadExecutor.execute {
        try {
            val stream = resolveDownloadStream(subjectId, quality, season, episode, details)
            if (stream == null) {
                runOnUiThread {
                    downloadActive = false
                    movieDetailsStatus.text = "Download nahi ho saki: is title ka direct link nahi mila."
                    toast("Download nahi ho saki — direct link nahi mila.")
                }
                return@execute
            }
            val fileName = downloadFileName(subjectId, quality, season, episode)
            val title = if (details.movie.isSeries) {
                "${details.movie.title} S$season E$episode"
            } else {
                details.movie.title
            }
            val entry = DownloadedMovie(
                key = key,
                subjectId = subjectId,
                title = title,
                year = details.movie.year,
                posterUrl = details.movie.posterUrl,
                fileName = fileName,
                sizeBytes = stream.sizeBytes,
                quality = quality,
                season = season,
                episode = episode,
                isSeries = details.movie.isSeries,
                downloadedAt = System.currentTimeMillis(),
                complete = false,
            )
            upsertDownloadEntry(entry)
            downloadFile(entry, stream)
        } catch (error: IOException) {
            runOnUiThread {
                downloadActive = false
                movieDetailsStatus.text = "Download nahi ho saki: ${error.message}"
                toast("Download nahi ho saki: ${error.message}")
            }
        }
    }
}

/** Cancels the active download; the loop sees the flag, deletes the
 * partial file and the index entry, and reports on the UI thread. */
internal fun MainActivity.cancelDownload() {
    downloadCancelFlag.set(true)
}

/** Resumes an interrupted download: re-resolves the stream fresh and
 * continues from the .part size. If the fresh stream is a different
 * file (size mismatch), the stale .part is dropped and it restarts. */
internal fun MainActivity.resumeDownload(key: String) {
    if (downloadActive) {
        toast("Aik download pehle se chal rahi hai — us ke mukammal hone ka intezar karein.")
        return
    }
    val entry = downloadedMovies().firstOrNull { it.key == key } ?: return
    downloadActive = true
    downloadActiveKey = key
    downloadCancelFlag.set(false)
    downloadExecutor.execute {
        try {
            val known = movieBox.details(entry.subjectId)
            val stream = resolveDownloadStream(
                entry.subjectId, entry.quality, entry.season, entry.episode, known,
            )
            if (stream == null) {
                runOnUiThread {
                    downloadActive = false
                    toast("Resume nahi ho saka: direct link nahi mila.")
                }
                return@execute
            }
            if (entry.sizeBytes > 0 && stream.sizeBytes > 0 &&
                stream.sizeBytes != entry.sizeBytes
            ) {
                File(downloadsDir(), entry.fileName + ".part").delete()
            }
            downloadFile(entry, stream)
        } catch (error: IOException) {
            runOnUiThread {
                downloadActive = false
                toast("Resume nahi ho saka: ${error.message}")
            }
        }
    }
}

internal fun MainActivity.deleteDownload(entry: DownloadedMovie) {
    if (entry.key == downloadActiveKey && downloadActive) {
        toast("Chal rahi download ko pehle Cancel karein.")
        return
    }
    File(downloadsDir(), entry.fileName).delete()
    File(downloadsDir(), entry.fileName + ".part").delete()
    removeDownloadEntry(entry.key)
    toast("Download delete kar di.")
    renderDownloads()
}

/** Plays a downloaded movie in the main player, from the local file —
 * no internet needed. Continue Watching still saves its position. */
internal fun MainActivity.playDownloadedMovie(entry: DownloadedMovie) {
    val file = File(downloadsDir(), entry.fileName)
    if (!file.exists()) {
        toast("File nahi mili — dobara download karein.")
        removeDownloadEntry(entry.key)
        renderDownloads()
        return
    }
    closeMovies()
    // A local file has no alternate qualities — drop any stale stream
    // list so the quality switcher cannot offer another title's
    // streams for this playback.
    currentMovieStreams = emptyList()
    playMovieStream(
        MovieBox.Movie(
            subjectId = entry.subjectId,
            title = entry.title,
            year = entry.year,
            posterUrl = entry.posterUrl,
            isSeries = entry.isSeries,
        ),
        "file://" + file.absolutePath,
        emptyMap(),
        episodeSeason = entry.season,
        episodeNumber = entry.episode,
    )
    applyFullScreen(true)
}

/** Downloads tab (mobile only): the active download with progress,
 * saved movies with Play + Delete, and interrupted ones with Resume. */
internal fun MainActivity.renderDownloads() {
    movieResults.removeAllViews()
    downloadProgressBar = null
    downloadProgressLabel = null
    val saved = downloadedMovies()
    movieStatus.text = when {
        saved.isEmpty() && !downloadActive ->
            "Abhi koi download nahi hai — movie khol kar Download dabayein."
        downloadActive -> "Download chal rahi hai…"
        else -> "Downloads — ${saved.size} movie(s)"
    }
    val rows = mutableListOf<View>()
    for (entry in saved) {
        val row = downloadRow(entry)
        rows += row
        movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    linkMovieRowActions(rows)
    setFirstContentBackLink(rows.firstOrNull())
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}

private fun MainActivity.downloadRow(entry: DownloadedMovie): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        minimumHeight = dp(88)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rowBackground(false)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        if (entry.complete) setOnClickListener { playDownloadedMovie(entry) }
    }
    row.addView(
        posterTile(entry.posterUrl, entry.title),
        LinearLayout.LayoutParams(dp(56), dp(84)),
    )
    val texts = column()
    texts.addView(TextView(this).apply {
        text = entry.title
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    val meta = TextView(this).apply {
        text = downloadMetaText(entry)
        textSize = 13f
        setTextColor(muted2)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    texts.addView(meta)
    if (entry.key == downloadActiveKey && downloadActive) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(cyan)
            progressBackgroundTintList = ColorStateList.valueOf(glassHi)
        }
        downloadProgressBar = bar
        downloadProgressLabel = meta
        texts.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply {
            topMargin = dp(6)
        })
    }
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    val (actionText, action) = when {
        entry.key == downloadActiveKey && downloadActive ->
            "Cancel" to { cancelDownload() }
        !entry.complete ->
            "Resume" to { resumeDownload(entry.key) }
        else ->
            "Delete" to { deleteDownload(entry) }
    }
    val actionButton = button(actionText) { action() }
    actionButton.id = View.generateViewId()
    actionButton.tag = "rowAction"
    row.nextFocusRightId = actionButton.id
    actionButton.nextFocusLeftId = row.id
    row.addView(actionButton, LinearLayout.LayoutParams(-2, -2).apply {
        marginStart = dp(8)
    })
    return row
}

private fun MainActivity.downloadMetaText(entry: DownloadedMovie): String {
    val quality = if (entry.quality > 0) "${entry.quality}p" else "Auto"
    val size = if (entry.sizeBytes > 0) formatSize(entry.sizeBytes) else ""
    return when {
        entry.key == downloadActiveKey && downloadActive -> "Download ho rahi hai…"
        !entry.complete -> "Adhoori download · $quality" +
            if (size.isNotEmpty()) " · $size" else ""
        else -> quality + if (size.isNotEmpty()) " · $size" else ""
    }
}
