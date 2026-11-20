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
 * The Movies browser: search, home, rows, favorites.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


// ---- MovieBox movies (v1) ----
//
// A full-screen Movies SECTION (same Qwen navy-glass look) beside Live
// TV: header with a "Live TV" way back, a now-playing bar, quick-search
// chips, then search → results (poster + name + year + HINDI badge) →
// details → audio (dub) + quality pickers → Play in the main player.
// Technically it is an overlay view over the Live TV layout, so opening
// it never detaches the player view underneath.
// All MovieBox calls run on the `imports` executor; stale results are
// dropped with a per-request counter, the same pattern as playEmbed.

internal fun MainActivity.buildMoviesView(): LinearLayout {
    val view = column().apply {
        // TV overscan: 32dp sides so nothing is cut (same as the
        // main layout's root padding).
        setPadding(dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12),
            dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(bg0, bg1, bg2),
        )
        // Clickable so touches don't fall through to the Live TV UI
        // underneath — but NOT focusable: a focusable full-screen root
        // traps D-pad focus (every child sits inside its rect, so no
        // direction finds a next view) and navigation dies.
        isClickable = true
        visibility = View.GONE
    }
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val backButton = button("‹ Live TV") { closeMovies() }
    backButton.id = View.generateViewId()
    header.addView(backButton,
        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
    header.addView(label("🎬 Movies", if (isTv) 26 else 22, textColor).apply {
        setTypeface(null, Typeface.BOLD)
        letterSpacing = 0.02f
    })
    view.addView(header)
    // Now-playing bar: when a movie is already playing, the section
    // says so and offers a shortcut back to the player.
    movieNowPlayingBar = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = glassShape(16)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        visibility = View.GONE
    }
    movieNowPlayingLabel = TextView(this).apply {
        textSize = if (isTv) 15f else 14f
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    movieNowPlayingBar.addView(movieNowPlayingLabel, LinearLayout.LayoutParams(0, -2, 1f))
    movieNowPlayingBar.addView(button("▶ Player") { closeMovies() },
        LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    view.addView(movieNowPlayingBar, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(10)
    })

    // ---- Search + results pane ----
    moviesListPane = column()
    val searchRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    movieSearchInput = EditText(this).apply {
        id = View.generateViewId()
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = "Movie ka naam…"
        setHintTextColor(muted2)
        setTextColor(textColor)
        textSize = if (isTv) 17f else 15f
        minimumHeight = dp(if (isTv) 56 else 52)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = glassStateList(28)
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchMovies()
                true
            } else {
                false
            }
        }
        // A focused EditText can swallow D-pad up/down on some TVs
        // (the Leanback keyboard session eats them) — forward them
        // to focus search so navigation never dies on the search box.
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN
                    else View.FOCUS_UP
                focusSearch(direction)?.requestFocus()
                true
            } else {
                false
            }
        }
    }
    // No Search button next to the bar — the keyboard's search key
    // runs the search (the button was a redundant tap target).
    searchRow.addView(movieSearchInput, LinearLayout.LayoutParams(0, -2, 1f))
    moviesListPane.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(12)
        bottomMargin = dp(8)
    })
    // Quick searches: one tap runs a real MovieBox search for a popular
    // term (no fake listings — these are just search shortcuts). The
    // Home chip goes back to Continue Watching + Fav Movies.
    val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    movieQuickRow = quickRow
    val homeChip = chip("⌂ Home") { showMoviesHome() }
    homeChip.id = View.generateViewId()
    movieHomeChip = homeChip
    quickRow.addView(homeChip, LinearLayout.LayoutParams(-2, -2).apply {
        marginEnd = dp(8)
    })
    for (term in listOf("Marvel", "DC", "Bollywood", "Punjabi", "Action", "Comedy")) {
        val quickChip = chip(term) { quickSearchMovie(term) }
        quickRow.addView(quickChip, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    // Explicit vertical links for the search box — geometry alone is
    // unreliable here (the chips sit inside a horizontal scroller).
    backButton.nextFocusDownId = movieSearchInput.id
    movieSearchInput.nextFocusDownId = homeChip.id
    movieSearchInput.nextFocusUpId = backButton.id
    homeChip.nextFocusUpId = movieSearchInput.id
    // End stops: LEFT past Home / RIGHT past the last chip must not
    // wander off the row (FocusFinder could otherwise pick a view
    // behind the overlay, and the selection would vanish).
    homeChip.nextFocusLeftId = View.NO_ID
    quickRow.getChildAt(quickRow.childCount - 1)?.nextFocusRightId = View.NO_ID
    val quickScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(quickRow)
    }
    moviesListPane.addView(quickScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    })
    // Tabs (v2.0.35): Home / Movies / TV Shows / Genres / My List
    // (+ Downloads on mobile) — the Netflix-style home navigation.
    moviesTabRow = buildMoviesTabsRow()
    val tabScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(moviesTabRow)
    }
    moviesListPane.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    })
    movieStatus = label(
        "Movie ka naam likh kar search karein — Hindi dubbed bhi milti hai.",
        if (isTv) 15 else 13, muted,
    )
    moviesListPane.addView(movieStatus)
    movieProgress = ProgressBar(this).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(cyan)
    }
    moviesListPane.addView(movieProgress, LinearLayout.LayoutParams(-2, -2).apply {
        topMargin = dp(8)
        bottomMargin = dp(8)
    })
    movieResults = column()
    movieResultsScroll = ScrollView(this).apply { addView(movieResults) }
    moviesListPane.addView(movieResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
    view.addView(moviesListPane, LinearLayout.LayoutParams(-1, 0, 1f))

    // ---- Details pane ----
    moviesDetailsPane = column().apply { visibility = View.GONE }
    val detailsScroll = ScrollView(this)
    val detailsInner = column()
    detailsScroll.addView(detailsInner)
    movieDetailsTitle = TextView(this).apply {
        textSize = if (isTv) 24f else 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
    }
    detailsInner.addView(movieDetailsTitle)
    movieDetailsMeta = label("", if (isTv) 15 else 13, muted)
    detailsInner.addView(movieDetailsMeta)
    movieDetailsDesc = TextView(this).apply {
        textSize = if (isTv) 15f else 14f
        setTextColor(muted)
    }
    detailsInner.addView(movieDetailsDesc, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
        bottomMargin = dp(12)
    })
    detailsInner.addView(label("Audio", if (isTv) 15 else 13, textColor).apply {
        setTypeface(null, Typeface.BOLD)
    })
    val dubScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    movieDubRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    dubScroll.addView(movieDubRow)
    detailsInner.addView(dubScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(12)
    })
    // Series only (v2.0.27): season chips + episode list. Hidden for
    // movies; the quality row below is per-episode for series.
    movieSeasonLabel = label("Season", if (isTv) 15 else 13, textColor).apply {
        setTypeface(null, Typeface.BOLD)
        visibility = View.GONE
    }
    detailsInner.addView(movieSeasonLabel)
    movieSeasonScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        visibility = View.GONE
    }
    movieSeasonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    movieSeasonScroll.addView(movieSeasonRow)
    detailsInner.addView(movieSeasonScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(12)
    })
    movieEpisodeLabel = label("Episodes", if (isTv) 15 else 13, textColor).apply {
        setTypeface(null, Typeface.BOLD)
        visibility = View.GONE
    }
    detailsInner.addView(movieEpisodeLabel)
    movieEpisodeColumn = column()
    movieEpisodeColumn.visibility = View.GONE
    detailsInner.addView(movieEpisodeColumn, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(12)
    })
    detailsInner.addView(label("Quality", if (isTv) 15 else 13, textColor).apply {
        setTypeface(null, Typeface.BOLD)
    })
    val qualityScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    movieQualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    qualityScroll.addView(movieQualityRow)
    detailsInner.addView(qualityScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(12)
    })
    movieDetailsStatus = label("", if (isTv) 15 else 13, muted)
    detailsInner.addView(movieDetailsStatus)
    moviePlayButton = Button(this).apply {
        text = "▶ Play"
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        textSize = if (isTv) 18f else 16f
        minimumHeight = dp(if (isTv) 56 else 52)
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(glass, glassStroke, 28, 1))
            addState(intArrayOf(android.R.attr.state_focused), glassShape(28, cyan, 3))
            addState(intArrayOf(android.R.attr.state_pressed), glassShape(28, cyan, 2))
            addState(intArrayOf(), gradientPill(28))
        }
        isEnabled = false
        setOnClickListener { playSelectedMovie() }
    }
    detailsInner.addView(moviePlayButton, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(12)
    })
    movieFavButton = button("♡ Favorite") { toggleFavoriteMovie() }
    detailsInner.addView(movieFavButton, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
    })
    // Download (v2.0.35, mobile only — the user did not approve TV
    // downloads): saves the movie file so it plays without internet.
    movieDownloadButton = button("⬇ Download") { startMovieDownload() }
    movieDownloadButton.visibility = if (isTv) View.GONE else View.VISIBLE
    detailsInner.addView(movieDownloadButton, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
    })
    detailsInner.addView(button("‹ Back to results") { showMoviesList() },
        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    moviesDetailsPane.addView(detailsScroll, LinearLayout.LayoutParams(-1, -1))
    view.addView(moviesDetailsPane, LinearLayout.LayoutParams(-1, 0, 1f))
    return view
}


