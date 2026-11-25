package com.sadique.iptv

import android.view.View
import android.widget.LinearLayout

/**
 * Movies Home tab: Continue Watching + Favorites. The hero banner,
 * Top 10, Trending Now, Top Picks and New Release rails were removed
 * (the user watches via search) — only personal sections remain.
 * The feed is loaded by the feed-backed tabs (MoviesTabs.kt), not
 * from Home.
 */
internal fun MainActivity.renderMoviesHome() {
    movieResults.removeAllViews()
    val continuing = movieStore.continueWatching()
    val favorites = movieStore.favoritesList()
    // v2.0.24: while the mini player is on, Continue Watching stays
    // hidden — the side-by-side panel is too narrow for its rows and
    // they clip. It comes back once the player is stopped (the list
    // re-renders then, with isMiniPlayer() false).
    val showContinuing = continuing.isNotEmpty() && !isMiniPlayer()
    movieStatus.text = when {
        showContinuing && favorites.isNotEmpty() -> "Continue Watching · Favorites"
        showContinuing -> "Continue Watching"
        favorites.isNotEmpty() -> "Favorites"
        else -> "Abhi koi movie nahi mili — search karke movie dekhein."
    }
    // Continue Watching keeps its rich rows (progress bar + remaining
    // time) — a poster rail would lose that.
    if (showContinuing) {
        movieResults.addView(sectionLabel("▶ Continue Watching"))
        val rows = mutableListOf<View>()
        for (entry in continuing) {
            val row = continueRow(entry)
            rows += row
            movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        linkMovieRowActions(rows)
    }
    // Favorites: a poster rail of the user's own list (My List).
    if (favorites.isNotEmpty()) {
        movieResults.addView(
            buildMoviesRail("♥ Favorites", favorites),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}
