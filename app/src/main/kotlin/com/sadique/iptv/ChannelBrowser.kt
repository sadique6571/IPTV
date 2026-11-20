package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Live TV browse pane: channel list, categories, search, favorites.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


/** Switches the TV browse pane between its two levels: the
 * categories, or the channels of the picked category. Focus lands on
 * the active category (categories) or the first row (channels). */
internal fun MainActivity.setBrowseLevel(level: BrowseLevel) {
    if (!isTv) return
    browseLevel = level
    val showingCategories = level == BrowseLevel.CATEGORIES
    categoryListScroll.visibility = if (showingCategories) View.VISIBLE else View.GONE
    catalogTitle.visibility = if (showingCategories) View.GONE else View.VISIBLE
    searchClearRow.visibility =
        if (!showingCategories && query.isNotEmpty()) View.VISIBLE else View.GONE
    listScroll.visibility = if (showingCategories) View.GONE else View.VISIBLE
    if (showingCategories) {
        filterChips[filter]?.requestFocus()
    } else {
        focusPlayingRowOrFirst()
    }
}


/** Focuses the playing channel's row when it is on screen, else the
 * first row — Back and level switches used to drop focus on channel
 * #1 even while another channel was playing. */
internal fun MainActivity.focusPlayingRowOrFirst(preferChannel: Channel? = selected) {
    val channel = preferChannel
    if (channel != null) {
        focusChannel(channel)
        // focusChannel is a no-op when the channel is not in this
        // list (filter changed) — but if focus already sits on a row,
        // that is still a good landing, so only fall back to the
        // first row when focus is nowhere in the list.
        if (currentFocus != null && list.indexOfChild(currentFocus) >= 0) return
    }
    list.getChildAt(0)?.requestFocus()
}


/** Puts D-pad focus where the current browse state needs it: the
 * playing movie's controls when the pane is hidden for a movie,
 * else the active category (categories level) or the playing
 * channel's row (channels level). */
internal fun MainActivity.focusBrowse(preferChannel: Channel? = selected) {
    if (playingMovie != null && !movieEnded && (isTv || isLandscape)) {
        playPauseButton.requestFocus()
        return
    }
    if (isTv && browseLevel == BrowseLevel.CATEGORIES) {
        filterChips[filter]?.requestFocus()
        return
    }
    focusPlayingRowOrFirst(preferChannel)
}


/** The browse pane (categories/channels) hides while a movie plays
 * on a side-by-side screen, so the video gets the space; channels
 * keep it (you surf during those). Fullscreen owns visibility while
 * it lasts, so this is a no-op there. */
internal fun MainActivity.updateBrowseVisibility() {
    if (fullScreen) return
    val visible = playingMovie == null || movieEnded || !(isTv || isLandscape)
    libraryPane.visibility = if (visible) View.VISIBLE else View.GONE
}


/** Rebuilds the channel list as ONE list (no pages — the user asked
 * for a single scroll). With preserveScroll, the scroll position is
 * kept (favorite toggles); otherwise the list starts at the top
 * (filter/search changes). */