/** Stops the currently playing channel (if any) — the Movies browser
 * opens over a silent screen instead of a channel that keeps playing
 * behind a movie search. Movies keep playing underneath. */
internal fun MainActivity.stopPlayingChannel() {
    if (selected == null) return
    stopPlayback()
    selected = null
    playPauseButton.text = "▶"
    setChannelMeta(null)
    status.text = ""
    applyControlVisibility()
    markPlayingRow()
    updateBrowseVisibility()
}


/** Builds the Movies overlay on first open (see onCreate — it is not
 * built at startup, so the app opens faster). */
internal fun MainActivity.ensureMoviesView() {
    if (moviesViewBuilt) return
    moviesViewBuilt = true
    moviesView = buildMoviesView()
    contentFrame.addView(moviesView, FrameLayout.LayoutParams(-1, -1))
}


internal fun MainActivity.openMovies() {
    ensureMoviesView()
    // A channel must not keep playing behind the Movies browser.
    stopPlayingChannel()
    moviesOpen = true
    moviesView.visibility = View.VISIBLE
    // Focus trap: the Movies overlay covers the Live TV layout, but
    // FocusFinder can still reach the rows and buttons underneath —
    // D-pad focus used to escape behind the overlay and the selection
    // vanished (the tabs themselves never moved). While the overlay
    // is open, the Live TV subtree takes no focus at all.
    rootView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    // Now-playing bar: show it only when a movie is actually playing.
    val movie = playingMovie
    if (movie != null) {
        movieNowPlayingLabel.text = "▶ Abhi chal rahi hai: ${movie.title}"
        movieNowPlayingBar.visibility = View.VISIBLE
    } else {
        movieNowPlayingBar.visibility = View.GONE
    }
    showMoviesList()
}


