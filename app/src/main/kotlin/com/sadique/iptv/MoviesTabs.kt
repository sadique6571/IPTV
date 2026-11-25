package com.sadique.iptv

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.io.IOException

/**
 * Movies tabs (v2.0.35): the Netflix-style navigation — Home /
 * Movies / TV Shows / Genres / My List (+ Downloads on mobile only).
 * Mobile shows them as a top chip row, TV as the sidebar column
 * (v2.0.37, MoviesSidebar.kt) — both are the same moviesTabRow, built
 * once in buildMoviesView; selectMoviesTab switches the content,
 * renderCurrentMoviesTab re-renders whatever is showing (used after
 * feed arrival, favorite toggles and Back from details, without
 * changing the tab).
 */
internal enum class MoviesTab(val label: String) {
    HOME("Home"),
    MOVIES("Movies"),
    SHOWS("TV Shows"),
    GENRES("Genres"),
    MY_LIST("My List"),
    DOWNLOADS("Downloads"),
}

/** Genres for the Genres tab (MovieBox catalogue filter values). */
internal val MOVIE_GENRES = listOf(
    "Action", "Adventure", "Animation", "Comedy", "Crime", "Documentary",
    "Drama", "Family", "Fantasy", "Horror", "Mystery", "Romance",
    "Sci-Fi", "Thriller", "War",
)

/** Builds the tab chip row (mobile only, once, in buildMoviesView).
 * TV uses the sidebar column instead (MoviesSidebar.kt) — both are
 * the same moviesTabRow, so tab switching works identically. */
internal fun MainActivity.buildMoviesTabsRow(): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (tab in MoviesTab.values()) {
        val chipView = chip(tab.label) { selectMoviesTab(tab) }
        chipView.tag = tab
        chipView.id = View.generateViewId()
        chipView.isSelected = tab == moviesTab
        refreshFilterText(chipView)
        chipView.nextFocusUpId = movieSearchInput.id
        row.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    return row
}

/** Points a first content view back at the navigation: UP to the
 * tabs on mobile (tabs sit above the content), LEFT to the sidebar
 * on TV (the sidebar sits left of the content). */
internal fun MainActivity.setFirstContentBackLink(view: View?) {
    if (view == null) return
    val target = firstMoviesTabChipId()
    if (isTv) view.nextFocusLeftId = target else view.nextFocusUpId = target
}

/** Switches the Movies tab and renders its content. Clears any search
 * or See All list — a tab is a fresh start. */
internal fun MainActivity.selectMoviesTab(tab: MoviesTab) {
    moviesTab = tab
    movieResultsAreSearch = false
    moviesSeeAll = null
    markChipByTag(moviesTabRow, tab)
    renderCurrentMoviesTab()
}

/** Re-renders whatever the Movies section is currently showing (the
 * current tab, or the See All list on top of it). Never changes the
 * tab — that is selectMoviesTab's job. */
internal fun MainActivity.renderCurrentMoviesTab() {
    movieProgress.visibility = View.GONE
    val seeAll = moviesSeeAll
    if (seeAll != null) {
        renderMoviesSeeAll(seeAll)
        return
    }
    when (moviesTab) {
        MoviesTab.HOME -> renderMoviesHome()
        MoviesTab.MOVIES -> renderMoviesFeedTab(
            "Movies", movieFeedNew, "Abhi koi nayi movie nahi mili.", true,
        )
        MoviesTab.SHOWS -> renderMoviesFeedTab(
            "TV Shows", movieFeedSeries, "Abhi koi series nahi mili.", true,
        )
        MoviesTab.GENRES -> renderMoviesGenres()
        MoviesTab.MY_LIST -> renderMoviesFeedTab(
            "My List", movieStore.favoritesList(),
            "Abhi koi Fav Movie nahi hai — movie khol kar ♥ dabayein.", false,
        )
        MoviesTab.DOWNLOADS -> renderDownloads()
    }
    // v2.0.25: the mini player goes wide on an empty Home and
    // restores 55/45 when content opens — re-check the split after
    // every content render.
    updateMiniPlayerLayout()
}

