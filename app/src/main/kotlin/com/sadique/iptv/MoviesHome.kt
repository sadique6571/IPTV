package com.sadique.iptv

import android.view.View
import android.widget.LinearLayout

/**
 * Movies Home tab (v2.0.35): hero banner + Continue Watching +
 * Trending / New Release rails. The feed loads once per app run
 * (MovieFeed.kt) and re-renders this tab when it arrives.
 */
internal fun MainActivity.renderMoviesHome() {
    movieResults.removeAllViews()
    val continuing = movieStore.continueWatching()
    val feedPending = !movieFeedLoaded
    if (feedPending) loadMovieFeed()
    val hasFeed = movieFeedNew.isNotEmpty() || movieFeedRecommended.isNotEmpty()
    movieStatus.text = when {
        continuing.isNotEmpty() && hasFeed -> "Continue Watching · Top 10 · Trending · New Release"
        continuing.isNotEmpty() -> "Continue Watching"
        hasFeed -> "Top 10 · Trending · New Release"
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
    // Netflix-style rows: a numbered Top 10 from Trending, Trending Now,
    // and Top Picks for You (daily/top curated picks).
    if (movieFeedTrending.isNotEmpty()) {
        movieResults.addView(
            buildTop10Rail("🔥 Top 10", movieFeedTrending),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
        movieResults.addView(
            buildMoviesRail("Trending Now", movieFeedTrending),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    if (movieFeedPicks.isNotEmpty()) {
        movieResults.addView(
            buildMoviesRail("Top Picks for You", topPicksForYou()),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    if (movieFeedNew.isNotEmpty()) {
        movieResults.addView(
            buildMoviesRail("New Release", movieFeedNew),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) },
        )
    }
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}

/** "Top Picks for You" leans Hindi when the user's own history leans
 * Hindi (Continue Watching + favorites matched against the loaded
 * feed — no extra network). Otherwise the picks stay in server order. */
private fun MainActivity.topPicksForYou(): List<MovieBox.Movie> {
    val picks = movieFeedPicks
    if (picks.isEmpty() || !userPrefersHindi()) return picks
    return picks.sortedWith(compareByDescending { it.isHindi })
}

private fun MainActivity.userPrefersHindi(): Boolean {
    val pool = movieFeedNew + movieFeedRecommended + movieFeedSeries
    if (pool.isEmpty()) return false
    val watchedIds = movieStore.continueWatching().map { it.subjectId }.toSet() +
        movieStore.favoritesList().map { it.subjectId }.toSet()
    val matched = pool.filter { it.subjectId in watchedIds }
    if (matched.isEmpty()) return false
    return matched.count { it.isHindi } * 2 > matched.size
}