internal fun MainActivity.closeMovies() {
    stopMoviesHero()
    moviesOpen = false
    moviesView.visibility = View.GONE
    // Release the focus trap before focus goes back to the Live TV layout.
    rootView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    // A movie keeps playing underneath when the browser is closed.
    focusBrowse()
}


internal fun MainActivity.showMoviesList() {
    moviesDetailsPane.visibility = View.GONE
    moviesListPane.visibility = View.VISIBLE
    // No search on screen — re-render whatever tab (or See All list)
    // was showing, so a favorite toggle or a finished movie is
    // reflected. A search result stays as it is.
    if (!movieResultsAreSearch) renderCurrentMoviesTab()
    // Focus landing: Back from a movie's details returns to the row
    // it came from; otherwise the first row (not the search box —
    // focusing it pops the keyboard and D-pad up/down can die on it).
    // With no rows at all, the Home chip is the landing.
    val returnRow = moviesReturnRow
    moviesReturnRow = -1
    val target = if (returnRow in 0 until movieResults.childCount) {
        movieResults.getChildAt(returnRow)?.takeIf { it.isFocusable }
    } else {
        null
    } ?: firstFocusableDeep(movieResults) ?: movieHomeChip
    target.post { if (moviesOpen && target.isShown) target.requestFocus() }
}


internal fun MainActivity.showMoviesDetails() {
    moviesListPane.visibility = View.GONE
    moviesDetailsPane.visibility = View.VISIBLE
    // The focused row just went GONE — without a landing, D-pad focus
    // is lost and the buttons become unreachable. Fav is always
    // enabled, so it is the safe landing; Play takes focus once the
    // streams load (see loadMovieStreams).
    movieFavButton.post { if (moviesOpen && movieFavButton.isShown) movieFavButton.requestFocus() }
}


/** One-tap quick search: fills the search box and runs the search. */
internal fun MainActivity.quickSearchMovie(term: String) {
    movieSearchInput.setText(term)
    searchMovies()
}


