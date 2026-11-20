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
import android.graphics.drawable.LayerDrawable
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
internal val bg0 = 0xff091120.toInt()
internal val bg1 = 0xff101e32.toInt()
internal val bg2 = 0xff202947.toInt()
internal val glass = 0xed172539.toInt()
internal val glassHi = 0xf122354e.toInt()
internal val glassStroke = 0x2496acff.toInt()
internal val strokeHi = 0x4d96acff.toInt()
internal val textColor = 0xfff2f6ff.toInt()
internal val muted = 0xff9aa7d6.toInt()
internal val muted2 = 0xffa6b6ce.toInt()
internal val cyan = 0xff22d9ee.toInt()
internal val blue = 0xff3d7bff.toInt()
internal val magenta = 0xffe45cff.toInt()
internal val purple = 0xff8b5cff.toInt()
internal val liveRed = 0xffbc334b.toInt()
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


/** Glass side panel (v2.0.38): a rounded glass container with
 * internal padding, so its items sit inset from the panel edge
 * like the approved mockups — nothing touches the edge, nothing
 * looks cut off. Used by the Movies sidebar and the Live TV
 * categories pane. */
internal fun Context.glassPanel(paddingDp: Int = 12) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = glassShape(20)
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}


/** Bright glass fill (v2.0.39): the big panels (sidebars, top bar)
 * need to READ as panels against the dark background — v2.0.38's
 * glassShape fill is nearly the same darkness as the backdrop and
 * its 1dp stroke is only ~14% alpha, so the panels blended in and
 * the user saw no change at all. This fill is clearly lighter
 * (steel-blue top stop) with a 2dp cyan stroke at ~45% alpha,
 * like the mockups' glossy panels. Small controls keep the
 * subtler glassShape — only the big panels use this. */
internal fun Context.brightGlassShape(radiusDp: Int = 20) =
    GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xf23a5a80.toInt(), 0xf22c4a6e.toInt(), 0xf01e3350.toInt()),
    ).apply {
        setStroke(dp(2), 0x7322d9ee.toInt())
        cornerRadius = dp(radiusDp).toFloat()
    }


/** Bright glass side panel (v2.0.39): same shape as glassPanel —
 * rounded container with internal padding — but with the bright
 * fill + strong cyan stroke, so the panel stands out from the
 * background instead of melting into it. */
internal fun Context.brightGlassPanel(paddingDp: Int = 12) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = brightGlassShape(20)
    setPadding(dp(paddingDp), dp(paddingDp), dp(paddingDp), dp(paddingDp))
}


internal fun Context.label(value: String, size: Int, color: Int) = TextView(this).apply {
    text = value
    textSize = size.toFloat()
    setTextColor(color)
    setPadding(0, dp(4), 0, dp(4))
}


// iconRes: optional vector icon (R.drawable.ic_*) shown beside the
// label as a compound drawable — start by default, end when iconEnd
// is true (e.g. the "See All ›" chevron). 0 means text only.
internal fun Context.button(
    value: String,
    iconRes: Int = 0,
    iconEnd: Boolean = false,
    action: () -> Unit,
) = Button(this).apply {
    text = value
    isAllCaps = false
    setTextColor(textColor)
    textSize = if (isTvDevice()) 15f else 14f
    minimumWidth = 0
    // 48dp touch target on phones/tablets; generous on TV too.
    minimumHeight = dp(if (isTvDevice()) 46 else 48)
    val sidePadding = dp(if (isTvDevice()) 14 else 10)
    setPadding(sidePadding, dp(8), sidePadding, dp(8))
    if (iconRes != 0) {
        if (iconEnd) setCompoundDrawablesWithIntrinsicBounds(0, 0, iconRes, 0)
        else setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(textColor)
    }
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            glassShape(10, cyan, if (isTvDevice()) 3 else 2))
        // Pressed state: without it taps on a phone gave no feedback.
        addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 10))
        addState(intArrayOf(), glassShape(10))
    }
    setOnClickListener { action() }
}


/** Wide pill TopBar action button (TV, v2.0.37): vector icon + a
 * visible text title, glass pill with a cyan ring on D-pad focus.
 * Replaces the round icon-only buttons — the big search pill is
 * gone, so the titles fit up here now. */
internal fun Context.topPillButton(iconRes: Int, title: String, action: () -> Unit) =
    Button(this).apply {
        text = title
        isAllCaps = false
        setTextColor(textColor)
        textSize = 15f
        minimumWidth = 0
        minimumHeight = dp(48)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(textColor)
        background = glassStateList(24)
        setOnClickListener { action() }
    }


/** Rounded-rectangle filter chip (v2.0.38, mockup boxes) with a
 * visible selected state (bright cyan→blue gradient). */
