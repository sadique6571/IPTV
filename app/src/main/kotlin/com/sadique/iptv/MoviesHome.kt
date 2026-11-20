package com.sadique.iptv

import android.view.View
import android.widget.LinearLayout

/**
 * Movies Home tab (v2.0.35): hero banner + Continue Watching +
 * Trending / Popular rails. The feed loads once per app run
 * (MovieFeed.kt) and re-renders this tab when it arrives.
 */
internal fun MainActivity.renderMoviesHome() {
    movieResults.removeAllViews()
    val continuing = movieStore.continueWatching()
    val feedPending = !movieFeedLoaded
    if (feedPending) loadMovieFeed()
    val hasFeed = movieFeedNew.isNotEmpty() || movieFeedRecommended.isNotEmpty()
    movieStatus.text = when {
        continuing.isNotEmpty() && hasFeed -> "Continue Watching · Trending · Popular"
        continuing.isNotEmpty() -> "Continue Watching"
        hasFeed -> "Trending · Popular"
        feedPending -> "Nayi movies load ho rahi hain…"
        else -> "Abhi koi movie nahi mili — search karke movie dekhein."
    }
    // Hero: the top Recommended movies (poster-based — MovieBox has no
    // backdrops). No hero while the feed is still loading.
    if (movieFeedRecommended.isNotEmpty()) {
        movieResults.addView(
            buildMoviesHero(movieFeedRecommended.take(5)),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) },
        )
    }
    // Continue Watching keeps its rich rows (progress bar + remaining
    // time) — a poster rail would lose that.
    if (continuing.isNotEmpty()) {
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
    if (movieFeedRecommended.isNotEmpty()) {
        movieResults.addView(
            buildMoviesRail("⭐ Trending", movieFeedRecommended),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    if (movieFeedNew.isNotEmpty()) {
        movieResults.addView(
            buildMoviesRail("🆕 Popular", movieFeedNew),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}
