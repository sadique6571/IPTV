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
import android.graphics.drawable.ClipDrawable
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
 * In-app update check, download and install dialogs.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


/** Silent once-a-day update check: only shows anything when a newer
 * release is actually available. Network/parse failures stay silent. */
internal fun MainActivity.checkForUpdateSilently() {
    if (!UpdateChecker.isCheckDue(this)) return
    imports.execute {
        try {
            val update = UpdateChecker.fetchLatest(this)
            UpdateChecker.markChecked(this)
            if (update != null) {
                runOnUiThread { if (!isDestroyed) showUpdateDialog(update) }
            }
        } catch (_: IOException) {
            // Silent: no network or bad response — try again next launch.
        }
    }
}


internal fun MainActivity.checkForUpdateManually() {
    toast("Update check ho raha hai…")
    imports.execute {
        try {
            val update = UpdateChecker.fetchLatest(this)
            UpdateChecker.markChecked(this)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (update != null) showUpdateDialog(update)
                else toast("App up to date hai.")
            }
        } catch (_: IOException) {
            runOnUiThread {
                if (!isDestroyed) toast("Update check nahi ho saka — internet check karein.")
            }
        }
    }
}


/** Themed dialog shell (v2.0.26): neutral black glass like the
 * player popup, capsule corners, compact padding — the old navy
 * gradient did not match the app's theme. The window background
 * goes transparent so the card's own corners show. */
internal fun MainActivity.themedDialogView(): LinearLayout = column().apply {
    background = GradientDrawable().apply {
        setColor(0xe6000000.toInt())
        cornerRadius = dp(28).toFloat()
        setStroke(dp(1), 0x26ffffff.toInt())
    }
    setPadding(dp(20), dp(16), dp(20), dp(16))
}


internal fun MainActivity.showThemedDialog(view: View, cancelable: Boolean = true): AlertDialog {
    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .setCancelable(cancelable)
        .create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    // Wide capsule (v2.0.26): the default dialog width left the box
    // narrow — 65% of the TV width, 90% on phones.
    val width = (resources.displayMetrics.widthPixels * if (isTv) 0.65f else 0.9f).toInt()
    dialog.window?.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
    return dialog
}


/** Update dialog title row (v2.0.26): a real download icon
 * (ic_download, cyan) instead of the "⬆" text glyph, which read
 * as a broken character on the TV. */
private fun MainActivity.updateTitleRow(text: String): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_download)
        imageTintList = ColorStateList.valueOf(cyan)
        layoutParams = LinearLayout.LayoutParams(
            dp(if (isTv) 28 else 24), dp(if (isTv) 28 else 24),
        )
    })
    row.addView(label(text, if (isTv) 20 else 18, cyan).apply {
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(10)
        }
    })
    return row
}


internal fun MainActivity.showUpdateDialog(update: UpdateChecker.Update) {
    val layout = themedDialogView()
    layout.addView(updateTitleRow("App Update"))
    layout.addView(label(
        "Naya version aa gaya hai (${update.tag}). Download kar ke install karna hai?",
        if (isTv) 16 else 14, textColor,
    ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    var dialog: AlertDialog? = null
    val downloadButton = button("Download") {
        dialog?.dismiss()
        downloadAndInstallUpdate(update)
    }.apply {
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(cyan, blue),
                ).apply {
                    cornerRadius = dp(25).toFloat()
                    setStroke(dp(2), Color.WHITE)
                })
            addState(intArrayOf(android.R.attr.state_pressed), shape(blue, cyan, 25))
            addState(intArrayOf(), gradientPill(25))
        }
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
    }
    // Capsule like Download (v2.0.26) — the default button() shape
    // is only 10dp.
    val laterButton = button("Baad mein") { dialog?.dismiss() }.apply {
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                glassShape(25, cyan, if (isTv) 3 else 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 25))
            addState(intArrayOf(), glassShape(25))
        }
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
    }
    buttons.addView(laterButton, LinearLayout.LayoutParams(-2, -2).apply {
        marginEnd = dp(12)
    })
    buttons.addView(downloadButton)
    layout.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(18)
    })
    dialog = showThemedDialog(layout)
    downloadButton.post { downloadButton.requestFocus() }
}


internal fun MainActivity.downloadAndInstallUpdate(update: UpdateChecker.Update) {
    // Capsule progress bar (v2.0.26): rounded track + rounded cyan
    // fill, replacing the old system bar. No indeterminate phase —
    // it starts at 0% and fills as the download reports.
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progress = 0
        val track = GradientDrawable().apply {
            setColor(0x33ffffff.toInt())
            cornerRadius = dp(6).toFloat()
        }
        val fill = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(cyan, blue),
        ).apply { cornerRadius = dp(6).toFloat() }
        progressDrawable = LayerDrawable(arrayOf(
            track,
            ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL),
        ))
    }
    val progressText = label("Download ho raha hai… 0%", 14, textColor)
    val layout = themedDialogView().apply {
        addView(updateTitleRow("Update download ho raha hai"))
        addView(progressBar, LinearLayout.LayoutParams(-1, dp(12)).apply {
            topMargin = dp(16)
        })
        addView(progressText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
    }
    val dialog = showThemedDialog(layout, cancelable = false)
    updateDownloadDialog = dialog
    imports.execute {
        try {
            val apk = UpdateChecker.downloadApk(this, update) { percent ->
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (percent >= 0) {
                        progressBar.progress = percent
                        progressText.text = "Download ho raha hai… $percent%"
                    }
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    dialog.dismiss()
                    updateDownloadDialog = null
                    try {
                        UpdateChecker.installApk(this, apk)
                    } catch (error: ActivityNotFoundException) {
                        toast("Installer nahi mila.")
                    }
                }
            }
        } catch (error: IOException) {
            runOnUiThread {
                if (!isDestroyed) {
                    dialog.dismiss()
                    updateDownloadDialog = null
                    toast("Download fail ho gaya: ${error.message}")
                }
            }
        }
    }
}

