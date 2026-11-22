package com.sadique.iptv

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException

/**
 * Hero banner (v2.0.35): the featured movie from the Recommended feed.
 * Poster-based — MovieBox serves posters only, no wide backdrops — so
 * the poster fills the banner (CENTER_CROP) under a dark gradient and
 * the title + actions sit on top. Auto-rotates through the top items
 * every 8 seconds; D-pad focus lands on Watch Now. Rotation pauses
 * while the app is backgrounded (see rotateMoviesHero) and stops
 * whenever the Home tab is left (stopMoviesHero).
 */

/** Milliseconds between hero rotations. */
internal const val HERO_ROTATE_MS = 8000L

internal fun MainActivity.buildMoviesHero(movies: List<MovieBox.Movie>): View {
    heroMovies = movies
    heroIndex = 0
    val frame = FrameLayout(this).apply {
        // Fixed height via minimumHeight: the caller adds the hero
        // with WRAP_CONTENT, and the MATCH_PARENT poster would measure
        // to nothing without it.
        minimumHeight = dp(if (isTv) 320 else 260)
        background = glassShape(16)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(16).toFloat())
            }
        }
        clipToOutline = true
    }
    val posterView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    heroPosterView = posterView
    frame.addView(posterView, FrameLayout.LayoutParams(-1, -1))
    // Dark gradient so the title stays readable over any poster.
    frame.addView(View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.TRANSPARENT, 0x66000000, 0xe6000000.toInt()),
        )
    }, FrameLayout.LayoutParams(-1, -1))
    val content = column().apply {
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    val titleView = TextView(this).apply {
        textSize = if (isTv) 26f else 22f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    heroTitleView = titleView
    content.addView(titleView)
    val metaView = TextView(this).apply {
        textSize = if (isTv) 15f else 14f
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    heroMetaView = metaView
    content.addView(metaView, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(4)
        bottomMargin = dp(12)
    })
    val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val watchView = heroActionButton("Watch Now", true, R.drawable.ic_play) {
        heroMovies.getOrNull(heroIndex)?.let { openMovieDetails(it) }
    }
    heroWatchView = watchView
    val listView = heroActionButton("My List", false, R.drawable.ic_add) {
        val movie = heroMovies.getOrNull(heroIndex) ?: return@heroActionButton
        val nowFav = movieStore.toggleFavorite(movie)
        updateHeroListButton()
        toast(if (nowFav) "My List mein add ho gayi." else "My List se nikal di.")
    }
    heroListView = listView
    watchView.nextFocusRightId = listView.id
    listView.nextFocusLeftId = watchView.id
    buttons.addView(watchView, LinearLayout.LayoutParams(-2, -2).apply {
        marginEnd = dp(10)
    })
    buttons.addView(listView)
    content.addView(buttons)
    frame.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
    // Rotation dots, top-right (the buttons own the bottom edge).
    val dotsView = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    heroDotsView = dotsView
    frame.addView(
        dotsView,
        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(16)
            marginEnd = dp(16)
        },
    )
    bindMoviesHero()
    scheduleMoviesHero()
    return frame
}

/** Binds the current hero item's title / poster / buttons, then loads
 * its details (genres + duration) in the background — the year shows
 * at once, the rest fills in when it arrives. */
