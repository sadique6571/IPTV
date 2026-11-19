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
 * Shared UI builders, the Qwen HarmonyOS palette, drawables and formatting helpers.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */

/** Qwen HarmonyOS palette (moved from MainActivity). */
internal val bg0 = 0xff050b2e.toInt()
internal val bg1 = 0xff0a1245.toInt()
internal val bg2 = 0xff2a1b6b.toInt()
internal val glass = 0x8c1e2a66.toInt()
internal val glassHi = 0x992a3a86.toInt()
internal val glassStroke = 0x2496acff.toInt()
internal val strokeHi = 0x4d96acff.toInt()
internal val textColor = 0xfff2f6ff.toInt()
internal val muted = 0xff9aa7d6.toInt()
internal val muted2 = 0xff6e7bad.toInt()
internal val cyan = 0xff22d9ee.toInt()
internal val blue = 0xff3d7bff.toInt()
internal val magenta = 0xffe45cff.toInt()
internal val purple = 0xff8b5cff.toInt()
internal val liveRed = 0xffff3b4e.toInt()
internal val overlayDark = 0x99060a1e.toInt() // Qwen meta chip / overlay pill
internal val accent = cyan // focus strokes, status text, list counts

internal fun formatTime(valueMs: Long): String {
    val totalSeconds = valueMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}


internal fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}


internal fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 ->
            String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024 * 1024 ->
            String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
        bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> ""
    }
}


internal fun Context.toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()


internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }


internal fun Context.label(value: String, size: Int, color: Int) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(color)
    setPadding(0, dp(4), 0, dp(4))
}


internal fun Context.button(value: String, action: () -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    setTextColor(textColor)
    textSize = if (isTvDevice()) 15f else 14f
    minimumWidth = 0
    // 48dp touch target on phones/tablets; generous on TV too.
    minimumHeight = dp(if (isTvDevice()) 46 else 48)
    val sidePadding = dp(if (isTvDevice()) 14 else 10)
    setPadding(sidePadding, dp(8), sidePadding, dp(8))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            glassShape(10, cyan, if (isTvDevice()) 3 else 2))
        // Pressed state: without it taps on a phone gave no feedback.
        addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 10))
        addState(intArrayOf(), glassShape(10))
    }
    setOnClickListener { action() }
}


/** Small round TopBar action button (TV): a glass circle with a
 * glyph, cyan ring on D-pad focus. */
internal fun Context.topRoundButton(glyph: String, description: String, action: () -> Unit) =
    Button(this).apply {
        text = glyph
        isAllCaps = false
        contentDescription = description
        setTextColor(textColor)
        textSize = 18f
        gravity = Gravity.CENTER
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 24))
            addState(intArrayOf(), glassShape(24))
        }
        setOnClickListener { action() }
    }


/** Pill-shaped filter chip with a visible selected state (cyan→blue
 * gradient pill, Qwen tabs). */
internal fun Context.chip(value: String, action: () -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    setTextColor(muted)
    textSize = if (isTvDevice()) 15f else 14f
    minimumWidth = 0
    minimumHeight = dp(if (isTvDevice()) 44 else 48)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_selected), gradientPill(24))
        addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 24))
        addState(intArrayOf(), glassShape(24))
    }
    setOnFocusChangeListener { _, hasFocus ->
        refreshFilterText(this)
        animateFocusScale(this, hasFocus)
    }
    setOnClickListener { action() }
}


/** White text on the active (gradient) or focused filter, muted otherwise. */
internal fun refreshFilterText(view: Button) {
    view.setTextColor(if (view.isSelected || view.isFocused) Color.WHITE else muted)
}


/** Subtle lift on focus (TV remote + keyboard): the focused item scales
 * up slightly, so it visibly rises above its neighbours. */
internal fun animateFocusScale(view: View, focused: Boolean) {
    val scale = if (focused) 1.03f else 1f
    view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
}


/** Round seek thumb: white dot with a cyan ring (YouTube-style), sized
 * for touch on phones and smaller on TV. */
internal fun Context.seekThumb() = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(Color.WHITE)
    setStroke(dp(2), cyan)
    setSize(dp(if (isTvDevice()) 16 else 20), dp(if (isTvDevice()) 16 else 20))
}


/** Channel-card background: glass, cyan border while this row is playing. */
internal fun Context.rowBackground(playing: Boolean) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), glassShape(22, cyan, 2))
    addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 22))
    addState(intArrayOf(), if (playing) glassShape(22, cyan, 2) else glassShape(22))
}