internal fun MainActivity.populate(preserveScroll: Boolean = false) {
    val savedScrollY = if (preserveScroll) listScroll.scrollY else 0
    val previousRowCount = list.childCount
    highlightedRow = null
    list.removeAllViews()
    // Search clear row (task 2): always visible while a query is active,
    // so there is always a way back to the full list — but only on the
    // channels level (on the categories level it would be a stray row).
    if (query.isNotEmpty()) {
        searchClearLabel.text = "Search: \"$query\""
        searchClearRow.visibility =
            if (browseLevel == BrowseLevel.CHANNELS) View.VISIBLE else View.GONE
    } else {
        searchClearRow.visibility = View.GONE
    }
    // The pane search bar shows the active query too.
    paneSearchLabel.text =
        if (query.isNotEmpty()) "Search: \"$query\"" else "Search channels…"
    val candidates = when {
        // Search is global: a query searches every bundled channel,
        // whatever category is showing — otherwise a search from the
        // News filter can never find a General channel like Express.
        query.isNotEmpty() -> (verifiedChannels + imported).distinctBy { it.key }
        filter == "M3U" -> imported
        filter == "Favs" -> store.favoritesList()
        filter == "Recent" -> store.recentList()
        else -> verifiedChannels
    }
    // Categories match the M3U group-title — case-insensitive,
    // multi-group titles ("Animation;Kids") match every part. General
    // is the catch-all: every group without its own category
    // (Entertainment, Movies, Music, Drama, Legislative…) lands
    // there, so every channel is reachable through some category.
    val matches = candidates.filter { channel ->
        val accepted = if (query.isNotEmpty()) true else when (filter) {
            "News" -> groupMatches(channel, "news")
            "Sports" -> groupMatches(channel, "sports", "cricket")
            "Islamic" -> groupMatches(channel, "islamic", "religious")
            "Kids" -> groupMatches(channel, "kids", "animation")
            "General" -> groupMatches(channel, "general", "undefined") ||
                !categoryGroups.any { groupMatches(channel, it) }
            else -> true
        }
        accepted && (query.isEmpty() || channel.name.contains(query, ignoreCase = true))
    }.let { filtered ->
        // Pinned Pakistani entertainment first (the user's own 1-5,
        // then the rest by popularity), then Pakistani channels,
        // Indian last (stable sort keeps the M3U order inside each
        // group). Favorites/Recent keep their own order; imported
        // playlists keep theirs.
        if (filter == "Favs" || filter == "Recent" || filter == "M3U") filtered
        else filtered.sortedWith(
            compareBy<Channel> { pinnedPkRank[it.name] ?: Int.MAX_VALUE }
                .thenBy {
                    when (it.country.uppercase(Locale.ROOT)) {
                        "PK" -> 0
                        "IN" -> 2
                        else -> 1
                    }
                },
        )
    }
    currentMatches = matches
    val sourceName = when (filter) {
        "M3U" -> playlistStore.active()?.name ?: "My playlist"
        "Favs" -> "Favorites"
        "Recent" -> "Recent"
        else -> "Checked streams"
    }
    catalogTitle.text = "$sourceName · ${matches.size}" +
        if (query.isEmpty()) "" else " · $query"
    appendChannelRows(if (preserveScroll) maxOf(previousRowCount, 16) else 16)
    if (matches.isEmpty()) {
        if (query.isNotEmpty()) {
            // No-result state (task 2): say so AND offer the way back —
            // the user was stuck here with no exit before.
            list.addView(label("Koi natija nahi mila — \"$query\"", if (isTv) 16 else 14, muted))
            list.addView(button("← Poori list wapas") { clearSearch() },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                })
        } else {
            // Contextual empty states (task 10): say WHY the list is
            // empty and what fixes it, instead of a bare "No matches".
            val emptyText = when (filter) {
                "Favs" -> "Koi favorite nahi hai — kisi channel card par ♥ dabayein."
                "Recent" -> "Abhi koi recent channel nahi hai."
                "M3U" -> "Koi playlist import nahi hui — Import M3U dabayein."
                else -> "Is category mein koi channel nahi hai."
            }
            list.addView(label(emptyText, if (isTv) 16 else 14, muted))
        }
    }
    listScroll.post { listScroll.scrollTo(0, savedScrollY) }
    markPlayingRow()
}


/** Case-insensitive group-title match; multi-group titles ("Animation;Kids")
 * match each part, so the channel appears under every category it belongs to. */
internal fun MainActivity.groupMatches(channel: Channel, vararg names: String): Boolean {
    val parts = channel.group.split(';').map { it.trim().lowercase(Locale.ROOT) }
    return names.any { it.lowercase(Locale.ROOT) in parts }
}


/** Clears the active search and restores the full list (task 2). */
internal fun MainActivity.clearSearch() {
    query = ""
    populate()
    list.getChildAt(0)?.requestFocus()
}


/** Glass channel card (Qwen ChannelCard): logo tile, bold name over a
 * muted category, red LIVE badge, and a favorite heart. The playing card
 * gets a cyan border and a cyan playing indicator. No fake scene art —
 * the logo (or a letter tile) is the only artwork. */