internal fun Context.chip(value: String, iconRes: Int = 0, action: () -> Unit) = Button(this).apply {
    text = value
    isAllCaps = false
    setTextColor(muted)
    textSize = if (isTvDevice()) 15f else 14f
    minimumWidth = 0
    minimumHeight = dp(if (isTvDevice()) 52 else 48)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    if (iconRes != 0) {
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        compoundDrawableTintList = ColorStateList.valueOf(muted)
    }
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), glassShape(16, cyan, 2))
        addState(intArrayOf(android.R.attr.state_selected), gradientPill(16))
        addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 16))
        addState(intArrayOf(), glassShape(16))
    }
    setOnFocusChangeListener { _, hasFocus ->
        refreshFilterText(this)
        animateFocusScale(this, hasFocus)
    }
    setOnClickListener { action() }
}


/** White text on the active (gradient) or focused filter, muted otherwise.
 * A chip with a vector icon keeps the icon the same color as the text;
 * harmless on buttons without a compound drawable. */
internal fun refreshFilterText(view: Button) {
    val color = if (view.isSelected || view.isFocused) Color.WHITE else muted
    view.setTextColor(color)
    view.compoundDrawableTintList = ColorStateList.valueOf(color)
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


/** Channel-card background: glass at rest, the bright cyan→blue
 * gradient while this row is playing (v2.0.38, mockup). */
internal fun Context.rowBackground(playing: Boolean) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), glassShape(16, cyan, 2))
    addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 16))
    addState(intArrayOf(), if (playing) gradientPill(16) else glassShape(16))
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
        intArrayOf(0xf12a405a.toInt(), glassHi, glass),
    ).apply {
        setStroke(dp(strokeDp), strokeColor)
        cornerRadius = dp(radiusDp).toFloat()
    }


/** Demo-matching app background (v2.0.38): the navy diagonal
 * gradient plus soft radial glows — cyan top-right, blue
 * bottom-left, a hint of purple top-left — like the mockup's
 * bokeh backdrop. Pure background, no layout change. */
internal fun Context.demoBackground(): LayerDrawable {
    val base = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(bg0, bg1, bg2),
    )
    val w = resources.displayMetrics.widthPixels.toFloat()
    val h = resources.displayMetrics.heightPixels.toFloat()
    fun glow(cx: Float, cy: Float, color: Int, radiusFrac: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            setGradientCenter(cx, cy)
            gradientRadius = minOf(w, h) * radiusFrac
            colors = intArrayOf(color, Color.TRANSPARENT)
        }
    return LayerDrawable(arrayOf(
        base,
        glow(0.85f, 0.12f, 0x4d22d9ee.toInt(), 0.75f),
        glow(0.08f, 0.92f, 0x403d7bff.toInt(), 0.7f),
        glow(0.15f, 0.15f, 0x338b5cff.toInt(), 0.5f),
    ))
}


/** Bright cyan → blue horizontal gradient (v2.0.38 — the mockup's
 * bright selected item, not the old dark teal). Active nav item /
 * chip / primary button. */
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


/** TV category item: glass box at rest (v2.0.38, mockup — every
 * item is a visible box, not transparent), bright gradient when
 * selected (active filter), cyan ring when D-pad focused. */
internal fun Context.navItemBackground() = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), glassShape(16, cyan, 2))
    addState(intArrayOf(android.R.attr.state_selected), gradientPill(16))
    addState(intArrayOf(android.R.attr.state_pressed), glassShape(16, strokeHi, 1))
    addState(intArrayOf(), glassShape(16))
}


internal fun Context.navItem(labelText: String, iconRes: Int = 0, action: () -> Unit) = Button(this).apply {
    text = labelText
    isAllCaps = false
    setTextColor(muted)
    textSize = 16f
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    minimumWidth = 0
    minimumHeight = dp(if (isTvDevice()) 52 else 48)
    setPadding(dp(16), dp(12), dp(16), dp(12))
    if (iconRes != 0) {
        setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        compoundDrawablePadding = dp(8)
        // Tinted to the rest color; refreshFilterText re-tints it white
        // on focus/selection, like the chip icons.
        compoundDrawableTintList = ColorStateList.valueOf(muted)
    }
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


internal fun Context.overlayButton(iconRes: Int, action: () -> Unit) = ImageButton(this).apply {
    setImageResource(iconRes)
    scaleType = ImageView.ScaleType.FIT_CENTER
    imageTintList = ColorStateList.valueOf(Color.WHITE)
    minimumWidth = 0
    minimumHeight = 0
    setPadding(dp(10), dp(10), dp(10), dp(10))
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
