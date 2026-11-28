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
import android.view.ViewTreeObserver
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
 * the active category (categories) or the first row (channels) —
 * unless moveFocus is false: the inline search field switches levels
 * on every keystroke, and a focus jump there would steal the
 * keyboard mid-typing. */
internal fun MainActivity.setBrowseLevel(level: BrowseLevel, moveFocus: Boolean = true) {
    if (!isTv) return
    browseLevel = level
    val showingCategories = level == BrowseLevel.CATEGORIES
    searchClearRow.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
    if (!moveFocus) return
    if (showingCategories) {
        if (!focusChannelSearchContent()) channelSearchInput.requestFocus()
    } else {
        focusPlayingRowOrFirst()
    }
}


/** Focuses the playing channel's row when it is on screen, else the
 * first row — Back and level switches used to drop focus on channel
 * #1 even while another channel was playing. */
internal fun MainActivity.focusPlayingRowOrFirst(preferChannel: Channel? = selected) {
    if (preferChannel != null && focusChannel(preferChannel, center = true)) return
    if (currentFocus?.parent === list) return
    if (!focusChannelSearchContent()) channelSearchInput.requestFocus()
}

/** Focuses the first available browse target below the search field. */
internal fun MainActivity.focusChannelSearchContent(): Boolean {
    if (isTv && browseLevel == BrowseLevel.CATEGORIES) {
        return filterChips[filter]?.requestFocus() == true
    }
    for (index in 0 until list.childCount) {
        val row = list.getChildAt(index)
        if (row.isFocusable && row.requestFocus()) return true
    }
    return false
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
        if (!focusChannelSearchContent()) channelSearchInput.requestFocus()
        return
    }
    focusPlayingRowOrFirst(preferChannel)
}


/** The browse pane (categories/channels) hides while a movie plays
 * on a side-by-side screen, so the video gets the space; channels
 * keep it (you surf during those). Fullscreen owns visibility while
 * it lasts, so this is a no-op there. */
internal fun MainActivity.updateBrowseVisibility() {
    libraryPane.visibility = if (fullScreen || browseHidden) View.GONE else View.VISIBLE
    channelLibrary.visibility = if (moviesOpen) View.GONE else View.VISIBLE
}

internal fun MainActivity.showChannelOrder() {
    val prefs = getSharedPreferences("iptv_layout", Context.MODE_PRIVATE)
    AlertDialog.Builder(this).setTitle("Arrange channels")
        .setSingleChoiceItems(arrayOf("Default", "Name A–Z", "Name Z–A"), prefs.getInt("channel_order", 0)) { dialog, choice ->
            prefs.edit().putInt("channel_order", choice).apply()
            populate(preserveScroll = true)
            dialog.dismiss()
        }.show()
}

/** Indian song/music channels by name (the M3U group is "General"
 * for most of them, so names are the only signal). */
private val songChannelNames = setOf(
    "B4U Music", "Mastiii", "Mh 1 Music", "Music India", "PTC Music",
    "Punjabi Beats HD", "Punjabi Hits", "Punjabi Music HD", "Zingabad",
    "Balle Balle HD", "E 24",
)

/** Pakistani drama channels by name (the M3U group is "General"
 * for Geo Kahani and ARY Zindagi, so names are the only signal). */
private val pkDramaNames = setOf(
    "Geo Kahani", "ARY Zindagi", "Aaj Entertainment",
)

/** Movie channels by name (same reason — the M3U group is
 * "General" for most of them). Pitaara Comedy is NOT here: it is a
 * comedy/drama channel, so it stays in the drama-entertainment
 * tier. */
private val movieChannelNames = setOf(
    "All Time Movies", "B4U Kadak", "B4U Movies", "Boogle Bollywood",
    "Colors Cineplex", "Dangal 2", "Dangal TV", "Goldmines Action",
    "Hindi Hits SD", "Maha Movie", "Manoranjan TV", "MH One Movies",
    "Movie Punjab", "NH BollyFlix", "Pitaara", "Shemaroo Josh",
    "The Movie Club", "Zee Cinema APAC", "Tabbar Hits",
)

