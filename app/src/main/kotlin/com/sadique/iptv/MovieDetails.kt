package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Movie details, dubs, quality, streams, resume and Continue Watching saves.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


/** Seeks the playing movie by a relative amount, clamped to its
 * length; the seek UI updates at once (the 500ms ticker catches up
 * after). */
internal fun MainActivity.seekMovieBy(deltaMs: Long) {
    val controller = playerController ?: return
    if (playingMovie == null) return
    val duration = controller.duration()
    val target = if (duration > 0) {
        (controller.position() + deltaMs).coerceIn(0L, duration)
    } else {
        (controller.position() + deltaMs).coerceAtLeast(0L)
    }
    controller.seekTo(target)
    showSeekIndicator(deltaMs)
    if (duration > 0) {
        seekBar.max = (duration / 1000).toInt()
        seekBar.progress = (target / 1000).toInt()
        updateMovieTimes(target, duration)
    }
    showPlayerControls()
}


/** Resume a Continue Watching movie or series episode: the signed
 * stream URL is never stored (it expires within hours), so details +
 * streams are resolved fresh from the subjectId (and season/episode
 * for series), then it plays at the saved position — the same quality
 * as before when it is still available. */
internal fun MainActivity.resumeMovie(entry: MovieStore.ContinueEntry, fullscreenOnSuccess: Boolean = true) {
    val request = ++movieResumeRequest
    ensureMoviesView()
    movieProgress.visibility = View.VISIBLE
    movieStatus.text = "Movie load ho rahi hai…"
    imports.execute {
        try {
            val details = movieBox.details(entry.subjectId)
            val streams = if (entry.isSeries) {
                movieBox.episodeStreams(details, entry.season, entry.episode)
            } else {
                movieBox.streams(details)
            }
            // Series resume: also load the seasons (best-effort — a
            // failure here must not break the resume) so Prev/Next
            // and Up Next know the episode count, like after Play
            // from the details screen. Without this, Next stayed
            // hidden after a Continue Watching resume.
            val seasons = if (entry.isSeries) {
                try {
                    movieBox.seasons(details.movie.subjectId)
                } catch (_: IOException) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            runOnUiThread {
                if (isDestroyed || request != movieResumeRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                // The resume fetched the details — keep them. A fresh
                // app start has no currentMovieDetails/currentPlayTitle,
                // so without this the top-bar title fallbacks were
                // stale after a Continue Watching resume.
                currentMovieDetails = details
                currentPlayTitle = details.movie.title
                // firstOrNull, never first(): an empty stream list used
                // to throw on the UI thread and crash the app.
                val stream = streams.firstOrNull { it.resolution == entry.quality }
                    ?: streams.firstOrNull()
                if (stream == null) {
                    movieStatus.text = "Resume nahi ho saka: koi playable link nahi mila."
                    status.text = movieStatus.text
                    return@runOnUiThread
                }
                // Keep the resolved streams: the in-player quality
                // switcher offers these after a resume, same as after
                // picking a quality on the details screen.
                currentMovieStreams = streams
                if (entry.isSeries) currentSeriesSeasons = seasons
                selectedQuality = stream.resolution
                currentSubtitle = subtitleForStream(stream, details)
                closeMovies()
                playMovieStream(
                    // Stored Continue Watching titles can be blank
                    // (old entries); the details title is right here,
                    // so prefer it — a blank title left the top bar
                    // showing only the back arrow, no title.
                    details.movie.copy(title = entry.title.ifBlank { details.movie.title }),
                    stream.url, stream.headers, entry.positionMs,
                    episodeSeason = if (entry.isSeries) entry.season else 0,
                    episodeNumber = if (entry.isSeries) entry.episode else 0,
                    dubs = details.audioDubs(),
                )
                applyFullScreen(fullscreenOnSuccess)
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieResumeRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                movieStatus.text = "Resume nahi ho saka. Dobara koshish karein."
                status.text = movieStatus.text
            }
        }
    }
}


/**
 * Saves the playing movie's position to Continue Watching. The
 * position is read here on the main thread (ExoPlayer's thread); the
 * store write runs on `imports` for the periodic tick (async) and
 * synchronously for lifecycle hooks that run right before the player
 * is released (sync) — either way the player is never blocked. A
 * movie past 95% is finished: the store drops it instead of saving.
 */
internal fun MainActivity.saveMovieProgress(async: Boolean) {
    val movie = playingMovie ?: return
    if (movie.subjectId.isEmpty()) return
    val controller = playerController ?: return
    val position = controller.position()
    val duration = controller.duration()
    if (duration <= 0 || position < MIN_SAVE_POSITION_MS) return
    val entry = MovieStore.ContinueEntry(
        subjectId = movie.subjectId,
        title = movie.title,
        posterUrl = movie.posterUrl,
        positionMs = position,
        durationMs = duration,
        quality = selectedQuality,
        updatedAt = System.currentTimeMillis(),
        season = playingEpisodeSeason,
        episode = playingEpisodeEpisode,
        isSeries = movie.isSeries,
    )
    if (async) {
        imports.execute { movieStore.saveProgress(entry) }
    } else {
        movieStore.saveProgress(entry)
    }
}


internal fun MainActivity.openMovieDetails(movie: MovieBox.Movie) {
    movieResumeRequest++
    movieStreamsRequest++
    movieSeasonsRequest++
    val request = ++movieDetailsRequest
    currentMovieDetails = null
    movieDetailsRendered = false
    currentMovieStreams = emptyList()
    currentPlayTitle = movie.title
    selectedDubId = movie.subjectId
    selectedQuality = 0
    currentSubtitle = null
    subtitlesEnabled = true
    movieDetailsTitle.text = movie.title
    movieDetailsMeta.text = ""
    movieDetailsDesc.text = ""
    movieQualityRow.removeAllViews()
    movieSeasonRow.removeAllViews()
    movieEpisodeColumn.removeAllViews()
    movieSeasonLabel.visibility = View.GONE
    movieSeasonScroll.visibility = View.GONE
    movieEpisodeLabel.visibility = View.GONE
    movieEpisodeColumn.visibility = View.GONE
    selectedSeason = 0
    selectedEpisode = 0
    currentSeriesSeasons = emptyList()
    moviePlayButton.isEnabled = false
    movieDetailsStatus.text = "Details load ho rahe hain…"
    updateMovieFavButton()
    showMoviesDetails()
    imports.execute {
        try {
            val details = movieBox.details(movie.subjectId)
            runOnUiThread {
                if (isDestroyed || request != movieDetailsRequest) return@runOnUiThread
                if (details.movie.isSeries) showSeriesDetails(details) else showMovieDetails(details)
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieDetailsRequest) return@runOnUiThread
                movieDetailsStatus.text = "Details nahi mile: ${error.message}"
            }
        }
    }
}


internal fun MainActivity.showMovieDetails(details: MovieBox.Details) {
    currentMovieDetails = details
    movieDetailsRendered = true
    currentPlayTitle = details.movie.title
    selectedDubId = details.movie.subjectId
    updateMovieFavButton()
    movieDetailsTitle.text = details.movie.title
    val metaParts = mutableListOf<String>()
    if (details.movie.year.isNotEmpty()) metaParts += details.movie.year
    if (details.durationSec > 0) metaParts += formatDuration(details.durationSec)
    if (details.rating.isNotEmpty()) metaParts += "★ ${details.rating}"
    if (details.genres.isNotEmpty()) metaParts += details.genres.take(3).joinToString(", ")
    movieDetailsMeta.text = metaParts.joinToString(" · ")
    val descParts = mutableListOf<String>()
    if (details.description.isNotEmpty()) descParts += details.description
    if (details.director.isNotEmpty()) descParts += "Director: ${details.director}"
    if (details.stars.isNotEmpty()) descParts += "Cast: ${details.stars}"
    movieDetailsDesc.text = descParts.joinToString("\n\n")
    loadMovieStreams()
}


/** Series details: same title/meta/description as a movie, but no
 * runtime (a series has no single length) and no upfront streams —
 * seasons load first, then the picked episode's streams. */
internal fun MainActivity.showSeriesDetails(details: MovieBox.Details) {
    currentMovieDetails = details
    movieDetailsRendered = true
    currentPlayTitle = details.movie.title
    selectedDubId = details.movie.subjectId
    updateMovieFavButton()
    movieDetailsTitle.text = details.movie.title
    val metaParts = mutableListOf<String>()
    if (details.movie.year.isNotEmpty()) metaParts += details.movie.year
    if (details.rating.isNotEmpty()) metaParts += "★ ${details.rating}"
    if (details.genres.isNotEmpty()) metaParts += details.genres.take(3).joinToString(", ")
    metaParts += "Series"
    movieDetailsMeta.text = metaParts.joinToString(" · ")
    val descParts = mutableListOf<String>()
    if (details.description.isNotEmpty()) descParts += details.description
    if (details.director.isNotEmpty()) descParts += "Director: ${details.director}"
    if (details.stars.isNotEmpty()) descParts += "Cast: ${details.stars}"
    movieDetailsDesc.text = descParts.joinToString("\n\n")
    loadSeriesSeasons()
}


internal fun MainActivity.markChipByTag(row: ViewGroup, tag: Any?) {
    for (i in 0 until row.childCount) {
        val child = row.getChildAt(i)
        when (child) {
            is Button -> {
                child.isSelected = child.tag == tag
                refreshFilterText(child)
            }
            // The episode grid nests its buttons inside row layouts.
            is ViewGroup -> markChipByTag(child, tag)
        }
    }
}


internal fun MainActivity.loadMovieStreams() {
    val subjectId = selectedDubId ?: return
    // A series picks streams per episode, not per title — a dub switch
    // reloads that dub's seasons instead.
    if (currentMovieDetails?.movie?.isSeries == true) {
        loadSeriesSeasons(subjectId)
        return
    }
    val request = ++movieStreamsRequest
    currentMovieStreams = emptyList()
    selectedQuality = 0
    movieQualityRow.removeAllViews()
    moviePlayButton.isEnabled = false
    movieDetailsStatus.text = "Streams load ho rahe hain…"
    imports.execute {
        try {
            // A dub is a different subject with its own files — reuse the
            // details we already have when the original is selected.
            val details = currentMovieDetails?.takeIf { it.movie.subjectId == subjectId }
                ?: movieBox.details(subjectId)
            currentPlayTitle = details.movie.title
            val streams = movieBox.streams(details)
            runOnUiThread {
                if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                currentMovieStreams = streams
                showMovieStreams(streams)
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                movieDetailsStatus.text = "Streams nahi mile: ${error.message}"
            }
        }
    }
}


/** Loads a series' seasons (for the selected dub when one is picked),
 * then shows the first season's episodes. */
internal fun MainActivity.loadSeriesSeasons(subjectId: String? = null) {
    val id = subjectId ?: selectedDubId ?: return
    movieStreamsRequest++
    val request = ++movieSeasonsRequest
    currentSeriesSeasons = emptyList()
    selectedSeason = 0
    selectedEpisode = 0
    currentMovieStreams = emptyList()
    selectedQuality = 0
    movieSeasonRow.removeAllViews()
    movieEpisodeColumn.removeAllViews()
    movieQualityRow.removeAllViews()
    moviePlayButton.isEnabled = false
    movieDetailsStatus.text = "Seasons load ho rahe hain…"
    imports.execute {
        try {
            val details = currentMovieDetails?.takeIf { it.movie.subjectId == id } ?: movieBox.details(id)
            currentPlayTitle = details.movie.title
            val seasons = movieBox.seasons(details.movie.subjectId)
            runOnUiThread {
                if (isDestroyed || request != movieSeasonsRequest) return@runOnUiThread
                currentSeriesSeasons = seasons
                if (seasons.isEmpty()) {
                    movieDetailsStatus.text = "Is series ke seasons nahi mile."
                    return@runOnUiThread
                }
                movieSeasonLabel.visibility = View.VISIBLE
                movieSeasonScroll.visibility = View.VISIBLE
                movieEpisodeLabel.visibility = View.VISIBLE
                movieEpisodeColumn.visibility = View.VISIBLE
                rebuildSeasonChips()
                selectSeason(seasons.first().number)
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieSeasonsRequest) return@runOnUiThread
                movieDetailsStatus.text = "Seasons nahi mile: ${error.message}"
            }
        }
    }
}


