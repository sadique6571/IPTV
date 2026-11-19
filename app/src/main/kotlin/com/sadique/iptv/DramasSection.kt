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
 * The Geo Dramas browser and episode playback.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


/** Builds the Geo Dramas overlay on first open (same reason). */
internal fun MainActivity.ensureDramasView() {
    if (dramasViewBuilt) return
    dramasViewBuilt = true
    dramasView = buildDramasView()
    contentFrame.addView(dramasView, FrameLayout.LayoutParams(-1, -1))
}


// ---- Geo Dramas (v17) ----
//
// A full-screen section beside Live TV (same overlay pattern as
// Movies): Har Pal Geo drama list (thumbnail + episode count + latest
// episode) → episode list (newest first) → Play in the main player.
// The list shows immediately in playlists-tab order; a background pass
// fetches each drama's latest-episode date and re-sorts, so the drama
// with the newest episode sits on top. New episodes appear by
// themselves: the section re-reads YouTube (6h disk cache) whenever it
// is opened after the cache expires, and Refresh forces a fresh read.
// All GeoDramas calls run off the main thread; stale results are
// dropped with a per-request counter, the same pattern as playEmbed.

internal fun MainActivity.buildDramasView(): LinearLayout {
    val view = column().apply {
        // TV overscan: 32dp sides so nothing is cut (same as the
        // main layout's root padding).
        setPadding(dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12),
            dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(bg0, bg1, bg2),
        )
        // Clickable so touches don't fall through — but NOT focusable
        // (a focusable full-screen root traps D-pad focus; see Movies).
        isClickable = true
        visibility = View.GONE
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(button("‹ Live TV") { closeDramas() },
        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
    // Har Pal Geo avatar (loaded from YouTube) over a "G" tile fallback.
    val avatarTile = FrameLayout(this)
    val avatarInitial = TextView(this).apply {
        text = "G"
        textSize = if (isTv) 20f else 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi, 12)
    }
    avatarTile.addView(avatarInitial, FrameLayout.LayoutParams(-1, -1))
    dramaAvatar = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    avatarTile.addView(dramaAvatar, FrameLayout.LayoutParams(-1, -1))
    header.addView(avatarTile, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
        marginEnd = dp(10)
    })
    header.addView(label("Geo Dramas", if (isTv) 26 else 22, textColor).apply {
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.02f
    })
    view.addView(header)
    // Now-playing bar: when an episode is already playing, the section
    // says so and offers a shortcut back to the player.
    dramaNowPlayingBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glassShape(16)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        visibility = View.GONE
    }
    dramaNowPlayingLabel = TextView(this).apply {
        textSize = if (isTv) 15f else 14f
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    dramaNowPlayingBar.addView(dramaNowPlayingLabel, LinearLayout.LayoutParams(0, -2, 1f))
    dramaNowPlayingBar.addView(button("▶ Player") { closeDramas() },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    view.addView(dramaNowPlayingBar, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(10)
    })

    // ---- Drama list pane ----
    dramasListPane = column()
    val statusRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    dramaStatus = label("Geo ke latest dramas — Har Pal Geo (YouTube).", if (isTv) 15 else 13, muted)
    statusRow.addView(dramaStatus, LinearLayout.LayoutParams(0, -2, 1f))
    statusRow.addView(button("↻ Refresh") { loadDramas(forceRefresh = true) },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    dramasListPane.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(12)
        bottomMargin = dp(4)
    })
    dramaProgress = ProgressBar(this).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(cyan)
    }
    dramasListPane.addView(dramaProgress, LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = dp(8)
        bottomMargin = dp(8)
    })
    dramaResults = column()
    dramaResultsScroll = ScrollView(this).apply { addView(dramaResults) }
    dramasListPane.addView(dramaResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    view.addView(dramasListPane, LinearLayout.LayoutParams(-1, 0, 1f))

    // ---- Episodes pane ----
    dramasEpisodesPane = column().apply { visibility = View.GONE }
    dramasEpisodesPane.addView(button("‹ Dramas") { showDramasList() },
        LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(4)
        })
    dramaEpisodesTitle = TextView(this).apply {
        textSize = if (isTv) 24f else 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
    }
    dramasEpisodesPane.addView(dramaEpisodesTitle)
    dramaEpisodesMeta = label("", if (isTv) 15 else 13, muted)
    dramasEpisodesPane.addView(dramaEpisodesMeta)
    dramaEpisodesStatus = label("", if (isTv) 15 else 13, muted)
    dramasEpisodesPane.addView(dramaEpisodesStatus)
    dramaEpisodesList = column()
    dramaEpisodesScroll = ScrollView(this).apply { addView(dramaEpisodesList) }
    dramasEpisodesPane.addView(dramaEpisodesScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    view.addView(dramasEpisodesPane, LinearLayout.LayoutParams(-1, 0, 1f))
    return view
}


