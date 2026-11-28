package com.sadique.iptv

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Movies sidebar (v2.0.37, TV only): the two-pane Movies layout —
 * search field + one vertical column of nav buttons (Home, Movies,
 * TV Shows, Genres, My List), posters on
 * the right. Mobile keeps the top layout (see buildMoviesView).
 *
 * The sidebar IS the tab row: moviesTabRow is this vertical column,
 * so selectMoviesTab / markChipByTag / linkMoviesTabFocus keep
 * working unchanged. The movie search input builder lives here too
 * — mobile uses the same field in its scrolling header.
 */

/** The Movies search field: inline EditText, the keyboard's search
 * key runs the search. D-pad up/down are forwarded (a focused
 * EditText can swallow them on some TVs). */
internal fun MainActivity.buildMovieSearchInput(): EditText {
    return EditText(this).apply {
        id = View.generateViewId()
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = "Movie ka naam…"
        textSize = if (isTv) 17f else 15f
        minimumHeight = dp(if (isTv) 56 else 52)
        styleSearchField()
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchMovies()
                true
            } else {
                false
            }
        }
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN
                    else View.FOCUS_UP
                focusSearch(direction)?.requestFocus()
                true
            } else {
                false
            }
        }
    }
}

/** Builds the TV Movies sidebar: search on top, the tab column.
 * The sidebar is a bright glass panel (v2.0.39) — rounded
 * corners, 12dp internal padding — so its items sit inset and
 * nothing touches the panel edge or looks cut off (v2.0.37's
 * bare column did). v2.0.38's panel used the dark glassShape
 * fill and blended into the background — invisible. */
internal fun MainActivity.buildMoviesSidebar(): LinearLayout {
    val sidebar = brightGlassPanel(12)
    movieSearchInput = buildMovieSearchInput()
    sidebar.addView(movieSearchInput, LinearLayout.LayoutParams(-1, -2).apply {
        marginStart = dp(8)
        marginEnd = dp(8)
        bottomMargin = dp(12)
    })
    // The tab column (vertical): Home / Movies / TV Shows / Genres /
    // My List — Downloads stays mobile-only (not approved for TV).
    moviesTabRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val tabIcons = mapOf(
        MoviesTab.HOME to R.drawable.ic_home,
        MoviesTab.MOVIES to R.drawable.ic_play,
        MoviesTab.SHOWS to R.drawable.ic_tv,
        MoviesTab.GENRES to R.drawable.ic_list,
        MoviesTab.MY_LIST to R.drawable.ic_favorite,
    )
    for (tab in MoviesTab.values()) {
        if (tab == MoviesTab.DOWNLOADS) continue
        val buttonView = chip(tab.label, tabIcons[tab] ?: 0) { selectMoviesTab(tab) }
        buttonView.tag = tab
        buttonView.id = View.generateViewId()
        buttonView.isSelected = tab == moviesTab
        refreshFilterText(buttonView)
        // RIGHT is re-pointed at the first content view on every
        // render (linkMoviesTabFocus); until then it goes nowhere.
        buttonView.nextFocusRightId = View.NO_ID
        moviesTabRow.addView(buttonView, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            bottomMargin = dp(8)
        })
    }
    sidebar.addView(moviesTabRow, LinearLayout.LayoutParams(-1, -2))
    // Explicit vertical links — geometry alone is unreliable across
    // the pane boundary. Up from the search goes to the status
    // bar's "Back to TV" button (v2.0.23 — the sidebar's own Live
    // TV button is gone; the status bar is the way back now).
    val firstTab = moviesTabRow.getChildAt(0)
    val lastTab = moviesTabRow.getChildAt(moviesTabRow.childCount - 1)
    movieSearchInput.nextFocusDownId = firstTab?.id ?: View.NO_ID
    movieSearchInput.nextFocusUpId = backToTvButton.id
    backToTvButton.nextFocusDownId = movieSearchInput.id
    firstTab?.nextFocusUpId = movieSearchInput.id
    lastTab?.nextFocusDownId = View.NO_ID
    return sidebar
}