/** The list-order tier for a channel (the user's order, 2026-11-25):
 * 0 Pakistani entertainment, 1 Pakistani drama, 2 Pakistani sports,
 * 3 Pakistani news, 4 other cricket sports, 5 Indian songs,
 * 6 Indian movies, 7 Indian / Canadian drama-entertainment, 8 kids,
 * 9 Islamic, 10 everything else. No channel is pinned: the head of
 * tier 0 is the user's own order (pkEntTopOrder), applied as a
 * tiebreaker inside the tier, and every channel stays in its own
 * tier. */
internal fun MainActivity.channelTier(channel: Channel): Int {
    val isPk = channel.country.equals("PK", ignoreCase = true)
    val isIslamic = channel.name == "Paigham Television" ||
        groupMatches(channel, "islamic", "religious")
    // The user's top entertainment order — Geo Entertainment is
    // group Drama in the M3U but the user counts it as
    // entertainment (#6), so the name list wins over the group.
    if (channel.name in pkEntTopOrder) return 0
    if (isIslamic) return 9
    if (isPk) {
        if (channel.name in pkDramaNames || groupMatches(channel, "drama")) return 1
        if (groupMatches(channel, "sports", "cricket")) return 2
        if (groupMatches(channel, "news")) return 3
        if (groupMatches(channel, "kids", "animation")) return 8
        return 0
    }
    if (groupMatches(channel, "sports", "cricket")) return 4
    if (channel.name in songChannelNames || groupMatches(channel, "music")) return 5
    if (channel.name in movieChannelNames || groupMatches(channel, "movies") ||
        channel.name.contains("movie", ignoreCase = true) ||
        channel.name.contains("cinema", ignoreCase = true) ||
        channel.name.contains("cineplex", ignoreCase = true)
    ) return 6
    if (groupMatches(channel, "kids", "animation")) return 8
    if (channel.country.equals("IN", ignoreCase = true) ||
        channel.country.equals("CA", ignoreCase = true)
    ) return 7
    return 10
}

/** Rebuilds the channel list as ONE list (no pages — the user asked
 * for a single scroll). With preserveScroll, the scroll position is
 * kept (favorite toggles); otherwise the list starts at the top
 * (filter/search changes). */