internal fun MainActivity.openDramas() {
    ensureDramasView()
    dramasOpen = true
    dramasView.visibility = View.VISIBLE
    // Focus trap, same as Movies: the overlay covers the Live TV
    // layout, but FocusFinder could still reach the rows and buttons
    // underneath and the selection would vanish behind it.
    rootView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    // Now-playing bar: show it only when an episode is actually playing.
    val drama = playingDrama
    if (drama != null) {
        dramaNowPlayingLabel.text = "▶ Abhi chal raha hai: $playingDramaTitle — ${drama.shortTitle}"
        dramaNowPlayingBar.visibility = View.VISIBLE
    } else {
        dramaNowPlayingBar.visibility = View.GONE
    }
    showDramasList()
    // Reload when the in-memory list is stale (6h, matching the disk
    // cache) so new episodes show up by themselves day after day.
    if (currentDramas.isEmpty() ||
        System.currentTimeMillis() - dramasLoadedAt > DRAMA_REFRESH_AFTER_MS) {
        loadDramas()
    } else {
        loadDramaAvatar()
    }
}


internal fun MainActivity.closeDramas() {
    dramasOpen = false
    dramasView.visibility = View.GONE
    // Release the focus trap (unless the Movies overlay is open —
    // it sets its own) before focus goes back to the Live TV layout.
    if (!moviesOpen) rootView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    // An episode keeps playing underneath when the browser is closed.
    focusBrowse()
}


internal fun MainActivity.showDramasList() {
    dramasEpisodesPane.visibility = View.GONE
    dramasListPane.visibility = View.VISIBLE
    dramaResults.getChildAt(0)?.requestFocus()
}


internal fun MainActivity.showDramaEpisodesPane() {
    dramasListPane.visibility = View.GONE
    dramasEpisodesPane.visibility = View.VISIBLE
}


/** Loads the Har Pal Geo avatar into the header icon (parsed from the
 * playlists response — no extra request). No-op until dramas() ran once. */
internal fun MainActivity.loadDramaAvatar() {
    val url = geoDramas.channelAvatarUrl
    if (url.isNotEmpty()) LogoLoader.load(this, url, dramaAvatar)
}


internal fun MainActivity.loadDramas(forceRefresh: Boolean = false) {
    val request = ++dramaListRequest
    dramaProgress.visibility = View.VISIBLE
    dramaStatus.text = "Dramas load ho rahe hain…"
    dramaResults.removeAllViews()
    imports.execute {
        try {
            if (forceRefresh) geoDramas.clearCache()
            val dramas = geoDramas.dramas()
            runOnUiThread {
                if (isDestroyed || request != dramaListRequest) return@runOnUiThread
                dramaProgress.visibility = View.GONE
                loadDramaAvatar()
                if (dramas.isEmpty()) {
                    dramaStatus.text = "Dramas nahi mile — internet check karke Refresh dabayein."
                } else {
                    currentDramas = dramas
                    dramasLoadedAt = System.currentTimeMillis()
                    dramaStatus.text = "${dramas.size} dramas — sab se nayi episode wala upar aayega."
                    showDramaRows(dramas)
                    refreshDramaDates(request)
                }
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != dramaListRequest) return@runOnUiThread
                dramaProgress.visibility = View.GONE
                dramaStatus.text = "Dramas nahi mile: ${error.message}"
            }
        }
    }
}


