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
import android.graphics.Rect
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
import androidx.core.view.OneShotPreDrawListener
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
// TV: TV has a sidebar (search + nav) with posters on the
// right; mobile keeps a top header + scrolling search and tabs. Then
// search → results (poster + name + year + HINDI badge) → details →
// audio (dub) + quality pickers → Play in the main player.
// Technically it is an overlay view over the Live TV layout, so opening
// it never detaches the player view underneath.
// All MovieBox calls run on the `imports` executor; stale results are
// dropped with a per-request counter, the same pattern as playEmbed.

internal enum class MovieSearchFilter(val label: String) {
    ALL("All"), MOVIES("Movies"), SHOWS("TV Shows")
}

internal data class MovieBrowsePosition(val cardTag: String, val scrollY: Int, val scrollX: Int)

private fun View.movieRailScroll(): HorizontalScrollView? {
    var ancestor = parent
    while (ancestor is View) {
        if (ancestor is HorizontalScrollView) return ancestor
        ancestor = ancestor.parent
    }
    return null
}

internal fun MainActivity.rememberMovieBrowsePosition(card: View) {
    val tag = card.tag as? String ?: return
    moviesReturnPosition = MovieBrowsePosition(tag, movieResultsScroll.scrollY, card.movieRailScroll()?.scrollX ?: 0)
}

