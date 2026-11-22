package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
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
            runOnUiThread {
                if (isDestroyed || request != movieResumeRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                // firstOrNull, never first(): an empty stream list used
                // to throw on the UI thread and crash the app.
                val stream = streams.firstOrNull { it.resolution == entry.quality }
                    ?: streams.firstOrNull()
                if (stream == null) {
                    movieStatus.text = "Resume nahi ho saka: koi playable link nahi mila."
                    status.text = movieStatus.text
                    return@runOnUiThread
                }
                selectedQuality = stream.resolution
                closeMovies()
                playMovieStream(
                    details.movie.copy(title = entry.title),
                    stream.url, stream.headers, entry.positionMs,
                    episodeSeason = if (entry.isSeries) entry.season else 0,
                    episodeNumber = if (entry.isSeries) entry.episode else 0,
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
    // The details pane covers the home — stop the hero rotation so it
    // stops firing details lookups for a hidden banner.
    stopMoviesHero()
    val request = ++movieDetailsRequest
    currentMovieDetails = null
    currentMovieStreams = emptyList()
    currentPlayTitle = movie.title
    selectedDubId = movie.subjectId
    selectedQuality = 0
    movieDetailsTitle.text = movie.title
    movieDetailsMeta.text = ""
    movieDetailsDesc.text = ""
    movieDubRow.removeAllViews()
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
    rebuildDubChips(details)
    loadMovieStreams()
}


/** Series details: same title/meta/description as a movie, but no
 * runtime (a series has no single length) and no upfront streams —
 * seasons load first, then the picked episode's streams. */
internal fun MainActivity.showSeriesDetails(details: MovieBox.Details) {
    currentMovieDetails = details
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
    rebuildDubChips(details)
    loadSeriesSeasons()
}


internal fun MainActivity.rebuildDubChips(details: MovieBox.Details) {
    movieDubRow.removeAllViews()
    val options = mutableListOf(details.movie.subjectId to "Original")
    for (dub in details.dubs) {
        if (dub.subjectId == details.movie.subjectId) continue
        options += dub.subjectId to dub.language.ifEmpty { dub.label }
    }
    for ((subjectId, label) in options) {
        val chipView = chip(label) {
            if (selectedDubId != subjectId) {
                selectedDubId = subjectId
                markChipByTag(movieDubRow, subjectId)
                loadMovieStreams()
            }
        }
        chipView.tag = subjectId
        chipView.isSelected = subjectId == selectedDubId
        refreshFilterText(chipView)
        movieDubRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
}


internal fun MainActivity.markChipByTag(row: LinearLayout, tag: Any?) {
    for (i in 0 until row.childCount) {
        val child = row.getChildAt(i)
        if (child is Button) {
            child.isSelected = child.tag == tag
            refreshFilterText(child)
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
    for (ep in season.episodes) {
        movieEpisodeColumn.addView(
            seriesEpisodeRow(seasonNumber, ep),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) },
        )
    }
    movieDetailsStatus.text = "${season.episodes.size} episodes — episode chunein."
}


/** One episode row: a full-width chip (selected state shows the picked
 * episode). Tapping it loads that episode's streams. */
internal fun MainActivity.seriesEpisodeRow(seasonNumber: Int, ep: MovieBox.EpisodeRef): View {
    val title = if (ep.title.isNotEmpty()) "E${ep.number} · ${ep.title}" else "Episode ${ep.number}"
    return chip(title) {
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
    movieDetailsStatus.text = "Episode load ho raha hai…"
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
    val isSeries = details.movie.isSeries
    playMovieStream(
        played, stream.url, stream.headers,
        episodeSeason = if (isSeries) selectedSeason else 0,
        episodeNumber = if (isSeries) selectedEpisode else 0,
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
) {
    // A new movie replaces the old one — save the old movie's place
    // in Continue Watching before the player switches streams.
    saveMovieProgress(async = false)
    movieProgressTick = 0
    stopWeb()
    selected = null
    playingMovie = movie
    playingEpisodeSeason = episodeSeason
    playingEpisodeEpisode = episodeNumber
    playingMovieUrl = url
    playingMovieHeaders = headers
    movieEnded = false
    setMovieMeta(movie, episodeSeason, episodeNumber)
    playPauseButton.setImageResource(R.drawable.ic_pause)
    videoOptions = emptyList()
    selectedVideoIndex = -1
    seekable = false
    stopSeekUpdates()
    updateFavoriteButton()
    applyControlVisibility()
    markPlayingRow()
    status.text = "Connecting to stream…"
    playerController?.play(url, headers)
    if (positionMs > 0) playerController?.seekTo(positionMs)
    bringPlayerOverlaysToFront()
    showPlayerControls()
    // A movie hides the browse pane on side-by-side screens — focus
    // moves to the play/pause button, since the list is gone.
    updateBrowseVisibility()
    if (isTv || isLandscape) playPauseButton.requestFocus()
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
    seekLabel.text = formatTime(position.coerceAtLeast(0L))
    remainingLabel.text = "−" + formatTime((duration - position).coerceAtLeast(0L))
}

internal fun MainActivity.resumeSavedPlayback(
    movie: MovieBox.Movie, url: String, headers: Map<String, String>, position: Long,
    episodeSeason: Int, episodeNumber: Int,
) {
    if (url.startsWith("file:") || movie.subjectId.isBlank()) {
        playMovieStream(movie, url, headers, position, episodeSeason, episodeNumber)
        return
    }
    status.text = "Playback link refresh ho raha hai…"
    resumeMovie(MovieStore.ContinueEntry(movie.subjectId, movie.title, movie.posterUrl,
        position, 0L, selectedQuality, System.currentTimeMillis(), episodeSeason, episodeNumber,
        movie.isSeries), fullScreen)
}
