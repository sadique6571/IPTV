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


/** Themed dialog shell: navy gradient card with rounded corners, so
 * the update dialogs match the app instead of the system dialog
 * look. The window background goes transparent so the card's own
 * corners show. */
internal fun MainActivity.themedDialogView(): LinearLayout = column().apply {
    background = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(0xff0a1245.toInt(), 0xff2a1b6b.toInt()),
    ).apply {
        cornerRadius = dp(24).toFloat()
        setStroke(dp(1), 0x6696acff.toInt())
    }
    setPadding(dp(24), dp(20), dp(24), dp(20))
}


internal fun MainActivity.showThemedDialog(view: View, cancelable: Boolean = true): AlertDialog {
    val dialog = AlertDialog.Builder(this)
        .setView(view)
        .setCancelable(cancelable)
        .create()
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    dialog.show()
    return dialog
}


internal fun MainActivity.showUpdateDialog(update: UpdateChecker.Update) {
    val layout = themedDialogView()
    layout.addView(label("⬆ App Update", if (isTv) 20 else 18, cyan).apply {
        setTypeface(null, Typeface.BOLD)
    })
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
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(2), Color.WHITE)
                })
            addState(intArrayOf(android.R.attr.state_pressed), shape(blue, cyan, 10))
            addState(intArrayOf(), gradientPill(10))
        }
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
    }
    val laterButton = button("Baad mein") { dialog?.dismiss() }
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
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(cyan)
        progressTintList = ColorStateList.valueOf(cyan)
        progressBackgroundTintList = ColorStateList.valueOf(0x33ffffff.toInt())
    }
    val progressText = label("Download ho raha hai… 0%", 14, textColor)
    val layout = themedDialogView().apply {
        addView(label("⬆ Update download ho raha hai", if (isTv) 18 else 16, cyan).apply {
            setTypeface(null, Typeface.BOLD)
        })
        addView(progressBar, LinearLayout.LayoutParams(-1, -2).apply {
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
                        progressBar.isIndeterminate = false
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