internal fun MainActivity.buildMoviesView(): LinearLayout {
    val view = column().apply {
        // TV overscan: 32dp sides so nothing is cut (same as the
        // main layout's root padding).
        setPadding(dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12),
            dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12))
        background = demoBackground()
        // Clickable so touches don't fall through to the Live TV UI
        // underneath — but NOT focusable: a focusable full-screen root
        // traps D-pad focus (every child sits inside its rect, so no
        // direction finds a next view) and navigation dies.
        isClickable = true
        visibility = View.GONE
    }
    // No header row here: the app status bar sits above this browser
    // (v2.0.23) and carries the "Back to TV" button, so a second
    // "Live TV" row inside the browser would only duplicate it.
    // (The old "Abhi chal rahi hai" bar is gone in v2.0.24: it only
    // ever showed in mini-player mode, where the player's own top
    // bar already shows the title and its fullscreen chip is the way
    // back to fullscreen.)

    // ---- Search + results pane ----
    moviesListPane = column()
    movieStatus = label(
        "Movie ka naam likh kar search karein — Hindi dubbed bhi milti hai.",
        if (isTv) 15 else 13, muted,
    )
    movieProgress = ProgressBar(this).apply {
        visibility = View.GONE
        indeterminateTintList = ColorStateList.valueOf(cyan)
    }
    movieResults = column()
    movieSearchFilters = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }
    if (isTv) {
        // Two-pane (v2.0.37, glass panel + 300dp in v2.0.38): the
        // sidebar (search + nav) on the left, the posters
        // on the right. The sidebar is part of moviesListPane so
        // the details pane hides it too.
        moviesListPane.orientation = LinearLayout.HORIZONTAL
        moviesSidebarView = buildMoviesSidebar()
        moviesListPane.addView(moviesSidebarView,
            LinearLayout.LayoutParams(dp(300), -1))
        val content = column()
        content.addView(movieStatus)
        for (filter in MovieSearchFilter.entries) {
            val control = chip(filter.label) {
                movieSearchFilter = filter
                markChipByTag(movieSearchFilters, filter)
                showMovieResults(moveFocus = false)
            }
            control.tag = filter
            control.id = View.generateViewId()
            control.nextFocusLeftId = firstMoviesTabChipId()
            movieSearchFilters.addView(control, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(4)
            })
        }
        val filtersScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(movieSearchFilters)
        }
        content.addView(filtersScroll, LinearLayout.LayoutParams(-1, -2))
        content.addView(movieProgress, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        movieResultsScroll = ScrollView(this).apply { addView(movieResults) }
        content.addView(movieResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        moviesListPane.addView(content, LinearLayout.LayoutParams(0, -1, 1f).apply {
            marginStart = dp(16)
        })
    } else {
        // Mobile: the search bar + tabs scroll WITH the content
        // (v2.0.37) — they used to stay pinned above the list.
        // (No Search button next to the bar — the keyboard's search
        // key runs the search; the button was a redundant tap target.)
        movieSearchInput = buildMovieSearchInput()
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        searchRow.addView(movieSearchInput, LinearLayout.LayoutParams(0, -2, 1f))
        // Tabs: Home / Movies / TV Shows / Genres / My List /
        // Downloads — the Netflix-style home navigation.
        moviesTabRow = buildMoviesTabsRow()
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(moviesTabRow)
        }
        // Bright glass header panel (v2.0.39): on phones the
        // search bar + tabs are the Movies navigation, so they sit
        // inside the visible panel the TV sidebar has — v2.0.38
        // left mobile with no panel and the theme change was
        // invisible. The panel scrolls with the content, as the
        // search + tabs did before. Focus links below are ID-based,
        // so reparenting changes nothing about D-pad navigation.
        val headerPanel = brightGlassPanel(12)
        headerPanel.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })
        headerPanel.addView(tabScroll, LinearLayout.LayoutParams(-1, -2))
        val scrollContent = column()
        scrollContent.addView(headerPanel, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        scrollContent.addView(movieStatus)
        scrollContent.addView(movieProgress, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        scrollContent.addView(movieResults)
        movieResultsScroll = ScrollView(this).apply { addView(scrollContent) }
        moviesListPane.addView(movieResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        // Explicit vertical links — geometry alone is unreliable
        // across the scroller boundary. Up from the search goes to
        // the status bar's "Back to TV" button (v2.0.23).
        backToTvButton.nextFocusDownId = movieSearchInput.id
        movieSearchInput.nextFocusDownId = firstMoviesTabChipId()
        movieSearchInput.nextFocusUpId = backToTvButton.id
    }
    view.addView(moviesListPane, LinearLayout.LayoutParams(-1, 0, 1f))

    // ---- Details pane ----
    moviesDetailsPane = column().apply { visibility = View.GONE }
    movieDetailsScroll = ScrollView(this)
    val detailsInner = column()
    movieDetailsScroll.addView(detailsInner)
    val detailsHeader = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    movieDetailsPoster = FrameLayout(this).apply { visibility = View.GONE }
    detailsHeader.addView(movieDetailsPoster, LinearLayout.LayoutParams(dp(72), dp(108)).apply {
        marginEnd = dp(12)
    })
    val detailsHeading = column()
    detailsHeader.addView(detailsHeading, LinearLayout.LayoutParams(0, -2, 1f))
    detailsInner.addView(detailsHeader)
    movieDetailsTitle = TextView(this).apply {
        textSize = if (isTv) 25f else 20f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
    }
    detailsHeading.addView(movieDetailsTitle)
    movieDetailsMeta = label("", if (isTv) 15 else 13, muted)
    detailsHeading.addView(movieDetailsMeta)
    movieDetailsDesc = TextView(this).apply {
        textSize = if (isTv) 16f else 14f
        setTextColor(if (isTv) textColor else muted)
        if (isTv) {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
    }
    detailsInner.addView(movieDetailsDesc, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
        bottomMargin = dp(12)
    })
    movieDetailsMoreButton = button("More") {
        val expand = movieDetailsDesc.maxLines == 3
        movieDetailsDesc.maxLines = if (expand) Int.MAX_VALUE else 3
        movieDetailsMoreButton.text = if (expand) "Less" else "More"
    }.apply { visibility = View.GONE }
    detailsInner.addView(movieDetailsMoreButton, LinearLayout.LayoutParams(-2, -2).apply {
        bottomMargin = dp(12)
    })
    if (isTv) {
        movieDetailsDesc.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMovieSynopsisButton()
        }
    }
    // No Audio row here (v2.0.5): audio/dub selection lives in the
    // player (Audio button), so the details screen stays clean.
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
    val detailsActions = column()
    movieDetailsStatus = label("", if (isTv) 15 else 13, muted)
    detailsActions.addView(movieDetailsStatus)
    movieResumeButton = button("Resume", R.drawable.ic_play) {}.apply { visibility = View.GONE }
    detailsActions.addView(movieResumeButton, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
    })
    moviePlayButton = Button(this).apply {
        text = "Play"
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
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
    detailsActions.addView(moviePlayButton, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(12)
    })
    // Secondary actions in one compact row under Play (instead of four
    // stacked full-width buttons): Favorite / Download / Back share the
    // width. Download stays mobile-only.
    val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    movieFavButton = button("Favorite", R.drawable.ic_favorite_outline) { toggleFavoriteMovie() }
    actionsRow.addView(movieFavButton, LinearLayout.LayoutParams(0, dp(if (isTv) 56 else 52), 1f).apply {
        marginEnd = dp(8)
    })
    // Download (v2.0.35, mobile only — the user did not approve TV
    // downloads): saves the movie file so it plays without internet.
    movieDownloadButton = button("Download", R.drawable.ic_download) { startMovieDownload() }
    movieDownloadButton.visibility = if (isTv) View.GONE else View.VISIBLE
    actionsRow.addView(movieDownloadButton, LinearLayout.LayoutParams(0, dp(if (isTv) 56 else 52), 1f).apply {
        marginEnd = dp(8)
    })
    actionsRow.addView(button("Back", R.drawable.ic_arrow_back) { showMoviesList() },
        LinearLayout.LayoutParams(0, dp(if (isTv) 56 else 52), 1f))
    detailsActions.addView(actionsRow, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(8)
    })
    if (isTv) {
        detailsInner.addView(detailsActions, 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    } else {
        detailsInner.addView(detailsActions)
    }
    moviesDetailsPane.addView(movieDetailsScroll, LinearLayout.LayoutParams(-1, -1))
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
    playPauseButton.setImageResource(R.drawable.ic_play)
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
    // is open, the channel list takes no focus at all. The trap is
    // on the library pane only (v2.0.23): the status bar ("Back to
    // TV") and the mini-player controls must stay reachable, so
    // fullscreen works from the mini player itself.
    libraryPane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    // The "Abhi chal rahi hai" bar is gone (v2.0.24) — see
    // buildMoviesView: the mini player's own top bar shows the title.
    showMoviesList()
    // Mini player: a movie playing windowed puts the small player on
    // top and this browser below it.
    updateMiniPlayerLayout()
}


