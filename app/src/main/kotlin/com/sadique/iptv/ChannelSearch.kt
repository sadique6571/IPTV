package com.sadique.iptv

import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText

/**
 * Inline channel search (v2.0.37): a real EditText above the channel
 * list — typing filters the list live, no search dialog opens. The
 * query still lives in MainActivity.query and populate() still owns
 * the filtering; this file only builds the field and keeps it in
 * sync when the query changes from elsewhere (clear button, Back,
 * playlist switches).
 */

/** Builds the channel search field and wires live filtering. */
internal fun MainActivity.buildChannelSearchInput(): EditText {
    return EditText(this).apply {
        id = View.generateViewId()
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = "Search channels…"
        setHintTextColor(muted2)
        setTextColor(textColor)
        textSize = if (isTv) 15f else 14f
        minimumHeight = dp(if (isTv) 52 else 48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        compoundDrawablePadding = dp(10)
        compoundDrawableTintList = ColorStateList.valueOf(muted)
        background = glassStateList(24)
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressChannelSearchWatch) return
                query = s.toString().trim()
                populate()
                // Results are channels — the TV pane switches to that
                // level, WITHOUT moving focus: setBrowseLevel's focus
                // jump would steal the keyboard mid-typing.
                setBrowseLevel(BrowseLevel.CHANNELS, moveFocus = false)
            }
        })
        // A focused EditText can swallow D-pad up/down on some TVs
        // (the Leanback keyboard session eats them) — forward them
        // to focus search so navigation never dies on the search box.
        setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN
                    else View.FOCUS_UP
                (isTv && direction == View.FOCUS_DOWN && focusChannelSearchContent()) ||
                    (focusSearch(direction)?.requestFocus() == true)
            } else {
                false
            }
        }
    }
}

/** Keeps the search field's text in step with the query when the
 * query changes from elsewhere (clear button, Back, playlist
 * switches). Called from populate() — every query change ends
 * there. The guard stops the TextWatcher from re-firing. */
internal fun MainActivity.syncChannelSearchInput() {
    if (!isChannelSearchInputReady()) return
    if (channelSearchInput.text.toString() != query) {
        suppressChannelSearchWatch = true
        channelSearchInput.setText(query)
        channelSearchInput.setSelection(query.length)
        suppressChannelSearchWatch = false
    }
}