internal fun Context.shape(color: Int, stroke: Int, radiusDp: Int = 10, strokeDp: Int = 2) =
    GradientDrawable().apply {
        setColor(color)
        setStroke(dp(strokeDp), stroke)
        cornerRadius = dp(radiusDp).toFloat()
    }


private var cachedIsTv: Boolean? = null

/** TV (Leanback) detection: UI mode first, hardware features as fallback.
 * Cached — a device never changes from TV to phone at runtime. */
internal fun Context.isTvDevice(): Boolean {
    cachedIsTv?.let { return it }
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    val modeType = uiModeManager?.currentModeType
        ?: (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
    val result = modeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        // Some boxes report a generic UI mode: Leanback without a
        // touchscreen is a TV for layout purposes.
        (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
    cachedIsTv = result
    return result
}


internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()


/** Glass fill: 3-stop vertical gradient (light top edge → GlassHi →
 * Glass) + 1dp stroke. The light top edge is what makes the glass
 * read as a raised panel instead of a flat fill. */
internal fun Context.glassShape(radiusDp: Int = 22, strokeColor: Int = glassStroke, strokeDp: Int = 1) =
    GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xa832468f.toInt(), glassHi, glass),
    ).apply {
        setStroke(dp(strokeDp), strokeColor)
        cornerRadius = dp(radiusDp).toFloat()
    }


/** Cyan → blue horizontal gradient pill (active nav item / chip). */
internal fun Context.gradientPill(radiusDp: Int = 25) =
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(cyan, blue),
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
    }


/** Glass background with a cyan focus ring (search pill). */
internal fun Context.glassStateList(radiusDp: Int) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), glassShape(radiusDp, cyan, 2))
    addState(intArrayOf(android.R.attr.state_pressed), glassShape(radiusDp, strokeHi, 1))
    addState(intArrayOf(), glassShape(radiusDp))
}


/** TV category item: transparent at rest, gradient pill when selected
 * (active filter), cyan ring when D-pad focused. */
internal fun Context.navItemBackground() = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_selected), gradientPill())
    addState(intArrayOf(android.R.attr.state_focused), glassShape(25, cyan, 2))
    addState(intArrayOf(android.R.attr.state_pressed), glassShape(25, strokeHi, 1))
    addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 25, 0))
}


internal fun Context.navItem(labelText: String, action: () -> Unit) = Button(this).apply {
    text = labelText
    isAllCaps = false
    setTextColor(muted)
    textSize = 16f
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    minimumWidth = 0
    minimumHeight = dp(40)
    setPadding(dp(16), dp(8), dp(16), dp(8))
    background = navItemBackground()
    setOnFocusChangeListener { _, hasFocus ->
        refreshFilterText(this)
        animateFocusScale(this, hasFocus)
    }
    setOnClickListener { action() }
}

/** Round button inside the on-video overlay pill: transparent at rest
 * (the pill is the dark background), cyan ring on D-pad focus. */
internal fun Context.overlayButtonBackground() = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), shape(0xcc16224f.toInt(), cyan, 24, 2))
    addState(intArrayOf(android.R.attr.state_pressed), shape(0xcc2a3a86.toInt(), cyan, 24, 1))
    addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 24, 0))
}


internal fun Context.overlayButton(glyph: String, action: () -> Unit) = Button(this).apply {
    text = glyph
    isAllCaps = false
    setTextColor(Color.WHITE)
    textSize = if (isTvDevice()) 20f else 18f
    gravity = Gravity.CENTER
    minimumWidth = 0
    minimumHeight = 0
    setPadding(0, 0, 0, 0)
    background = overlayButtonBackground()
    setOnClickListener { action() }
}


internal fun Context.overlayParams() = LinearLayout.LayoutParams(
    dp(if (isTvDevice()) 48 else 44), dp(if (isTvDevice()) 48 else 44),
).apply {
    marginStart = dp(3)
    marginEnd = dp(3)
}


/** Red LIVE badge (Qwen). Every channel here is live, so the badge shows
 * on every card, on the meta chip and in the info bar. */
internal fun Context.liveBadge() = TextView(this).apply {
    text = "LIVE"
    textSize = 12f
    letterSpacing = 0.08f
    setTypeface(null, Typeface.BOLD)
    setTextColor(Color.WHITE)
    gravity = Gravity.CENTER
    setPadding(dp(11), dp(4), dp(11), dp(4))
    background = shape(liveRed, liveRed, 8, 0)
}
