package com.sadique.iptv

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.content.res.ColorStateList

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
    movieStatus.text = if (continuing.isEmpty() && favorites.isEmpty() && !movieFeedLoaded) {
        "Search movies & series or browse the catalogue."
    } else ""
    movieStatus.visibility = if (movieStatus.text.isNotEmpty()) View.VISIBLE else View.GONE
    if (continuing.isNotEmpty()) {
        movieResults.addView(sectionLabel("Continue Watching"))
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (entry in continuing) {
            val cardWidth = dp(if (isTv) 118 else 102)
            val card = column().apply {
                id = View.generateViewId()
                tag = "continue:${entry.subjectId}"
                isFocusable = true
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = rowBackground(false)
                setOnFocusChangeListener { _, focused -> animateFocusScale(this, focused) }
                setOnClickListener {
                    rememberMovieBrowsePosition(this)
                    resumeMovie(entry)
                }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(this@renderMoviesHome)
                        .setMessage("Remove from Continue Watching?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            movieStore.removeContinue(entry.subjectId)
                            renderMoviesHome()
                        }.show()
                    true
                }
                addView(posterTile(entry.posterUrl, entry.title), LinearLayout.LayoutParams(cardWidth, cardWidth * 3 / 2))
                addView(label(entry.title, 14, textColor).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(cardWidth, -2))
                addView(ProgressBar(this@renderMoviesHome, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = (entry.progress * 100).toInt()
                    progressTintList = ColorStateList.valueOf(cyan)
                }, LinearLayout.LayoutParams(cardWidth, dp(5)))
                val remaining = when {
                    entry.durationMs > 0 -> "${formatTime(entry.remainingMs)} left"
                    entry.isSeries && entry.positionMs == 0L -> "Up next"
                    else -> formatTime(entry.positionMs)
                }
                addView(label(listOf(entry.episodeLabel, remaining).filter { it.isNotEmpty() }.joinToString(" · "), 12, muted),
                    LinearLayout.LayoutParams(cardWidth, -2))
            }
            val item = if (isTv && entry.isSeries) column().apply {
                addView(card)
                val episodes = button("Episodes") {
                    rememberMovieBrowsePosition(card)
                    openMovieDetails(MovieBox.Movie(subjectId = entry.subjectId, title = entry.title,
                        posterUrl = entry.posterUrl, isSeries = true))
                }.apply {
                    id = View.generateViewId()
                    contentDescription = "Episodes of ${entry.title}"
                    nextFocusUpId = card.id
                }
                card.nextFocusDownId = episodes.id
                addView(episodes, LinearLayout.LayoutParams(-1, dp(44)))
            } else card
            cards.addView(item, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(8)
            })
        }
        movieResults.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cards)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    for ((title, titles) in listOf("Top Picks for You" to movieFeedRecommended, "New Releases" to movieFeedNew, "Favorites" to favorites)) {
        if (titles.isNotEmpty()) {
            movieResults.addView(buildMoviesRail(title, titles), LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
    }
    if (!movieFeedLoaded) loadMovieFeed()
    linkMoviesTabFocus()
    movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
}