internal fun MainActivity.rebuildSeasonChips() {
    movieSeasonRow.removeAllViews()
    for (season in currentSeriesSeasons) {
        val labelText = season.name.ifEmpty { "Season ${season.number}" }
        val chipView = chip(labelText) {
            if (selectedSeason != season.number) {
                selectedSeason = season.number
                markChipByTag(movieSeasonRow, season.number)
                showSeasonEpisodes(season.number)
            }
        }
        chipView.tag = season.number
        chipView.isSelected = season.number == selectedSeason
        refreshFilterText(chipView)
        movieSeasonRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
}


internal fun MainActivity.selectSeason(number: Int) {
    movieStreamsRequest++
    selectedSeason = number
    markChipByTag(movieSeasonRow, number)
    showSeasonEpisodes(number)
}


internal fun MainActivity.showSeasonEpisodes(seasonNumber: Int) {
    val season = currentSeriesSeasons.firstOrNull { it.number == seasonNumber } ?: return
    selectedEpisode = 0
    movieEpisodeColumn.removeAllViews()
    movieQualityRow.removeAllViews()
    currentMovieStreams = emptyList()
    selectedQuality = 0
    moviePlayButton.isEnabled = false
    // Compact number grid: small "1" "2" "3" buttons in rows instead of
    // one full-width box per episode.
    fun buildGrid(perRow: Int) {
        movieEpisodeColumn.removeAllViews()
        for (chunk in season.episodes.chunked(perRow)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (ep in chunk) {
                row.addView(seriesEpisodeRow(seasonNumber, ep), LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(8)
                })
            }
            movieEpisodeColumn.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
    }
    val perRow = if (isTv) 8 else 5
    buildGrid(perRow)
    if (!isTv) {
        // Auto-fit (mobile): measure the REAL rendered button width
        // once laid out. The button's minimumWidth (56dp) is only a
        // floor — the rendered button is wider (10dp padding each
        // side + text), so the v2.0.5 maths assumed a 64dp slot and
        // counted 5 buttons where only 4 fit, cutting the 5th off
        // at the side (seen on 2.0.6). Measuring the first button
        // instead adapts to any device/font size. Only shrinks, and
        // only before an episode is picked.
        movieEpisodeColumn.post {
            val w = movieEpisodeColumn.width
            if (w > 0 && selectedEpisode == 0) {
                val firstRow = movieEpisodeColumn.getChildAt(0) as? LinearLayout
                val firstBtn = firstRow?.getChildAt(0)
                val slot = (firstBtn?.measuredWidth ?: 0) + dp(8)
                if (slot > 0) {
                    val fit = (w / slot).coerceIn(2, 5)
                    if (fit < perRow) buildGrid(fit)
                }
            }
        }
    }
    movieDetailsStatus.text = "${season.episodes.size} episodes — episode chunein."
}


/** One episode button: a small number button in the grid (selected
 * state shows the picked episode). Tapping it loads that episode's
 * streams; the episode title shows in the status line instead. */
internal fun MainActivity.seriesEpisodeRow(seasonNumber: Int, ep: MovieBox.EpisodeRef): View {
    return episodeNumberButton("${ep.number}") {
        if (selectedEpisode != ep.number || selectedSeason != seasonNumber || currentMovieStreams.isEmpty()) {
            selectedSeason = seasonNumber
            selectedEpisode = ep.number
            markChipByTag(movieEpisodeColumn, ep.number)
            loadEpisodeStreams()
        }
    }.apply {
        tag = ep.number
        isSelected = ep.number == selectedEpisode && seasonNumber == selectedSeason
        refreshFilterText(this)
    }
}


/** Loads the picked episode's streams, then the usual quality chips. */
internal fun MainActivity.loadEpisodeStreams() {
    val subjectId = selectedDubId ?: return
    val season = selectedSeason
    val episode = selectedEpisode
    if (season <= 0 || episode <= 0) return
    val request = ++movieStreamsRequest
    currentMovieStreams = emptyList()
    selectedQuality = 0
    movieQualityRow.removeAllViews()
    moviePlayButton.isEnabled = false
    val epTitle = currentSeriesSeasons.firstOrNull { it.number == season }
        ?.episodes?.firstOrNull { it.number == episode }?.title.orEmpty()
    movieDetailsStatus.text = if (epTitle.isNotEmpty()) {
        "E$episode · $epTitle load ho raha hai…"
    } else {
        "Episode load ho raha hai…"
    }
    imports.execute {
        try {
            val details = currentMovieDetails?.takeIf { it.movie.subjectId == subjectId }
                ?: movieBox.details(subjectId)
            val streams = movieBox.episodeStreams(details, season, episode)
            runOnUiThread {
                if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                currentMovieStreams = streams
                showMovieStreams(streams)
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                movieDetailsStatus.text = "Episode nahi mila: ${error.message}"
            }
        }
    }
}


internal fun MainActivity.showMovieStreams(streams: List<MovieBox.MovieStream>) {
    val resolutions = streams.map { it.resolution }.filter { it > 0 }
        .distinct().sortedDescending()
    // Series: quality is picked in the player (Quality button), not
    // on the episode list screen — no quality row here. Best quality
    // is the default; the in-player dialog can switch it while playing.
    if (currentMovieDetails?.movie?.isSeries == true) {
        selectedQuality = resolutions.firstOrNull() ?: 0
        movieQualityLabel.visibility = View.GONE
        movieQualityRow.visibility = View.GONE
        // The file size again (v2.0.2 showed it, v2.0.4 lost it): the
        // best stream's size, e.g. "Stream ready hai · 282 MB — Play dabayein."
        val best = streams.firstOrNull()
        val sizeText = if (best != null && best.sizeBytes > 0) " · ${formatSize(best.sizeBytes)}" else ""
        movieDetailsStatus.text = "Stream ready hai$sizeText — Play dabayein."
        moviePlayButton.isEnabled = true
        // If the user hasn't moved off the Fav landing yet, put focus
        // on Play now that it works.
        if (movieFavButton.hasFocus()) moviePlayButton.requestFocus()
        return
    }
    // Movies keep the quality chips: restore the row in case a series
    // hid it.
    movieQualityLabel.visibility = View.VISIBLE
    movieQualityRow.visibility = View.VISIBLE
    if (resolutions.isEmpty()) {
        // Streams exist but none reports a resolution — one Auto entry
        // that plays the best (first) stream.
        selectedQuality = 0
        val chipView = chip("Auto") {
            selectedQuality = 0
            markChipByTag(movieQualityRow, 0)
        }
        chipView.tag = 0
        chipView.isSelected = true
        refreshFilterText(chipView)
        movieQualityRow.addView(chipView)
        movieDetailsStatus.text = "Stream ready hai — Play dabayein."
        moviePlayButton.isEnabled = true
        // If the user hasn't moved off the Fav landing yet, put focus
        // on Play now that it works.
        if (movieFavButton.hasFocus()) moviePlayButton.requestFocus()
        return
    }
    // Default: the highest available quality.
    selectedQuality = resolutions.first()
    for (res in resolutions) {
        val chipView = chip("${res}p") {
            selectedQuality = res
            markChipByTag(movieQualityRow, res)
        }
        chipView.tag = res
        chipView.isSelected = res == selectedQuality
        refreshFilterText(chipView)
        movieQualityRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    val best = streams.first()
    val sizeText = if (best.sizeBytes > 0) " · ${formatSize(best.sizeBytes)}" else ""
    movieDetailsStatus.text = "${resolutions.size} qualities available$sizeText — Play dabayein."
    moviePlayButton.isEnabled = true
    // If the user hasn't moved off the Fav landing yet, put focus
    // on Play now that it works.
    if (movieFavButton.hasFocus()) moviePlayButton.requestFocus()
}


internal fun MainActivity.playSelectedMovie() {
    val details = currentMovieDetails ?: return
    val streams = currentMovieStreams
    if (streams.isEmpty()) return
    val stream = streams.firstOrNull { it.resolution == selectedQuality } ?: streams.first()
    closeMovies()
    // The played subject is the SELECTED DUB (a dub is a different
    // subject with its own files) — Continue Watching must resume the
    // same audio the user actually played, not the original.
    val played = details.movie.copy(
        subjectId = selectedDubId ?: details.movie.subjectId,
        title = currentPlayTitle,
    )
    // English CC for this title (null when it has no English subtitle,
    // so a previous title's subtitle never leaks into this one).
    currentSubtitle = subtitleForStream(stream, details)
    val isSeries = details.movie.isSeries
    playMovieStream(
        played, stream.url, stream.headers,
        episodeSeason = if (isSeries) selectedSeason else 0,
        episodeNumber = if (isSeries) selectedEpisode else 0,
        dubs = details.audioDubs(),
    )
    applyFullScreen(true)
}


/**
 * Plays a resolved movie file in the main player, with the same setup
 * as [play] but without touching the channel store: no recent entry, no
 * last-channel, no favorite — the signed URL must not outlive this
 * session. Seek/pause/volume/speed/fullscreen all work through the
 * existing controls; movies are seekable, so the seek bar appears.
 */
internal fun MainActivity.playMovieStream(
    movie: MovieBox.Movie,
    url: String,
    headers: Map<String, String>,
    positionMs: Long = 0L,
    episodeSeason: Int = 0,
    episodeNumber: Int = 0,
    dubs: List<MovieBox.Dub> = emptyList(),
    autoplay: Boolean = true,
) {
    audioRequest++
    playingDubs = dubs
    // A new movie replaces the old one — save the old movie's place
    // in Continue Watching before the player switches streams.
    saveMovieProgress(async = false)
    movieProgressTick = 0
    stopWeb()
    selected = null
    playingMovie = movie
    // A blank title leaves the top bar showing only the back arrow.
    // The details API always has the real title ("The Body [Hindi]"
    // verified live). Fill it from whatever the app already knows
    // first (the details screen's title / the stored play title),
    // then fetch it in the background as a backstop. v2.0.14's fetch
    // only ran on the single-thread `imports` queue — a stuck task
    // ahead of it left the bar blank for the whole playback — and it
    // caught only IOException, so a parse failure also left it blank.
    // This one runs on its own thread and catches everything.
    // Playback itself never waits for any of this.
    if (movie.title.isBlank()) {
        val known = currentPlayTitle.ifBlank { currentMovieDetails?.movie?.title.orEmpty() }
        if (known.isNotBlank()) {
            playingMovie = movie.copy(title = known)
            currentPlayTitle = known
            topTitleLabel.text = topBarTitle()
        } else if (movie.subjectId.isNotBlank() && !url.startsWith("file:")) {
            fetchTopBarTitle(movie.subjectId)
        }
    }
    playingEpisodeSeason = episodeSeason
    playingEpisodeEpisode = episodeNumber
    playingMovieUrl = url
    playingMovieHeaders = headers
    movieEnded = false
    hideUpNextCard()
    // Use the title-filled copy when the blank-title heal ran above,
    // so the meta line shows the title too (not just the top bar).
    setMovieMeta(playingMovie ?: movie, episodeSeason, episodeNumber)
    playPauseButton.setImageResource(R.drawable.ic_pause)
    videoOptions = emptyList()
    selectedVideoIndex = -1
    seekable = false
    stopSeekUpdates()
    updateFavoriteButton()
    applyControlVisibility()
    markPlayingRow()
    status.text = "Connecting to stream…"
    // English CC for this title (currentSubtitle, set by the caller
    // from the title's details). An unusable URL (unknown subtitle
    // format) clears it so the CC button hides.
    val subtitleConfig = currentSubtitle?.let { subtitleConfigFor(it) }
    if (subtitleConfig == null) currentSubtitle = null
    playerController?.subtitlesEnabled = subtitlesEnabled
    // Fallback file: another resolved quality of the same episode/movie.
    // If the file server refuses the first URL (rate limit / dead link),
    // the player tries this one before giving up.
    val backupUrl = currentMovieStreams.firstOrNull { it.url != url }?.url
    playerController?.play(url, headers, backup = backupUrl, subtitle = subtitleConfig)
    if (positionMs > 0) playerController?.seekTo(positionMs)
    if (!autoplay && playerController?.wantsPlayback == true) playerController?.togglePlayPause()
    bringPlayerOverlaysToFront()
    showPlayerControls()
    // A movie hides the browse pane on side-by-side screens — focus
    // moves to the play/pause button, since the list is gone.
    updateBrowseVisibility()
    if (isTv || isLandscape) playPauseButton.requestFocus()
    // No next-episode prefetch (v2.0.5): resolving the next episode
    // while this one plays doubled requests to the file server and
    // helped trip its 429 rate limit. Next now resolves on demand
    // in playAdjacentEpisode, which already worked as the fallback.
}


/** One-shot background title fetch for the top bar (v2.0.16).
 * The play-time fill in [playMovieStream] only runs when the title
 * is blank at play start — three versions still showed a bare back
 * arrow on TV, so the title must be getting blanked (or the fetch
 * discarded) after play start, on a path the play-time code never
 * sees. This helper is also called from [applyControlVisibility]
 * whenever the bar is drawn with a blank title, so the title heals
 * at display time no matter which path blanked it. One attempt per
 * subject (guarded by [titleFetchSubject]); a failed or discarded
 * attempt clears the guard so the next controls toggle retries.
 * Playback never waits for any of this. */
internal fun MainActivity.fetchTopBarTitle(subjectId: String) {
    if (subjectId.isBlank() || subjectId == titleFetchSubject) return
    if (playingMovieUrl?.startsWith("file:") == true) return
    titleFetchSubject = subjectId
    Thread {
        try {
            val fetched = movieBox.details(subjectId)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (playingMovie?.subjectId != subjectId) {
                    // Another title took over mid-fetch; let its own
                    // heal run instead of stamping a stale title.
                    titleFetchSubject = ""
                    return@runOnUiThread
                }
                val title = fetched.movie.title
                if (title.isBlank()) {
                    titleFetchSubject = ""
                    return@runOnUiThread
                }
                playingMovie = playingMovie?.copy(title = title)
                currentPlayTitle = title
                currentMovieDetails = fetched
                topTitleLabel.text = topBarTitle()
            }
        } catch (_: Exception) {
            // Title stays blank; playback is unaffected. Clear the
            // guard so a later controls toggle retries once more.
            titleFetchSubject = ""
        }
    }.apply { name = "title-fetch"; isDaemon = true; start() }
}


/** Switches the playing movie/episode to another resolved quality,
 * resuming at the current position. Same mechanism as Play — the
 * signed URL is swapped and progress is saved first — so it works
 * for movies and series episodes alike. */
internal fun MainActivity.switchMovieQuality(resolution: Int) {
    val movie = playingMovie ?: return
    val stream = currentMovieStreams.firstOrNull { it.resolution == resolution } ?: return
    if (stream.url == playingMovieUrl) {
        selectedQuality = resolution
        return
    }
    selectedQuality = resolution
    val position = playerController?.position() ?: 0L
    playMovieStream(movie, stream.url, stream.headers, position, playingEpisodeSeason, playingEpisodeEpisode, playingDubs, playerController?.wantsPlayback == true)
}


/** English subtitle for CC (the user wants English only): the first
 * subtitle whose language name is English/en. */
internal fun List<MovieBox.Subtitle>.englishSubtitle(): MovieBox.Subtitle? =
    firstOrNull {
        val name = it.name.trim().lowercase()
        name == "english" || name == "en" || name == "eng" ||
            name.startsWith("en-") || name.startsWith("english ")
    }

/** Subtitle for the stream about to play: the English caption fetched
 * for this episode/title at resolve time (first), else the title's
 * own subtitles (extCaptions). Null when neither has one — the CC
 * button then hides, as before. */
internal fun MainActivity.subtitleForStream(
    stream: MovieBox.MovieStream,
    details: MovieBox.Details?,
): MovieBox.Subtitle? =
    stream.subtitleUrl.takeIf { it.isNotEmpty() }?.let { MovieBox.Subtitle("English", it) }
        ?: details?.subtitles?.englishSubtitle()


/** CC on/off for the playing title (player CC button). */
internal fun MainActivity.toggleSubtitles() {
    if (currentSubtitle == null) return
    subtitlesEnabled = !subtitlesEnabled
    playerController?.applySubtitlesEnabled(subtitlesEnabled)
    updateCCButton()
    showPlayerControls()
}


/** CC button state: cyan while subtitles are on, white while off.
 * (No isInitialized guard: the button is created in onCreate with the
 * rest of the overlay, and this only runs while a movie plays — the
 * same invariant the Quality/Audio buttons already rely on. A
 * ::prop.isInitialized check only compiles inside the declaring
 * class, so it cannot live in this file.) */
internal fun MainActivity.updateCCButton() {
    // Demo UI: the CC chip is filled cyan (dark text) while subtitles
    // are on, a plain dark chip while off.
    if (subtitlesEnabled) {
        movieCCButton.background = shape(cyan, cyan, 10, 0)
        movieCCButton.setTextColor(0xff111111.toInt())
    } else {
        movieCCButton.background = rowChipBackground()
        movieCCButton.setTextColor(Color.WHITE)
    }
    movieCCButton.contentDescription =
        if (subtitlesEnabled) "Subtitles on" else "Subtitles off"
}

/** Subtitle sizes (sp) offered in the CC menu. Medium (20sp) is the
 * default — the old ExoPlayer default scaled with the view height
 * and was far too big on TV. */
internal val subtitleSizeOptions = listOf(16f to "Chhota", 20f to "Medium", 24f to "Bara")

/** The persisted subtitle size (sp) — the CC menu's choice, kept
 * across restarts. */
internal var MainActivity.subtitleSizeSp: Float
    get() = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        .getFloat("subtitle_size_sp", 20f)
    set(value) {
        getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
            .putFloat("subtitle_size_sp", value).apply()
    }

/** The persisted subtitle background (black box behind the text) —
 * the demo look, on by default. Off is the PR #13 text-only look. */
internal var MainActivity.subtitleBackground: Boolean
    get() = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        .getBoolean("subtitle_background", true)
    set(value) {
        getSharedPreferences("iptv", Context.MODE_PRIVATE).edit()
            .putBoolean("subtitle_background", value).apply()
    }

/** Bottom-sheet shell shared by the player menus — CC, Quality,
 * Audio (Netflix/YouTube style): navy gradient card, title + close,
 * option rows with a cyan checkmark, tap applies live and the sheet
 * stays open. Identical on TV and phone — the old AlertDialog lists
 * are gone from all three menus. */
internal class PlayerMenuSheet(private val activity: MainActivity, title: String) {
    private val dialog = Dialog(activity)
    private val checks = mutableListOf<Pair<TextView, () -> Boolean>>()
    private var firstRow: View? = null
    private val sheet = activity.column().apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xff0a1245.toInt(), 0xff2a1b6b.toInt()),
        ).apply {
            cornerRadii = floatArrayOf(
                activity.dp(24).toFloat(), activity.dp(24).toFloat(),
                activity.dp(24).toFloat(), activity.dp(24).toFloat(),
                0f, 0f, 0f, 0f,
            )
            setStroke(activity.dp(1), 0x6696acff.toInt())
        }
        setPadding(activity.dp(20), activity.dp(16), activity.dp(20), activity.dp(20))
    }

    init {
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(activity.label(title, if (activity.isTv) 20 else 18, Color.WHITE).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(activity).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.WHITE)
            minimumWidth = 0
            minimumHeight = 0
            setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
            background = activity.overlayButtonBackground()
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(-2, -2))
        sheet.addView(header, LinearLayout.LayoutParams(-1, -2))
    }

    /** Extra content under the header (e.g. the CC live preview). */
    fun addView(view: View, params: LinearLayout.LayoutParams) {
        sheet.addView(view, params)
    }

    /** A section heading (small caps, muted). */
    fun section(text: String) {
        sheet.addView(activity.label(text, 12, muted2).apply {
            setTypeface(null, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(16) })
    }

    /** One option row: label on the left, cyan checkmark on the
     * right while [checked] holds. Tap applies [onTap] live, then
     * refreshes every checkmark; the sheet stays open. */
    fun row(text: String, checked: () -> Boolean, onTap: () -> Unit) {
        val check = activity.label("✓", 18, cyan).apply {
            visibility = if (checked()) View.VISIBLE else View.GONE
        }
        checks += check to checked
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(activity.dp(6), activity.dp(12), activity.dp(6), activity.dp(12))
            setOnFocusChangeListener { _, hasFocus ->
                animateFocusScale(this, hasFocus)
                background = if (hasFocus) GradientDrawable().apply {
                    setColor(0x2affffff.toInt())
                    cornerRadius = activity.dp(10).toFloat()
                } else null
            }
            addView(activity.label(text, 16, Color.WHITE), LinearLayout.LayoutParams(0, -2, 1f))
            addView(check, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { onTap(); refresh() }
            if (firstRow == null) firstRow = this
        }
        sheet.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    /** Extra refresh work a menu needs (e.g. the CC preview restyle). */
    var onRefresh: (() -> Unit)? = null

    /** Re-evaluates every checkmark (and [onRefresh]). */
    fun refresh() {
        for ((view, isChecked) in checks) {
            view.visibility = if (isChecked()) View.VISIBLE else View.GONE
        }
        onRefresh?.invoke()
    }

    fun dismiss() = dialog.dismiss()

    fun show() {
        refresh()
        dialog.setContentView(sheet)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
            window.setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        if (activity.isTv) firstRow?.post { firstRow?.requestFocus() }
    }
}

/** CC menu (player CC button): a Netflix/YouTube-style bottom
 * sheet — language, size and background, all applied live while the
 * sheet stays open, with a preview of the subtitle style. */
internal fun MainActivity.showSubtitleStyleDialog() {
    if (currentSubtitle == null) return
    val sheet = PlayerMenuSheet(this, "Subtitles")
    lateinit var preview: TextView
    sheet.onRefresh = {
        preview.textSize = subtitleSizeSp
        preview.background = if (subtitleBackground) GradientDrawable().apply {
            setColor(0x99000000.toInt())
            cornerRadius = dp(4).toFloat()
        } else null
    }
    // Live preview of the subtitle style (the demo's line).
    preview = label("Tum yahan kya kar rahe ho?", 16, Color.WHITE).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }
    sheet.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    sheet.section("LANGUAGE")
    sheet.row("English", { subtitlesEnabled }) {
        if (!subtitlesEnabled) toggleSubtitles()
    }
    sheet.row("Off", { !subtitlesEnabled }) {
        if (subtitlesEnabled) toggleSubtitles()
    }
    sheet.section("SIZE")
    for ((size, sizeLabel) in subtitleSizeOptions) {
        sheet.row(sizeLabel, { subtitleSizeSp == size }) {
            subtitleSizeSp = size
            playerController?.setSubtitleSize(size)
        }
    }
    sheet.section("BACKGROUND")
    sheet.row("Black background", { subtitleBackground }) {
        subtitleBackground = !subtitleBackground
        playerController?.setSubtitleBackground(subtitleBackground)
    }
    sheet.show()
}


