package com.sadique.iptv

/**
 * Movies home feed loading (v2.0.27): the New Movies, Recommended and
 * Series sections load once per app run, in the background, and the home
 * screen re-renders when they arrive. A failed load leaves the sections
 * out — the feed functions already fall back internally, and home never
 * breaks on a feed failure. Nothing is re-fetched while the app runs.
 */
internal fun MainActivity.loadMovieFeed() {
    if (movieFeedLoaded || movieFeedLoading) return
    movieFeedLoading = true
    val request = ++movieFeedRequest
    imports.execute {
        val fresh = movieBox.newMovies()
        val picks = movieBox.recommended()
        val series = movieBox.seriesFeed()
        runOnUiThread {
            if (isDestroyed || request != movieFeedRequest) return@runOnUiThread
            movieFeedLoading = false
            movieFeedLoaded = true
            // New Release must genuinely differ from Trending
            // (v2.0.37): drop any title already in the Trending rail,
            // newest first by release year.
            val trendingIds = picks.map { it.subjectId }.toSet()
            movieFeedNew = fresh.filter { it.subjectId !in trendingIds }
                .sortedByDescending { it.year.toIntOrNull() ?: 0 }
            movieFeedRecommended = picks
            movieFeedSeries = series
            if (moviesOpen && !movieResultsAreSearch) renderCurrentMoviesTab()
        }
    }
}