/** A tab that is one vertical list of movie rows (Movies / TV Shows /
 * My List). Feed-backed tabs kick off the one-time feed load when it
 * has not arrived yet; the feed re-renders this tab on arrival. */
internal fun MainActivity.renderMoviesFeedTab(
    title: String,
    movies: List<MovieBox.Movie>,
    emptyText: String,
    feedBacked: Boolean,
) {
    if (feedBacked && !movieFeedLoaded) loadMovieFeed()
    movieResults.removeAllViews()
    val loading = feedBacked && !movieFeedLoaded
    movieStatus.text = when {
        loading -> "$title load ho rahi hain…"
        movies.isEmpty() -> emptyText
        else -> "$title — ${movies.size} movies"
    }
    if (movies.isEmpty()) {
        linkMoviesTabFocus()
        return
    }
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
    setFirstContentBackLink(rows.firstOrNull())
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}

/** Genres tab: genre chips + the picked genre's movies (loaded from
 * the MovieBox catalogue filter, newest first). */
internal fun MainActivity.renderMoviesGenres() {
    movieResults.removeAllViews()
    movieStatus.text = "Genre chunein — us genre ki movies neeche aayengi."
    movieGenreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (genre in MOVIE_GENRES) {
        val chipView = chip(genre) {
            if (moviesGenre != genre) {
                moviesGenre = genre
                markChipByTag(movieGenreRow, genre)
                loadMoviesGenre(genre)
            }
        }
        chipView.tag = genre
        chipView.id = View.generateViewId()
        chipView.isSelected = genre == moviesGenre
        refreshFilterText(chipView)
        if (isTv) chipView.nextFocusLeftId = firstMoviesTabChipId()
        else chipView.nextFocusUpId = firstMoviesTabChipId()
        movieGenreRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    val genreScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(movieGenreRow)
    }
    movieResults.addView(genreScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    })
    // Quick searches (moved here from the Movies home in v2.0.37 —
    // the home's duplicate Home chip + shortcut row are gone): one
    // tap runs a real MovieBox search for a popular term.
    movieGenreQuickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    for (term in listOf("Marvel", "DC", "Bollywood", "Punjabi", "Action", "Comedy")) {
        val quickChip = chip(term) { quickSearchMovie(term) }
        quickChip.id = View.generateViewId()
        if (isTv) quickChip.nextFocusLeftId = firstMoviesTabChipId()
        movieGenreQuickRow.addView(quickChip, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    val quickScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(movieGenreQuickRow)
    }
    movieResults.addView(quickScroll, LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    })
    // Wire the vertical chain NOW, not only after the genre results
    // load (v2.0.28): the quick chips had no ids, so the genre
    // chips' DOWN link was NO_ID and D-pad DOWN relied on geometry
    // across the scrollers — the pointer went nowhere. Genre chips
    // go DOWN to the first quick chip; quick chips go UP to the
    // selected genre chip.
    val firstQuickId = movieGenreQuickRow.getChildAt(0)?.id ?: View.NO_ID
    val selectedGenreChip = (0 until movieGenreRow.childCount)
        .mapNotNull { movieGenreRow.getChildAt(it) as? Button }
        .firstOrNull { it.isSelected }
    for (i in 0 until movieGenreRow.childCount) {
        movieGenreRow.getChildAt(i)?.nextFocusDownId = firstQuickId
    }
    for (i in 0 until movieGenreQuickRow.childCount) {
        movieGenreQuickRow.getChildAt(i)?.nextFocusUpId =
            selectedGenreChip?.id ?: View.NO_ID
    }
    movieGenreResults = column()
    movieResults.addView(movieGenreResults, LinearLayout.LayoutParams(-1, -2))
    loadMoviesGenre(moviesGenre)
    linkMoviesTabFocus()
}

/** Loads one genre's movies; stale results (genre changed, tab left)
 * are dropped by the request counter. */