internal fun MainActivity.bindMoviesHero() {
    val movie = heroMovies.getOrNull(heroIndex) ?: return
    heroTitleView?.text = movie.title
    heroMetaView?.text = movie.year.ifEmpty { "Movie" }
    val posterView = heroPosterView
    posterView?.setImageDrawable(null)
    if (posterView != null && movie.posterUrl.isNotEmpty()) {
        LogoLoader.load(this, movie.posterUrl, posterView, 512)
    }
    // Prefetch the next hero poster so the 8s rotation never shows a
    // blank banner while it downloads.
    val next = heroMovies.getOrNull((heroIndex + 1) % heroMovies.size)
    if (next != null && next.posterUrl.isNotEmpty()) {
        LogoLoader.prefetch(this, listOf(next.posterUrl), 512)
    }
    updateHeroListButton()
    rebuildHeroDots()
    val request = ++heroRequest
    imports.execute {
        try {
            val details = movieBox.details(movie.subjectId)
            runOnUiThread {
                if (isDestroyed || request != heroRequest) return@runOnUiThread
                if (heroMovies.getOrNull(heroIndex)?.subjectId != movie.subjectId) {
                    return@runOnUiThread
                }
                val parts = mutableListOf<String>()
                if (movie.year.isNotEmpty()) parts += movie.year
                if (details.durationSec > 0) parts += formatDuration(details.durationSec)
                if (details.rating.isNotEmpty()) parts += "★ ${details.rating}"
                if (details.genres.isNotEmpty()) parts += details.genres.take(3).joinToString(", ")
                heroMetaView?.text = parts.joinToString(" · ")
            }
        } catch (_: IOException) {
            // Meta stays year-only — the hero never breaks on a failed lookup.
        }
    }
}

internal fun MainActivity.updateHeroListButton() {
    val movie = heroMovies.getOrNull(heroIndex) ?: return
    val fav = movieStore.isFavorite(movie)
    heroListView?.text = "My List"
    heroListView?.setCompoundDrawablesWithIntrinsicBounds(
        if (fav) R.drawable.ic_check else R.drawable.ic_add, 0, 0, 0,
    )
}

private fun MainActivity.rebuildHeroDots() {
    val dots = heroDotsView ?: return
    dots.removeAllViews()
    for (i in heroMovies.indices) {
        val active = i == heroIndex
        dots.addView(View(this).apply {
            background = shape(
                if (active) cyan else muted2, if (active) cyan else muted2, 4, 0,
            )
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }
}

/** Advances the hero to the next featured movie. Never reschedules
 * itself off the Home tab, over a search, or while backgrounded — the
 * next render (or onStart) restarts the rotation. */
internal fun MainActivity.rotateMoviesHero() {
    if (!moviesOpen || moviesTab != MoviesTab.HOME || moviesSeeAll != null ||
        movieResultsAreSearch || stoppedForBackground
    ) {
        return
    }
    if (heroMovies.size < 2) return
    heroIndex = (heroIndex + 1) % heroMovies.size
    bindMoviesHero()
    scheduleMoviesHero()
}

internal fun MainActivity.scheduleMoviesHero() {
    mainHandler.removeCallbacks(heroRotateRunnable)
    if (heroMovies.size < 2 || stoppedForBackground) return
    mainHandler.postDelayed(heroRotateRunnable, HERO_ROTATE_MS)
}

/** Stops hero rotation and drops the hero view references (called on
 * every content re-render, on leaving Home, and when the section
 * closes — the hero only exists on the Home tab). */
internal fun MainActivity.stopMoviesHero() {
    mainHandler.removeCallbacks(heroRotateRunnable)
    heroRequest++
    heroTitleView = null
    heroMetaView = null
    heroPosterView = null
    heroWatchView = null
    heroListView = null
    heroDotsView = null
}

/** Hero action button: gradient pill for the primary action, glass
 * for the secondary — same focus ring as the Play button. */
private fun MainActivity.heroActionButton(
    label: String,
    primary: Boolean,
    iconRes: Int = 0,
    action: () -> Unit,
) = Button(this).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(null, Typeface.BOLD)
        textSize = if (isTv) 16f else 15f
        minimumHeight = dp(if (isTv) 52 else 48)
        setPadding(dp(20), dp(8), dp(20), dp(8))
        if (iconRes != 0) {
            setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
            compoundDrawablePadding = dp(8)
            compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 3))
            addState(intArrayOf(android.R.attr.state_pressed), glassShape(24, cyan, 2))
            addState(intArrayOf(), if (primary) gradientPill(24) else glassShape(24))
        }
        setOnClickListener { action() }
    }
