package com.sadique.iptv

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Horizontal poster rails (v2.0.35) + the See All full-list screen.
 * D-pad LEFT/RIGHT moves between cards on TV (the scroller follows the
 * focused card); touch scrolls on phones. A See All list sits on top
 * of the current tab — Back returns to the tab (see onBackPressed).
 */

/** A See All list open over the current tab. */
internal data class MoviesSeeAll(val title: String, val movies: List<MovieBox.Movie>)

/** One rail: section header (title + See All) + a horizontal row of
 * poster cards. */
internal fun MainActivity.buildMoviesRail(title: String, movies: List<MovieBox.Movie>): View {
    val rail = column()
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(sectionLabel(title), LinearLayout.LayoutParams(0, -2, 1f))
    val seeAll = button("See All", R.drawable.ic_chevron_right, iconEnd = true) { showMoviesSeeAll(title, movies) }
    header.addView(seeAll)
    rail.addView(header)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    // Only the cards visible at first load eagerly — the rest of the
    // rail loads on the prefetch pool, so the visible posters never
    // queue behind off-screen ones.
    val eagerCount = if (isTv) 8 else 4
    for ((index, movie) in movies.withIndex()) {
        row.addView(posterCard(movie, backgroundLoad = index >= eagerCount), LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(12)
        })
    }
    // End stops: LEFT past the first card / RIGHT past the last must
    // not wander off the rail.
    row.getChildAt(0)?.nextFocusLeftId = View.NO_ID
    if (row.childCount > 0) {
        row.getChildAt(row.childCount - 1)?.nextFocusRightId = View.NO_ID
    }
    val scroller = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }
    rail.addView(scroller)
    return rail
}

/** Top 10 rail (Netflix-style): the same poster cards, each preceded
 * by a big rank number. */
internal fun MainActivity.buildTop10Rail(title: String, movies: List<MovieBox.Movie>): View {
    val rail = column()
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    header.addView(sectionLabel(title), LinearLayout.LayoutParams(0, -2, 1f))
    val top10 = movies.take(10)
    val seeAll = button("See All", R.drawable.ic_chevron_right, iconEnd = true) { showMoviesSeeAll(title, top10) }
    header.addView(seeAll)
    rail.addView(header)
    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val eagerCount = if (isTv) 8 else 4
    for ((index, movie) in top10.withIndex()) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        item.addView(TextView(this).apply {
            text = "${index + 1}"
            textSize = if (isTv) 56f else 44f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        })
        item.addView(posterCard(movie, backgroundLoad = index >= eagerCount))
        row.addView(item, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(16)
        })
    }
    // End stops on the cards themselves (the rank numbers are not
    // focusable — the wrappers are).
    (row.getChildAt(0) as? LinearLayout)?.getChildAt(1)?.nextFocusLeftId = View.NO_ID
    if (row.childCount > 0) {
        (row.getChildAt(row.childCount - 1) as? LinearLayout)?.getChildAt(1)?.nextFocusRightId = View.NO_ID
    }
    val scroller = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }
    rail.addView(scroller)
    return rail
}

/** Poster card: 2:3 poster + title + year (and a HINDI note for
 * Hindi-dubbed titles). Tapping opens the details pane. */
internal fun MainActivity.posterCard(movie: MovieBox.Movie, backgroundLoad: Boolean = false): View {
    val cardWidth = dp(if (isTv) 150 else 120)
    val posterHeight = cardWidth * 3 / 2
    val card = column().apply {
        contentDescription = listOf(movie.title, movie.year).filter { it.isNotBlank() }.joinToString(", ")
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rowBackground(false)
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { openMovieDetails(movie) }
    }
    card.addView(
        posterTile(movie.posterUrl, movie.title, backgroundLoad),
        LinearLayout.LayoutParams(cardWidth, posterHeight),
    )
    card.addView(TextView(this).apply {
        text = movie.title
        textSize = if (isTv) 14f else 13f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }, LinearLayout.LayoutParams(cardWidth, -2).apply { topMargin = dp(6) })
    val yearText = movie.year + if (movie.isHindi) " · HINDI" else ""
    if (yearText.isNotEmpty()) {
        card.addView(TextView(this).apply {
            text = yearText
            textSize = 12f
            setTextColor(muted2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(cardWidth, -2))
    }
    return card
}

/** Opens a rail's full list (vertical rows, like search results). */
internal fun MainActivity.showMoviesSeeAll(title: String, movies: List<MovieBox.Movie>) {
    moviesSeeAll = MoviesSeeAll(title, movies)
    renderCurrentMoviesTab()
}

internal fun MainActivity.renderMoviesSeeAll(seeAll: MoviesSeeAll) {
    movieResults.removeAllViews()
    movieStatus.text = "${seeAll.title} — ${seeAll.movies.size} movies"
    val rows = mutableListOf<View>()
    for ((index, movie) in seeAll.movies.withIndex()) {
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