/** Play the previous/next episode from the player (Prev/Next buttons).
 * Resolves the episode in the background on demand (no prefetch). */
internal fun MainActivity.playAdjacentEpisode(delta: Int) {
    val movie = playingMovie ?: return
    val season = playingEpisodeSeason
    val episode = playingEpisodeEpisode
    if (season <= 0 || episode <= 0) return
    val target = episode + delta
    val count = currentSeriesSeasons.firstOrNull { it.number == season }?.episodeCount ?: 0
    if (target < 1 || (count > 0 && target > count)) {
        toast(if (delta > 0) "Aakhri episode hai." else "Pehla episode hai.")
        return
    }
    toast("Episode load ho raha hai…")
    imports.execute {
        try {
            val details = currentMovieDetails?.takeIf { it.movie.subjectId == movie.subjectId }
                ?: movieBox.details(movie.subjectId)
            val streams = movieBox.episodeStreams(details, season, target)
            runOnUiThread {
                if (isDestroyed || playingMovie !== movie || playingEpisodeEpisode != episode) return@runOnUiThread
                if (streams.isEmpty()) {
                    toast("Episode nahi mila.")
                    return@runOnUiThread
                }
                playAdjacentStreams(movie, season, target, streams)
            }
        } catch (_: IOException) {
            runOnUiThread { toast("Episode load nahi ho saka.") }
        }
    }
}