/**
 * Background re-sort: fetches each drama's latest-episode date (one
 * playlist page per drama — each fetch also warms that drama's episode
 * cache, so opening it later is instant) and re-sorts the list once,
 * newest episode on top. Runs on the dedicated `dramaDates` executor
 * so it never blocks a user's episode tap. The list only rebuilds when
 * the order actually changed, so D-pad focus is not disturbed.
 */
internal fun MainActivity.refreshDramaDates(listRequest: Int) {
    val request = ++dramaDatesRequest
    dramaDates.execute {
        val dated = mutableListOf<GeoDramas.Drama>()
        for (drama in currentDramas) {
            if (isDestroyed || request != dramaDatesRequest || listRequest != dramaListRequest) return@execute
            val episodes = geoDramas.episodes(drama.playlistId)
            val latestMs = episodes.maxOfOrNull { it.publishedMs } ?: 0L
            val latest = episodes.firstOrNull()
            val label = when {
                latest == null -> ""
                latest.dateLabel.isNotEmpty() -> "${latest.shortTitle} · ${latest.dateLabel}"
                else -> latest.shortTitle
            }
            dated += drama.copy(latestMs = latestMs, latestLabel = label)
        }
        runOnUiThread {
            if (isDestroyed || request != dramaDatesRequest || listRequest != dramaListRequest) return@runOnUiThread
            val before = currentDramas.map { it.playlistId }
            currentDramas = dated.sortedWith(
                compareByDescending<GeoDramas.Drama> { it.latestMs > 0 }
                    .thenByDescending { it.latestMs },
            )
            if (dramasOpen && dramasListPane.visibility == View.VISIBLE &&
                currentDramas.map { it.playlistId } != before) {
                showDramaRows(currentDramas)
            }
        }
    }
}