internal fun MainActivity.searchMovies() {
    val query = movieSearchInput.text.toString().trim()
    if (query.isEmpty()) {
        toast("Movie ka naam likhein.")
        return
    }
    movieResultsAreSearch = true
    val request = ++movieSearchRequest
    // Hide the keyboard so the results are visible on phones.
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(movieSearchInput.windowToken, 0)
    movieProgress.visibility = View.VISIBLE
    movieStatus.text = "Search ho raha hai…"
    movieResults.removeAllViews()
    stopMoviesHero()
    imports.execute {
        try {
            val results = movieBox.search(query)
            runOnUiThread {
                if (isDestroyed || request != movieSearchRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                if (results.isEmpty()) {
                    movieStatus.text = "Koi movie nahi mili — naam badal kar dobara try karein."
                } else {
                    movieStatus.text = "${results.size} movies mili."
                    showMovieResults(results)
                }
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != movieSearchRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                movieStatus.text = "Search nahi ho saka: ${error.message}"
            }
        }
    }
}


internal fun MainActivity.showMovieResults(movies: List<MovieBox.Movie>) {
    movieResults.removeAllViews()
    val rows = mutableListOf<View>()
    for (movie in movies) {
        val row = movieRow(movie)
        rows += row
        movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    linkMovieRowActions(rows)
    // UP from the first row reaches the tabs (geometry alone is
    // unreliable across the scroller boundary).
    rows.firstOrNull()?.nextFocusUpId = firstMoviesTabChipId()
    syncMovieChipFocusTargets()
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
    movieResults.getChildAt(0)?.requestFocus()
}

/** Points every quick chip's DOWN at the first tab chip — geometry
 * alone is unreliable across the scroller boundary, and without it
 * DOWN from a chip could land outside the Movies section and the
 * selection would vanish. */
internal fun MainActivity.syncMovieChipFocusTargets() {
    // DOWN from a quick chip reaches the tabs (the tabs' own DOWN
    // targets point at the content — see linkMoviesTabFocus).
    val targetId = firstMoviesTabChipId()
    for (i in 0 until movieQuickRow.childCount) {
        movieQuickRow.getChildAt(i)?.nextFocusDownId = targetId
    }
}


/** Links each movie row's action button (♥ fav / ✕ remove) up and down
 * to the neighbouring ROWS — without this, D-pad focus falls down the
 * action column and the rows become unreachable. LEFT/RIGHT between a
 * row and its own action is wired in movieRow/continueRow. */
internal fun MainActivity.linkMovieRowActions(rows: List<View>) {
    for (i in rows.indices) {
        val row = rows[i] as? LinearLayout ?: continue
        val action = (0 until row.childCount).mapNotNull { row.getChildAt(it) }
            .firstOrNull { it.tag == "rowAction" } ?: continue
        if (i > 0) action.nextFocusUpId = rows[i - 1].id
        if (i < rows.size - 1) action.nextFocusDownId = rows[i + 1].id
    }
}


/** Movies home tab: the Home chip lands here — hero + Continue
 * Watching + Trending / Popular rails (see renderMoviesHome). */
internal fun MainActivity.showMoviesHome() {
    selectMoviesTab(MoviesTab.HOME)
}


internal fun MainActivity.sectionLabel(text: String) =
    label(text, if (isTv) 17 else 16, textColor).apply {
        setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(10), 0, dp(4))
    }


/** Continue Watching row: poster + title + remaining time + a cyan
 * progress bar, with an ✕ button to remove it by hand. Tapping the
 * row resumes the movie where it stopped. */
internal fun MainActivity.continueRow(entry: MovieStore.ContinueEntry): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        minimumHeight = dp(if (isTv) 96 else 88)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rowBackground(false)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { resumeMovie(entry) }
    }
    val posterWidth = dp(if (isTv) 60 else 56)
    val posterHeight = dp(if (isTv) 90 else 84)
    row.addView(
        posterTile(entry.posterUrl, entry.title),
        LinearLayout.LayoutParams(posterWidth, posterHeight),
    )
    val texts = column()
    texts.addView(TextView(this).apply {
        text = entry.title
        textSize = if (isTv) 17f else 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    texts.addView(TextView(this).apply {
        val ep = entry.episodeLabel
        val prefix = if (ep.isNotEmpty()) "$ep · " else ""
        text = "$prefix${(entry.progress * 100).toInt()}% dekhi · ${formatTime(entry.remainingMs)} baaki"
        textSize = 13f
        setTextColor(muted2)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    })
    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = (entry.progress * 100).toInt()
        progressTintList = ColorStateList.valueOf(cyan)
        progressBackgroundTintList = ColorStateList.valueOf(glassHi)
    }
    texts.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply {
        topMargin = dp(6)
    })
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    val removeButton = iconButton("✕") {
        movieStore.removeContinue(entry.subjectId)
        toast("Continue Watching se nikal di.")
        renderCurrentMoviesTab()
    }
    // D-pad: RIGHT from the row reaches the remove button (it sits
    // INSIDE the row, so geometry alone never finds it); UP/DOWN
    // from the button go to the neighbouring rows (linkMovieRowActions).
    removeButton.id = View.generateViewId()
    removeButton.tag = "rowAction"
    row.nextFocusRightId = removeButton.id
    removeButton.nextFocusLeftId = row.id
    row.addView(removeButton, LinearLayout.LayoutParams(-2, -2).apply {
        marginStart = dp(8)
    })
    return row
}