private fun MainActivity.playAdjacentStreams(
    movie: MovieBox.Movie,
    season: Int,
    target: Int,
    streams: List<MovieBox.MovieStream>,
) {
    if (streams.isEmpty()) return
    currentMovieStreams = streams
    val stream = streams.firstOrNull { it.resolution == selectedQuality } ?: streams.first()
    selectedSeason = season
    selectedEpisode = target
    selectedQuality = stream.resolution
    // Next/Prev episode: its own caption (the details here are the
    // same title's, when available).
    currentSubtitle = subtitleForStream(
        stream,
        currentMovieDetails?.takeIf { it.movie.subjectId == movie.subjectId },
    )
    playMovieStream(
        movie, stream.url, stream.headers, 0L,
        episodeSeason = season, episodeNumber = target, dubs = playingDubs,
    )
    applyControlVisibility()
}


internal fun MainActivity.setMovieMeta(movie: MovieBox.Movie, season: Int = 0, episode: Int = 0) {
    val label = if (movie.isSeries && season > 0 && episode > 0) " S$season E$episode" else ""
    metaName.text = movie.title + label
    metaCategory.text = if (movie.isSeries) "Series" else "Movie"
    metaCategory.visibility = View.VISIBLE
    metaLiveBadge.visibility = View.GONE
}