/** Reopens the Movies browser at the details screen of the title
 * that was just playing (Back from playback, or Back from fullscreen
 * into the mini player). The details pane is already rendered — Play
 * was pressed from it — so this only re-shows the overlay, with the
 * same focus trap as openMovies(); nothing is reloaded. The Now
 * Playing bar shows while a movie plays (mini player): its Player
 * button is the way back to fullscreen. */
internal fun MainActivity.openMoviesToDetails() {
    ensureMoviesView()
    // A channel must not keep playing behind the Movies browser.
    stopPlayingChannel()
    moviesOpen = true
    moviesView.visibility = View.VISIBLE
    // Same focus trap as openMovies(): the library pane only, so the
    // status bar and the mini-player controls stay reachable.
    libraryPane.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    val details = currentMovieDetails
    if (details != null) {
        refreshMovieResume(details)
        val playingSubject = playingMovie?.subjectId
        if (isTv && playingEpisodeSeason > 0 && playingEpisodeEpisode > 0 &&
            details.audioDubs().any { it.subjectId == playingSubject } &&
            playingSeriesSeasons.any { it.number == playingEpisodeSeason }
        ) {
            selectedDubId = playingSubject
            currentSeriesSeasons = playingSeriesSeasons
            selectedSeason = playingEpisodeSeason
            rebuildSeasonChips()
            showSeasonEpisodes(selectedSeason, playingEpisodeEpisode)
            if (currentMovieStreams.isNotEmpty()) movieDetailsStatus.text =
                "S$selectedSeason E$selectedEpisode · Stream ready hai"
        }
    }
    // The "Abhi chal rahi hai" bar is gone (v2.0.24) — see
    // buildMoviesView: the mini player's own top bar shows the title.
    showMoviesDetails()
    updateMiniPlayerLayout()
}


internal fun MainActivity.closeMovies(stopMovie: Boolean = false) {
    if (stopMovie && playingMovie != null) {
        // Leaving the Movies section while a movie plays in the mini
        // player stops it — otherwise it would keep playing in the
        // channels screen's small player.
        stopCurrentPlayback()
    }
    moviesOpen = false
    moviesView.visibility = View.GONE
    // Release the focus trap before focus goes back to the Live TV layout.
    libraryPane.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    // A movie keeps playing underneath when the browser is closed.
    updateMiniPlayerLayout()
    if (!fullScreen) focusBrowse()
}