internal fun MainActivity.channelRow(channel: Channel): View {
    val isPlaying = channel.key == selected?.key
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        // 48dp+ touch target on phones; compact on TV so more rows fit.
        minimumHeight = dp(if (isTv) 48 else 52)
        setPadding(dp(14), dp(if (isTv) 6 else 10), dp(14), dp(if (isTv) 6 else 10))
        tag = channel.key
        background = rowBackground(isPlaying)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { play(channel) }
        // TV D-pad: RIGHT from a row goes onto the video's own
        // controls (play/pause, volume, fullscreen) — the target is
        // the first overlay control that is actually visible, and
        // applyControlVisibility re-syncs it on every playback change.
        nextFocusRightId = firstOverlayControlId()
    }
    val tileSize = dp(if (isTv) 40 else 44)
    val tileView = FrameLayout(this)
    val initial = TextView(this).apply {
        text = channel.name.take(1).uppercase()
        textSize = if (isTv) 20f else 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi, 12)
    }
    tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
    if (channel.logo.isNotEmpty()) {
        val logoView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        tileView.addView(logoView, FrameLayout.LayoutParams(-1, -1))
        LogoLoader.load(this, channel.logo, logoView)
    }
    row.addView(tileView, LinearLayout.LayoutParams(tileSize, tileSize))
    val texts = column()
    val nameView = TextView(this).apply {
        text = channel.name
        textSize = 16f
        setTypeface(null, Typeface.BOLD)
        setTextColor(textColor)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    texts.addView(nameView)
    val category = channel.group
    if (category.isNotEmpty()) {
        texts.addView(TextView(this).apply {
            text = category
            textSize = 12f
            setTextColor(muted2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }
    row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
        marginStart = dp(12)
    })
    // Playing indicator (cyan) — toggled by markPlayingRow without a
    // list rebuild, so channel changes do not lose the scroll position.
    val eq = TextView(this).apply {
        text = "♫"
        textSize = 18f
        setTextColor(cyan)
        tag = "eq"
        visibility = if (isPlaying) View.VISIBLE else View.GONE
        setPadding(dp(4), 0, dp(8), 0)
    }
    row.addView(eq)

    // Favorite heart — touch only (not D-pad focusable, so remote
    // navigation keeps one stop per row). Tapping toggles this
    // channel's favorite; the on-screen corner button does the same
    // for the playing channel.
    val isFav = store.isFavorite(channel)
    val heart = Button(this).apply {
        tag = "favorite"
        contentDescription = if (isFav) "Remove ${channel.name} from favorites" else "Add ${channel.name} to favorites"
        text = if (isFav) "♥" else "♡"
        textSize = 19f
        setTextColor(if (isFav) magenta else Color.WHITE)
        gravity = Gravity.CENTER
        isFocusable = false
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = shape(0x9904081a.toInt(), 0x1affffff.toInt(), 18, 1)
        setOnClickListener { toggleFavoriteFor(channel) }
    }
    val heartSize = dp(if (isTv) 36 else 48)
    row.addView(heart, LinearLayout.LayoutParams(heartSize, heartSize).apply {
        marginStart = dp(8)
    })
    return row
}


internal fun MainActivity.appendChannelRows(limit: Int) {
    for (index in list.childCount until minOf(limit, currentMatches.size)) {
        val channel = currentMatches[index]
        val row = channelRow(channel)
        // Side margins: a focused row scales to 1.03x
        // (animateFocusScale), and a full-width row's edges clipped at
        // the pane sides. 8dp each side gives the scale room, so the
        // card never looks cut off.
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(if (isTv) 3 else 5)
            bottomMargin = topMargin
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        if (channel.key == selected?.key) highlightedRow = row
    }
}


internal fun MainActivity.focusChannel(channel: Channel) {
    // One list, no pages: the row index IS the match index.
    val index = currentMatches.indexOfFirst { it.key == channel.key }
    if (index < 0) return
    appendChannelRows(index + 1)
    list.getChildAt(index)?.requestFocus()
}


internal fun MainActivity.updateFavoriteButton() {
    val channel = selected
    val isFav = channel != null && store.isFavorite(channel)
    favButton.text = if (isFav) "★" else "☆"
    favButton.setTextColor(if (isFav) magenta else Color.WHITE)
}


internal fun MainActivity.toggleFavorite() {
    val channel = selected ?: return
    toggleFavoriteFor(channel)
}

internal fun MainActivity.toggleFavoriteFor(channel: Channel) {
    val nowFavorite = store.toggleFavorite(channel)
    updateFavoriteButton()
    if (filter == "Favs" && query.isEmpty()) {
        populate(preserveScroll = true)
    } else {
        list.findViewWithTag<View>(channel.key)?.findViewWithTag<Button>("favorite")?.apply {
            text = if (nowFavorite) "♥" else "♡"
            setTextColor(if (nowFavorite) magenta else Color.WHITE)
            contentDescription = if (nowFavorite) "Remove ${channel.name} from favorites" else "Add ${channel.name} to favorites"
        }
    }
    toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
}


/** Re-points every visible channel row's RIGHT target at the overlay
 * after the visible controls changed (channel switch, movie). */
internal fun MainActivity.syncRowFocusTargets() {
    val target = firstOverlayControlId()
    for (i in 0 until list.childCount) {
        list.getChildAt(i)?.nextFocusRightId = target
    }
}


internal fun MainActivity.markActiveFilter() {
    for ((name, chipView) in filterChips) {
        chipView.isSelected = name == filter
        refreshFilterText(chipView)
    }
}


/** Highlights the playing row (rows carry the channel key as their tag)
 * and toggles its cyan playing indicator. */
internal fun MainActivity.markPlayingRow() {
    val key = selected?.key
    val next = key?.let { list.findViewWithTag<View>(it) }
    if (highlightedRow === next) return
    highlightedRow?.apply {
        background = rowBackground(false)
        findViewWithTag<View>("eq")?.visibility = View.GONE
    }
    next?.apply {
        background = rowBackground(true)
        findViewWithTag<View>("eq")?.visibility = View.VISIBLE
    }
    highlightedRow = next
}