internal fun MainActivity.loadMoviesGenre(genre: String) {
    val request = ++moviesGenreRequest
    movieGenreResults.removeAllViews()
    movieProgress.visibility = View.VISIBLE
    imports.execute {
        try {
            val movies = movieBox.moviesByGenre(genre)
            runOnUiThread {
                if (isDestroyed || request != moviesGenreRequest) return@runOnUiThread
                if (moviesTab != MoviesTab.GENRES || moviesSeeAll != null) return@runOnUiThread
                movieProgress.visibility = View.GONE
                if (movies.isEmpty()) {
                    movieStatus.text = "$genre mein koi movie nahi mili."
                    return@runOnUiThread
                }
                movieStatus.text = "$genre — ${movies.size} movies"
                val rows = mutableListOf<View>()
                for ((index, movie) in movies.withIndex()) {
                    val row = movieRow(movie, backgroundLoad = index >= 6)
                    rows += row
                    movieGenreResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    })
                }
                linkMovieRowActions(rows)
                // UP/DOWN across the genre + quick chips is wired
                // explicitly — geometry alone is unreliable across
                // the scrollers. Chain: genre chips -> quick chips
                // -> first result row.
                val firstQuickId = movieGenreQuickRow.getChildAt(0)?.id ?: View.NO_ID
                val firstRowId = rows.firstOrNull()?.id ?: View.NO_ID
                for (i in 0 until movieGenreRow.childCount) {
                    movieGenreRow.getChildAt(i)?.nextFocusDownId = firstQuickId
                }
                val selectedChip = (0 until movieGenreRow.childCount)
                    .mapNotNull { movieGenreRow.getChildAt(it) as? Button }
                    .firstOrNull { it.isSelected }
                for (i in 0 until movieGenreQuickRow.childCount) {
                    val quickChip = movieGenreQuickRow.getChildAt(i)
                    quickChip?.nextFocusUpId = selectedChip?.id ?: View.NO_ID
                    quickChip?.nextFocusDownId = firstRowId
                }
                rows.firstOrNull()?.nextFocusUpId = firstQuickId
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (isDestroyed || request != moviesGenreRequest) return@runOnUiThread
                if (moviesTab != MoviesTab.GENRES || moviesSeeAll != null) return@runOnUiThread
                movieProgress.visibility = View.GONE
                movieStatus.text = "$genre load nahi ho saka: ${error.message}"
            }
        }
    }
}

/** Id of the first tab button (Home) — the back-link target of the
 * first content view (UP on mobile, LEFT on TV) and the DOWN target
 * of the search field. */
internal fun MainActivity.firstMoviesTabChipId(): Int =
    moviesTabRow.getChildAt(0)?.id ?: View.NO_ID

/** Links the tab buttons to the first focusable content view and
 * that view back to the first tab button, so D-pad navigation never
 * gets stuck between the navigation and the content. Re-run on every
 * render, since the first content view changes. TV: the sidebar sits
 * LEFT of the content, so the link is RIGHT/LEFT; mobile: the tabs
 * sit ABOVE the content, so it is DOWN/UP. */
internal fun MainActivity.linkMoviesTabFocus() {
    val first = firstFocusableDeep(movieResults)
    val firstId = first?.id ?: View.NO_ID
    for (i in 0 until moviesTabRow.childCount) {
        val child = moviesTabRow.getChildAt(i) ?: continue
        if (isTv) child.nextFocusRightId = firstId else child.nextFocusDownId = firstId
    }
    if (first != null) {
        if (isTv) first.nextFocusLeftId = firstMoviesTabChipId()
        else first.nextFocusUpId = firstMoviesTabChipId()
    }
}

/** Depth-first search for the first focusable view inside a group
 * (hero buttons and rail cards are nested, not direct children). */
internal fun MainActivity.firstFocusableDeep(group: ViewGroup): View? {
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i) ?: continue
        if (child.isFocusable) return child
        if (child is ViewGroup) {
            firstFocusableDeep(child)?.let { return it }
        }
    }
    return null
}
