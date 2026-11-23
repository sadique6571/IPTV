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
        val trend = movieBox.trending()
        val picks = movieBox.curatedPicks()
        val series = movieBox.seriesFeed()
        runOnUiThread {
            if (isDestroyed || request != movieFeedRequest) return@runOnUiThread
            movieFeedLoading = false
            movieFeedLoaded = true
            movieFeedTrending = trend
            movieFeedPicks = picks
            movieFeedRecommended = (trend + picks).distinctBy { it.subjectId }
            // New Release must genuinely differ from Trending / Top Picks
            // (v2.0.37): drop any title already in those rails, newest
            // first by release year.
            val pickIds = movieFeedRecommended.map { it.subjectId }.toSet()
            movieFeedNew = fresh.filter { it.subjectId !in pickIds }
                .sortedByDescending { it.year.toIntOrNull() ?: 0 }
            movieFeedSeries = series
            if (moviesOpen && !movieResultsAreSearch) renderCurrentMoviesTab()
        }
    }
}
