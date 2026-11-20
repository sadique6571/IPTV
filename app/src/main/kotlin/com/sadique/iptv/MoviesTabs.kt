package com.sadique.iptv

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.io.IOException

/**
 * Movies tabs (v2.0.35): the Netflix-style top navigation — Home /
 * Movies / TV Shows / Genres / My List (+ Downloads on mobile only).
 * The tabs bar is built once in buildMoviesView; selectMoviesTab
 * switches the content, renderCurrentMoviesTab re-renders whatever is
 * showing (used after feed arrival, favorite toggles and Back from
 * details, without changing the tab).
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

/** Builds the tab chip row (once, in buildMoviesView). Downloads is
 * mobile-only — the user did not approve TV downloads. */
internal fun MainActivity.buildMoviesTabsRow(): LinearLayout {
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val tabs = MoviesTab.values().filter { it != MoviesTab.DOWNLOADS || !isTv }
    for (tab in tabs) {
        val chipView = chip(tab.label) { selectMoviesTab(tab) }
        chipView.tag = tab
        chipView.id = View.generateViewId()
        chipView.isSelected = tab == moviesTab
        refreshFilterText(chipView)
        chipView.nextFocusUpId = movieHomeChip.id
        row.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
    }
    return row
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
    stopMoviesHero()
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
    for (movie in movies) {
        val row = movieRow(movie)
        rows += row
        movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })
    }
    linkMovieRowActions(rows)
    rows.firstOrNull()?.nextFocusUpId = firstMoviesTabChipId()
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
        chipView.nextFocusUpId = firstMoviesTabChipId()
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
                for (movie in movies) {
                    val row = movieRow(movie)
                    rows += row
                    movieGenreResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    })
                }
                linkMovieRowActions(rows)
                // UP/DOWN across the genre-chips scroller is wired
                // explicitly — geometry alone is unreliable there.
                val firstRowId = rows.firstOrNull()?.id ?: View.NO_ID
                for (i in 0 until movieGenreRow.childCount) {
                    movieGenreRow.getChildAt(i)?.nextFocusDownId = firstRowId
                }
                val selectedChip = (0 until movieGenreRow.childCount)
                    .mapNotNull { movieGenreRow.getChildAt(it) as? Button }
                    .firstOrNull { it.isSelected }
                rows.firstOrNull()?.nextFocusUpId = selectedChip?.id ?: View.NO_ID
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

/** Id of the first tab chip — the UP target of the first content row
 * and the DOWN target of the quick chips. */
internal fun MainActivity.firstMoviesTabChipId(): Int =
    moviesTabRow.getChildAt(0)?.id ?: View.NO_ID

/** Links the tab chips DOWN to the first focusable content view and
 * that view UP to the first tab chip, so D-pad navigation never gets
 * stuck between the tabs and the content. Re-run on every render,
 * since the first content view changes. */
internal fun MainActivity.linkMoviesTabFocus() {
    val first = firstFocusableDeep(movieResults)
    val firstId = first?.id ?: View.NO_ID
    for (i in 0 until moviesTabRow.childCount) {
        moviesTabRow.getChildAt(i)?.nextFocusDownId = firstId
    }
    if (first != null) first.nextFocusUpId = firstMoviesTabChipId()
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