internal fun MainActivity.showMoviesList() {
    if (moviesDetailsPane.isShown && !movieResumeButton.isEnabled) {
        movieResumeRequest++
        movieResumeButton.isEnabled = true
    }
    moviesDetailsPane.visibility = View.GONE
    moviesListPane.visibility = View.VISIBLE
    // No search on screen — re-render whatever tab (or See All list)
    // was showing, so a favorite toggle or a finished movie is
    // reflected. A search result stays as it is.
    val position = moviesReturnPosition
    moviesReturnPosition = null
    movieSearchFilters.visibility =
        if (isTv && movieResultsAreSearch && movieSearchResults.isNotEmpty()) View.VISIBLE else View.GONE
    if (!movieResultsAreSearch &&
        (!isTv || position == null || moviesTab == MoviesTab.HOME || moviesTab == MoviesTab.MY_LIST)
    ) renderCurrentMoviesTab()
    val card = position?.let { movieResults.findViewWithTag<View>(it.cardTag) }
    val target = card ?: firstFocusableDeep(movieResults)
        ?: moviesTabRow.getChildAt(0)
        ?: movieSearchInput
    OneShotPreDrawListener.add(target) {
        if (!moviesOpen || !moviesListPane.isShown || !target.isShown) return@add
        target.requestFocus()
        if (card != null && position != null) {
            card.movieRailScroll()?.scrollTo(position.scrollX, 0)
            movieResultsScroll.scrollTo(0, position.scrollY)
            card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), true)
        }
    }
}


internal fun MainActivity.showMoviesDetails() {
    moviesListPane.visibility = View.GONE
    moviesDetailsPane.visibility = View.VISIBLE
    movieFavButton.post {
        if (moviesOpen && movieFavButton.isShown) {
            val action = when {
                movieResumeButton.isShown && movieResumeButton.isEnabled -> movieResumeButton
                moviePlayButton.isEnabled -> moviePlayButton
                else -> movieFavButton
            }
            action.requestFocus()
        }
    }
    // v2.0.25: the details pane has content — a wide mini player
    // (empty Home) goes back to 55/45.
    updateMiniPlayerLayout()
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
    moviesSeeAll = null
    moviesReturnPosition = null
    movieSearchResults = emptyList()
    movieSearchFilter = MovieSearchFilter.ALL
    movieSearchFilters.visibility = View.GONE
    val request = ++movieSearchRequest
    // Hide the keyboard so the results are visible on phones.
    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(movieSearchInput.windowToken, 0)
    movieProgress.visibility = View.VISIBLE
    movieStatus.text = "Search ho raha hai…"
    movieResults.removeAllViews()
    // The focused view (a quick chip, the search field) was just
    // removed with the results — without a landing the D-pad pointer
    // is lost until results arrive, and forever on an empty search.
    // Park focus on the search field: it is always there, and DOWN
    // from it reaches the tabs. showMovieResults moves focus to the
    // first row when results arrive.
    movieSearchInput.requestFocus()
    imports.execute {
        try {
            val results = movieBox.search(query)
            runOnUiThread {
                if (isDestroyed || !movieResultsAreSearch || request != movieSearchRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                if (results.isEmpty()) {
                    movieStatus.text = "Koi movie nahi mili — naam badal kar dobara try karein."
                } else {
                    movieSearchResults = results
                    if (isTv) {
                        movieSearchFilters.visibility = View.VISIBLE
                        markChipByTag(movieSearchFilters, movieSearchFilter)
                    }
                    showMovieResults()
                }
                // v2.0.25: search results are content — a wide mini
                // player (empty Home) goes back to 55/45.
                updateMiniPlayerLayout()
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || !movieResultsAreSearch || request != movieSearchRequest) return@runOnUiThread
                movieProgress.visibility = View.GONE
                movieStatus.text = "Search nahi ho saka: ${error.message}"
            }
        }
    }
}