internal fun MainActivity.restoreMoviePlayback(saved: Bundle?) {
    if (saved == null) return
    val url = saved.getString(STATE_MOVIE_URL) ?: return
    val title = saved.getString(STATE_MOVIE_TITLE).orEmpty()
    val position = saved.getLong(STATE_MOVIE_POS, 0L)
    val headers = saved.getStringArrayList(STATE_MOVIE_HEADERS).orEmpty()
        .mapNotNull { raw ->
            val parts = raw.split("\u0001")
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    selectedQuality = saved.getInt("movie_quality", 0)
    resumeSavedPlayback(
        MovieBox.Movie(
            subjectId = saved.getString("movie_subject_id").orEmpty(),
            title = title,
            isSeries = saved.getBoolean("movie_is_series", false),
        ),
        url, headers, position,
        episodeSeason = saved.getInt("movie_season", 0),
        episodeNumber = saved.getInt("movie_episode", 0),
    )
}


internal fun MainActivity.updateMovieTimes(position: Long, duration: Long) {
    // Demo UI: one combined readout right of the bar
    // ("24:31 / 1:58:00"); the remaining-time label is hidden.
    seekLabel.text = "${formatTime(position.coerceAtLeast(0L))} / ${formatTime(duration.coerceAtLeast(0L))}"
}

/** Demo UI (v2.0.11): title for the top player bar — "The Body •
 * S1:E1" for a series episode, the plain title for a movie. */
internal fun MainActivity.topBarTitle(): String {
    val movie = playingMovie ?: return ""
    // The playing title can be blank on resume paths (a stored
    // Continue Watching entry without a title); fall back to the
    // title the details screen loaded, so the bar never shows a
    // lonely back arrow with no title.
    val title = movie.title.ifBlank { currentPlayTitle }
        .ifBlank { currentMovieDetails?.movie?.title.orEmpty() }
    return if (playingEpisodeSeason > 0 && playingEpisodeEpisode > 0 && title.isNotBlank()) {
        "$title • S${playingEpisodeSeason}:E${playingEpisodeEpisode}"
    } else {
        title
    }
}

/** Demo UI: does the playing series episode have a next one? The
 * count must be known — after a Continue Watching resume the
 * seasons are not loaded yet, and then Up Next stays hidden, just
 * like the Prev/Next buttons. */
internal fun MainActivity.hasNextEpisode(): Boolean {
    if (playingMovie == null || playingEpisodeSeason <= 0 || playingEpisodeEpisode <= 0) return false
    val count = currentSeriesSeasons.firstOrNull { it.number == playingEpisodeSeason }?.episodeCount ?: 0
    return count > 0 && playingEpisodeEpisode < count
}

private fun MainActivity.upNextKey() =
    "${playingMovie?.subjectId}:${playingEpisodeSeason}:${playingEpisodeEpisode}"

/** The Up Next trigger, called from the 500ms seek ticker: in the
 * last 20s of a series episode the card appears with a 10s
 * auto-play countdown. Seeking away from the end hides it again. */
internal fun MainActivity.checkUpNext(position: Long, duration: Long) {
    val remaining = duration - position
    if (remaining > 25_000 || !hasNextEpisode()) {
        hideUpNextCard()
        return
    }
    if (remaining <= 0 || remaining > 20_000) return
    if (upNextShownFor == upNextKey()) return
    showUpNextCard()
}

internal fun MainActivity.showUpNextCard() {
    val movie = playingMovie ?: return
    val next = playingEpisodeEpisode + 1
    upNextTitle.text = "Up Next: S${playingEpisodeSeason}:E$next"
    upNextCountdownValue = 10
    upNextCountdown.text = "Auto-plays in 10s"
    upNextShownFor = upNextKey()
    LogoLoader.load(this, movie.posterUrl, upNextThumb, maxDimension = 192)
    upNextCard.visibility = View.VISIBLE
    applyControlVisibility()
    mainHandler.removeCallbacks(upNextTickRunnable)
    mainHandler.postDelayed(upNextTickRunnable, 1000)
}

internal fun MainActivity.hideUpNextCard() {
    mainHandler.removeCallbacks(upNextTickRunnable)
    upNextShownFor = null
    upNextCard.visibility = View.GONE
}

internal fun MainActivity.resumeSavedPlayback(
    movie: MovieBox.Movie, url: String, headers: Map<String, String>, position: Long,
    episodeSeason: Int, episodeNumber: Int,
) {
    if (url.startsWith("file:") || movie.subjectId.isBlank()) {
        // A local file has no alternate qualities — drop any stale
        // stream list so the quality switcher cannot offer another
        // title's streams for this playback.
        currentMovieStreams = emptyList()
        playMovieStream(movie, url, headers, position, episodeSeason, episodeNumber)
        return
    }
    status.text = "Playback link refresh ho raha hai…"
    resumeMovie(MovieStore.ContinueEntry(movie.subjectId, movie.title, movie.posterUrl,
        position, 0L, selectedQuality, System.currentTimeMillis(), episodeSeason, episodeNumber,
        movie.isSeries), fullScreen)
}