internal fun MainActivity.populate(preserveScroll: Boolean = false) {
    val savedScrollY = if (preserveScroll) listScroll.scrollY else 0
    val previousRowCount = list.childCount
    val focusedIndex = if (preserveScroll) currentFocus?.let(list::indexOfChild) ?: -1 else -1
    val focusedKey = if (focusedIndex >= 0) list.getChildAt(focusedIndex).tag else null
    highlightedRow = null
    list.removeAllViews()
    // Search clear row (task 2): always visible while a query is active,
    // so there is always a way back to the full list — but only on the
    // channels level (on the categories level it would be a stray row).
    if (query.isNotEmpty()) {
        searchClearLabel.text = "Search: \"$query\""
        searchClearRow.visibility = View.VISIBLE
    } else {
        searchClearRow.visibility = View.GONE
    }
    // The inline search field shows the query itself — keep its
    // text in step when the query changed from elsewhere (clear,
    // Back, playlist switches).
    syncChannelSearchInput()
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
        // The user's own order (2026-11-25): Pakistani
        // entertainment first (Green 1, Hum 2, ARY 3, Aan 4, MUN 5,
        // Geo 6, Express 7, then the rest), then Pakistani drama,
        // Pakistani sports, Pakistani news, other cricket sports,
        // Indian songs, Indian movies, Indian / Canadian
        // drama-entertainment, kids, Islamic in their own block,
        // and everything else last. No channel is pinned: the head
        // of the entertainment tier is the pkEntTopOrder tiebreaker,
        // and a stable sort keeps the M3U order inside each tier.
        // Favorites/Recent keep their own order; imported playlists
        // keep theirs.
        if (filter == "Favs" || filter == "Recent" || filter == "M3U") filtered
        else filtered.sortedWith(
            compareBy<Channel> { channelTier(it) }
                .thenBy { pkEntTopRank[it.name] ?: Int.MAX_VALUE }
                .thenBy {
                    when (it.country.uppercase(Locale.ROOT)) {
                        "PK" -> 0
                        "IN" -> 2
                        else -> 1
                    }
                },
        )
    }
    currentMatches = when (getSharedPreferences("iptv_layout", Context.MODE_PRIVATE).getInt("channel_order", 0)) {
        1 -> matches.sortedBy { it.name.lowercase(Locale.ROOT) }
        2 -> matches.sortedByDescending { it.name.lowercase(Locale.ROOT) }
        else -> matches
    }
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
    if (focusedIndex >= 0 && listScroll.isShown) {
        val channel = matches.firstOrNull { it.key == focusedKey }
            ?: matches.getOrNull(focusedIndex.coerceAtMost(matches.lastIndex))
        if (channel == null || !focusChannel(channel, center = true)) focusPlayingRowOrFirst()
    } else {
        listScroll.post { listScroll.scrollTo(0, savedScrollY) }
    }
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
    focusBrowse()
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
        // 48dp+ touch target on phones; padded rounded cards on TV
        // (v2.0.38, mockup) — roomier rows, plenty still fit.
        minimumHeight = dp(if (isTv) 52 else 48)
        setPadding(dp(16), dp(if (isTv) 6 else 8), dp(16), dp(if (isTv) 6 else 8))
        tag = channel.key
        contentDescription = listOf(channel.name, channel.group).filter { it.isNotBlank() }.joinToString(", ")
        background = rowBackground(isPlaying)
        elevation = dp(3).toFloat()
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
        setOnClickListener { play(channel) }
        setOnLongClickListener {
            toggleFavoriteFor(channel)
            true
        }
        // TV D-pad: RIGHT from a row goes onto the video's own
        // controls (play/pause, volume, fullscreen) — the target is
        // the first overlay control that is actually visible, and
        // applyControlVisibility re-syncs it on every playback change.
        nextFocusRightId = firstOverlayControlId()
    }
    val tileSize = dp(if (isTv) 40 else 44)
    val tileView = FrameLayout(this)
    // Round icon tile (v2.0.37): the logo (or its letter tile) shows
    // as a round icon, like the approved TV mockup.
    tileView.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
    tileView.clipToOutline = true
    val initial = TextView(this).apply {
        text = channel.name.take(1).uppercase()
        textSize = if (isTv) 20f else 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = shape(glassHi, glassHi)
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
        // White, not cyan: the playing row is the bright gradient
        // now (v2.0.38), so a cyan mark would vanish on it. This
        // view is only ever visible on the playing row.
        setTextColor(Color.WHITE)
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
        background = shape(0x9904081a.toInt(), 0x1affffff.toInt(), componentRadiusDp, 1)
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


internal fun MainActivity.focusChannel(channel: Channel, center: Boolean = false): Boolean {
    if (!listScroll.isShown || moviesOpen || fullScreen) return false
    val index = currentMatches.indexOfFirst { it.key == channel.key }
    if (index < 0) return false
    appendChannelRows(index + 1)
    val row = list.getChildAt(index) ?: return false
    if (!row.requestFocus()) return false
    if (center) {
        // Wait for a layout pass if the row was just appended or became visible.
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                centerFocusedChannelRow(row)
            }
        })
        row.requestLayout()
    }
    return true
}

private fun MainActivity.centerFocusedChannelRow(row: View) {
    if (!row.hasFocus() || row.parent !== list || !listScroll.isShown) return
    val height = listScroll.height
    val target = (row.top - (height - row.height) / 2).coerceIn(0, maxOf(0, list.height - height))
    listScroll.scrollTo(0, target)
}

/** Keeps TV up/down in the list and creates the next batch before focus reaches its end. */
internal fun MainActivity.moveChannelRowFocus(direction: Int): Boolean {
    val index = currentFocus?.let(list::indexOfChild) ?: return false
    if (index !in currentMatches.indices) return false
    val next = index + direction
    if (next < 0) {
        val clear = searchClearRow.getChildAt(1)
        if (!clear.isShown || !clear.requestFocus()) channelSearchInput.requestFocus()
    } else if (next in currentMatches.indices) {
        if (next >= list.childCount) appendChannelRows(next + 12)
        list.getChildAt(next)?.requestFocus()
    }
    return true
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