private fun MainActivity.showMovieResults(moveFocus: Boolean = true) {
    val movies = when (movieSearchFilter) {
        MovieSearchFilter.ALL -> movieSearchResults
        MovieSearchFilter.MOVIES -> movieSearchResults.filterNot { it.isSeries }
        MovieSearchFilter.SHOWS -> movieSearchResults.filter { it.isSeries }
    }
    movieStatus.text = if (movies.isEmpty()) "Is filter mein koi title nahi mila." else "${movies.size} titles mile."
    movieResults.removeAllViews()
    val rows = mutableListOf<View>()
    for ((index, movie) in movies.withIndex()) {
        val row = movieRow(movie, backgroundLoad = index >= 6)
        rows += row
        movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    linkMovieRowActions(rows)
    // Back from the first row reaches the navigation (geometry alone
    // is unreliable across the scroller boundary).
    setFirstContentBackLink(rows.firstOrNull())
    linkMoviesTabFocus()
    if (isTv) {
        for (i in 0 until movieSearchFilters.childCount) {
            val control = movieSearchFilters.getChildAt(i)
            control.nextFocusDownId = rows.firstOrNull()?.id ?: View.NO_ID
            control.nextFocusUpId = movieSearchInput.id
        }
        val selectedFilter = movieSearchFilters.findViewWithTag<View>(movieSearchFilter)
        rows.firstOrNull()?.nextFocusUpId = selectedFilter?.id ?: View.NO_ID
    }
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
    if (moveFocus && moviesListPane.isShown) rows.firstOrNull()?.requestFocus()
}

/** Links each movie row's action button (♥ fav / ✕ remove) up and down
 * to the neighbouring ROWS — without this, D-pad focus falls down the
 * action column and the rows become unreachable. LEFT/RIGHT between a
 * row and its own action is wired in movieRow/continueRow. */
internal fun MainActivity.linkMovieRowActions(rows: List<View>) {
    for (i in rows.indices) {
        val row = rows[i] as? LinearLayout ?: continue
        // LEFT from any row reaches the sidebar navigation (v2.0.28):
        // geometry alone is unreliable across the scroller boundary,
        // so rows past the first had no way back to the tabs.
        if (isTv) row.nextFocusLeftId = firstMoviesTabChipId()
        val action = (0 until row.childCount).mapNotNull { row.getChildAt(it) }
            .firstOrNull { it.tag == "rowAction" } ?: continue
        if (i > 0) action.nextFocusUpId = rows[i - 1].id
        if (i < rows.size - 1) action.nextFocusDownId = rows[i + 1].id
    }
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
        tag = "continue:${entry.subjectId}"
        setOnClickListener {
            rememberMovieBrowsePosition(this)
            resumeMovie(entry)
        }
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
    val removeButton = iconButton(R.drawable.ic_close) {
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


/** 2:3 poster tile with a letter tile behind it while the image loads.
 * background: off-screen tiles (rows/rails below the fold) load on the
 * prefetch pool so they never queue behind the visible posters. */
internal fun MainActivity.posterTile(posterUrl: String, title: String, backgroundLoad: Boolean = false): View {
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
        LogoLoader.load(this, posterUrl, posterView, background = backgroundLoad)
    }
    return tileView
}


/** Small square icon button (favorite heart, remove X) for movie rows. */
internal fun MainActivity.iconButton(iconRes: Int, tint: Int = muted, action: (ImageButton) -> Unit) =
    ImageButton(this).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        imageTintList = ColorStateList.valueOf(tint)
        minimumWidth = dp(44)
        minimumHeight = dp(44)
        setPadding(dp(10), dp(10), dp(10), dp(10))
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
    movieFavButton.text = "Favorite"
    movieFavButton.setCompoundDrawablesWithIntrinsicBounds(
        if (fav) R.drawable.ic_favorite else R.drawable.ic_favorite_outline, 0, 0, 0,
    )
    movieFavButton.compoundDrawableTintList =
        ColorStateList.valueOf(if (fav) liveRed else textColor)
}


/** Glass movie card: 2:3 poster (letter tile behind it while it loads),
 * bold title + year, and a cyan HINDI badge for Hindi-dubbed titles. */
internal fun MainActivity.movieRow(movie: MovieBox.Movie, backgroundLoad: Boolean = false): View {
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
        tag = "movie:${movie.subjectId}"
        setOnClickListener {
            rememberMovieBrowsePosition(this)
            openMovieDetails(movie)
        }
    }
    val posterWidth = dp(if (isTv) 60 else 56)
    val posterHeight = dp(if (isTv) 90 else 84)
    row.addView(
        posterTile(movie.posterUrl, movie.title, backgroundLoad),
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
    if (movie.year.isNotEmpty() || isTv && movieResultsAreSearch) {
        texts.addView(TextView(this).apply {
            text = if (isTv && movieResultsAreSearch) {
                listOf(movie.year, if (movie.isSeries) "TV Show" else "Movie")
                    .filter { it.isNotBlank() }.joinToString(" · ")
            } else movie.year
            textSize = if (isTv) 15f else 13f
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
    if (movie.isSeries && !(isTv && movieResultsAreSearch)) {
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
    val heart = iconButton(
        if (movieStore.isFavorite(movie)) R.drawable.ic_favorite else R.drawable.ic_favorite_outline,
        if (movieStore.isFavorite(movie)) liveRed else muted,
    ) { btn ->
        val nowFav = movieStore.toggleFavorite(movie)
        btn.setImageResource(if (nowFav) R.drawable.ic_favorite else R.drawable.ic_favorite_outline)
        btn.imageTintList = ColorStateList.valueOf(if (nowFav) liveRed else muted)
        toast(if (nowFav) "Fav Movies mein add ho gayi." else "Fav Movies se nikal di.")
        if (moviesOpen && !movieResultsAreSearch) renderCurrentMoviesTab()
    }
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