/** 2:3 poster tile with a letter tile behind it while the image loads. */
internal fun MainActivity.posterTile(posterUrl: String, title: String): View {
    val tileView = FrameLayout(this)
    val initial = TextView(this).apply {
        text = title.take(1).uppercase()
        textSize = if (isTv) 22f else 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi, 12)
    }
    tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
    if (posterUrl.isNotEmpty()) {
        val posterView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        tileView.addView(posterView, FrameLayout.LayoutParams(-1, -1))
        LogoLoader.load(this, posterUrl, posterView)
    }
    return tileView
}


/** Small square icon button (♥ favorite, ✕ remove) for movie rows. */
internal fun MainActivity.iconButton(value: String, action: (Button) -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    setTextColor(muted)
    textSize = if (isTv) 17f else 16f
    minimumWidth = 0
    minimumHeight = dp(44)
    setPadding(dp(12), dp(6), dp(12), dp(6))
    background = glassStateList(12)
    setOnClickListener { action(this) }
}


/** Favorite toggle from the details pane (the movie itself, not the
 * selected dub — a dub is the same movie in another language). */
internal fun MainActivity.toggleFavoriteMovie() {
    val details = currentMovieDetails ?: return
    val nowFav = movieStore.toggleFavorite(details.movie)
    updateMovieFavButton()
    toast(if (nowFav) "Fav Movies mein add ho gayi." else "Fav Movies se nikal di.")
}


internal fun MainActivity.updateMovieFavButton() {
    val movie = currentMovieDetails?.movie
    val fav = movie != null && movieStore.isFavorite(movie)
    movieFavButton.text = if (fav) "♥ Favorite" else "♡ Favorite"
}


/** Glass movie card: 2:3 poster (letter tile behind it while it loads),
 * bold title + year, and a cyan HINDI badge for Hindi-dubbed titles. */
internal fun MainActivity.movieRow(movie: MovieBox.Movie): View {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        minimumHeight = dp(if (isTv) 96 else 88)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = rowBackground(false)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        // Remember this row so Back from the details pane returns to it.
        setOnClickListener {
            moviesReturnRow = movieResults.indexOfChild(this)
            openMovieDetails(movie)
        }
    }
    val posterWidth = dp(if (isTv) 60 else 56)
    val posterHeight = dp(if (isTv) 90 else 84)
    row.addView(
        posterTile(movie.posterUrl, movie.title),
        LinearLayout.LayoutParams(posterWidth, posterHeight),
    )
    val texts = column()
    texts.addView(TextView(this).apply {
        text = movie.title
        textSize = if (isTv) 17f else 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    })
    if (movie.year.isNotEmpty()) {
        texts.addView(TextView(this).apply {
            text = movie.year
            textSize = 13f
            setTextColor(muted2)
        })
    }
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    if (movie.isHindi) {
        row.addView(TextView(this).apply {
            text = "HINDI"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = shape(cyan, cyan, 8, 0)
        })
    }
    if (movie.isSeries) {
        row.addView(TextView(this).apply {
            text = "SERIES"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = shape(purple, purple, 8, 0)
        }, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
    }
    // Favorite heart: red when this movie is in Fav Movies. Tapping
    // it only toggles the favorite — the row itself still opens the
    // details pane.
    val heart = iconButton(if (movieStore.isFavorite(movie)) "♥" else "♡") { btn ->
        val nowFav = movieStore.toggleFavorite(movie)
        btn.text = if (nowFav) "♥" else "♡"
        btn.setTextColor(if (nowFav) liveRed else muted)
        toast(if (nowFav) "Fav Movies mein add ho gayi." else "Fav Movies se nikal di.")
        if (moviesOpen && !movieResultsAreSearch) renderCurrentMoviesTab()
    }
    heart.setTextColor(if (movieStore.isFavorite(movie)) liveRed else muted)
    // D-pad: RIGHT from the row reaches the heart (it sits INSIDE the
    // row, so geometry alone never finds it); UP/DOWN from the heart
    // go to the neighbouring rows (linkMovieRowActions).
    heart.id = View.generateViewId()
    heart.tag = "rowAction"
    row.nextFocusRightId = heart.id
    heart.nextFocusLeftId = row.id
    row.addView(heart, LinearLayout.LayoutParams(-2, -2).apply {
        marginStart = dp(8)
    })
    return row
}