internal fun MainActivity.showDramaRows(dramas: List<GeoDramas.Drama>) {
    dramaResults.removeAllViews()
    for (drama in dramas) {
        dramaResults.addView(dramaRow(drama), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    dramaResultsScroll.post { dramaResultsScroll.scrollTo(0, 0) }
    dramaResults.getChildAt(0)?.requestFocus()
}


/** Glass drama card: 16:9 thumbnail (letter tile behind it while it
 * loads), bold title, episode count + latest-episode label. */
internal fun MainActivity.dramaRow(drama: GeoDramas.Drama): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(if (isTv) 96 else 88)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rowBackground(false)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { openDramaEpisodes(drama) }
    }
    val thumbWidth = dp(if (isTv) 144 else 128)
    val thumbHeight = dp(if (isTv) 81 else 72)
    val tileView = FrameLayout(this)
    val initial = TextView(this).apply {
        text = drama.title.take(1).uppercase()
        textSize = if (isTv) 22f else 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi, 12)
    }
    tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
    if (drama.thumbnailUrl.isNotEmpty()) {
        val thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        tileView.addView(thumbView, FrameLayout.LayoutParams(-1, -1))
        LogoLoader.load(this, drama.thumbnailUrl, thumbView)
    }
    row.addView(tileView, LinearLayout.LayoutParams(thumbWidth, thumbHeight))
    val texts = column()
    texts.addView(TextView(this).apply {
        text = drama.title
        textSize = if (isTv) 17f else 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    val metaParts = mutableListOf<String>()
    if (drama.episodeCount > 0) metaParts += "${drama.episodeCount} episodes"
    if (drama.latestLabel.isNotEmpty()) metaParts += drama.latestLabel
    if (metaParts.isNotEmpty()) {
        texts.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 13f
            setTextColor(muted2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    return row
}


internal fun MainActivity.openDramaEpisodes(drama: GeoDramas.Drama) {
    val request = ++dramaEpisodesRequest
    dramaEpisodesTitle.text = drama.title
    dramaEpisodesMeta.text =
        if (drama.episodeCount > 0) "${drama.episodeCount} episodes" else ""
    dramaEpisodesStatus.text = "Episodes load ho rahe hain…"
    dramaEpisodesList.removeAllViews()
    showDramaEpisodesPane()
    imports.execute {
        val episodes = geoDramas.episodes(drama.playlistId)
        runOnUiThread {
            if (isDestroyed || request != dramaEpisodesRequest) return@runOnUiThread
            if (episodes.isEmpty()) {
                dramaEpisodesStatus.text = "Episodes nahi mile — internet check karke dobara kholein."
            } else {
                dramaEpisodesStatus.text = "${episodes.size} episodes — sab se nayi upar hai."
                showDramaEpisodes(drama, episodes)
            }
        }
    }
}


internal fun MainActivity.showDramaEpisodes(drama: GeoDramas.Drama, episodes: List<GeoDramas.Episode>) {
    dramaEpisodesList.removeAllViews()
    for (episode in episodes) {
        dramaEpisodesList.addView(episodeRow(drama, episode), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    dramaEpisodesScroll.post { dramaEpisodesScroll.scrollTo(0, 0) }
    dramaEpisodesList.getChildAt(0)?.requestFocus()
}


/** Glass episode card: small 16:9 thumbnail, "Episode N", air date +
 * views. Tap plays the episode in the main player. */
internal fun MainActivity.episodeRow(drama: GeoDramas.Drama, episode: GeoDramas.Episode): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(if (isTv) 80 else 72)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rowBackground(false)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { playDramaEpisode(drama, episode) }
    }
    val thumbWidth = dp(if (isTv) 112 else 104)
    val thumbHeight = dp(if (isTv) 63 else 59)
    val tileView = FrameLayout(this)
    val initial = TextView(this).apply {
        text = episode.shortTitle.take(1).uppercase()
        textSize = if (isTv) 20f else 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi, 12)
    }
    tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
    val thumbView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    tileView.addView(thumbView, FrameLayout.LayoutParams(-1, -1))
    LogoLoader.load(this, "https://i.ytimg.com/vi/${episode.videoId}/hqdefault.jpg", thumbView)
    row.addView(tileView, LinearLayout.LayoutParams(thumbWidth, thumbHeight))
    val texts = column()
    texts.addView(TextView(this).apply {
        text = episode.shortTitle
        textSize = if (isTv) 16f else 15f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    val metaParts = mutableListOf<String>()
    if (episode.dateLabel.isNotEmpty()) metaParts += episode.dateLabel
    if (episode.views.isNotEmpty()) metaParts += episode.views
    if (metaParts.isNotEmpty()) {
        texts.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 13f
            setTextColor(muted2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    return row
}


internal fun MainActivity.playDramaEpisode(drama: GeoDramas.Drama, episode: GeoDramas.Episode) {
    closeDramas()
    startDramaPlayback(episode, drama.title)
}


/**
 * Plays a drama episode in the main player, with the same setup as
 * [play] but without touching the channel store: no recent entry, no
 * last-channel — an episode is not a channel. The episode plays as a
 * WebView embed (loop off, so it ends instead of replaying), and the
 * ExoPlayer controls stay hidden exactly as for web channels.
 */
internal fun MainActivity.startDramaPlayback(episode: GeoDramas.Episode, dramaTitle: String) {
    // An episode replaces a movie — save the movie's place first.
    saveMovieProgress(async = false)
    stopWeb()
    selected = null
    playingMovie = null
    playingMovieUrl = null
    playingMovieHeaders = emptyMap()
    playingDrama = episode
    playingDramaTitle = dramaTitle
    setDramaMeta(episode, dramaTitle)
    val showBars = !fullScreen
    infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
    playPauseButton.text = "❚❚"
    speed = 1f
    playerController?.setSpeed(1f)
    setSpeedText("1x")
    videoOptions = emptyList()
    selectedVideoIndex = -1
    seekable = false
    stopSeekUpdates()
    updateFavoriteButton()
    applyControlVisibility()
    markPlayingRow()
    updateBrowseVisibility()
    playerController?.stop()
    playEmbed(episode.videoId, loop = false)
    bringPlayerOverlaysToFront()
    showPlayerControls()
}


internal fun MainActivity.setDramaMeta(episode: GeoDramas.Episode, dramaTitle: String) {
    val name = "$dramaTitle — ${episode.shortTitle}"
    metaName.text = name
    infoName.text = name
    metaCategory.text = "Geo Dramas"
    metaCategory.visibility = View.VISIBLE
    metaLiveBadge.visibility = View.GONE
    infoLiveBadge.visibility = View.GONE
}
