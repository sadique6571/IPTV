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


private fun MainActivity.updateTitleRow(text: String): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    row.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_update_download)
        imageTintList = ColorStateList.valueOf(cyan)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = shape(0x2022d9ee, glassStroke, 14, 1)
        layoutParams = LinearLayout.LayoutParams(dp(if (isTv) 48 else 42), dp(if (isTv) 48 else 42))
    })
    row.addView(label(text, if (isTv) 20 else 18, cyan).apply {
        setTypeface(null, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(10)
        }
    })
    return row
}

private fun MainActivity.trackUpdateDialog(dialog: AlertDialog): AlertDialog {
    updateDownloadDialog = dialog
    dialog.setOnDismissListener {
        if (updateDownloadDialog === dialog) updateDownloadDialog = null
    }
    return dialog
}

private fun MainActivity.updatePrimaryButton(text: String, action: () -> Unit): Button =
    button(text, action = action).apply {
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cyan, blue)).apply {
                    cornerRadius = dp(25).toFloat()
                    setStroke(dp(2), Color.WHITE)
                })
            addState(intArrayOf(android.R.attr.state_pressed), shape(blue, cyan, 25))
            addState(intArrayOf(), gradientPill(25))
        }
        setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
    }

internal fun MainActivity.showUpdateDialog(update: UpdateChecker.Update) {
    if (updateDownloadDialog?.isShowing == true) return
    val layout = themedDialogView()
    layout.addView(updateTitleRow("App Update"))
    layout.addView(label(
        "${update.tag} download ke liye tayyar hai.",
        if (isTv) 16 else 14, textColor,
    ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    var dialog: AlertDialog? = null
    val downloadButton = updatePrimaryButton("Download") {
        dialog?.dismiss()
        downloadAndInstallUpdate(update)
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
    // v2.0.28: big, wide, full-width buttons stacked — the old
    // side-by-side wrap-content pair was too small.
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
    }
    buttons.addView(downloadButton, LinearLayout.LayoutParams(-1, dp(if (isTv) 56 else 52)))
    buttons.addView(laterButton, LinearLayout.LayoutParams(-1, dp(if (isTv) 56 else 52)).apply {
        topMargin = dp(8)
    })
    layout.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(18)
    })
    dialog = trackUpdateDialog(showThemedDialog(layout))
    downloadButton.post { downloadButton.requestFocus() }
}


internal fun MainActivity.downloadAndInstallUpdate(update: UpdateChecker.Update) {
    if (updateDownloadDialog?.isShowing == true) return
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
        progressDrawable = LayerDrawable(arrayOf(track, ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }
    val progressText = label("Download shuru ho raha hai…", if (isTv) 16 else 14, textColor)
    val actions = column()
    val layout = themedDialogView().apply {
        addView(updateTitleRow("App Update"))
        addView(label(update.tag, if (isTv) 15 else 13, muted), LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        addView(progressBar, LinearLayout.LayoutParams(-1, dp(12)).apply {
            topMargin = dp(16)
        })
        addView(progressText, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
        addView(actions, LinearLayout.LayoutParams(-1, -2))
    }
    val dialog = trackUpdateDialog(showThemedDialog(layout, cancelable = false))

    fun showAction(text: String, action: () -> Unit) {
        dialog.setCancelable(true)
        actions.removeAllViews()
        val primary = updatePrimaryButton(text, action)
        actions.addView(primary, LinearLayout.LayoutParams(-1, dp(if (isTv) 56 else 52)).apply {
            topMargin = dp(16)
        })
        actions.addView(button("Baad mein") { dialog.dismiss() },
            LinearLayout.LayoutParams(-1, dp(if (isTv) 56 else 52)).apply { topMargin = dp(8) })
        primary.requestFocus()
    }

    imports.execute {
        try {
            val apk = UpdateChecker.downloadApk(this, update) { done, total ->
                runOnUiThread {
                    if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                    progressText.text = if (total > 0) {
                        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
                        progressBar.progress = percent
                        val sizes = "${formatSize(done)} / ${formatSize(total)} · $percent%"
                        if (done >= total) "$sizes\nAPK verify ho rahi hai…" else sizes
                    } else {
                        progressBar.isIndeterminate = true
                        "${formatSize(done)} download ho gayi"
                    }
                }
            }
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                progressText.text = "${formatSize(apk.length())} · Install ke liye tayyar"
                showAction("Install") {
                    try {
                        UpdateChecker.installApk(this, apk)
                        dialog.dismiss()
                    } catch (_: ActivityNotFoundException) {
                        progressText.text = "Android installer nahi mila."
                    } catch (_: SecurityException) {
                        progressText.text = "Installer ko APK kholne ki ijazat nahi mili."
                    }
                }
            }
        } catch (_: IOException) {
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                progressBar.visibility = View.GONE
                progressText.text = "Update download nahi hui. Dobara koshish karein."
                showAction("Retry") {
                    dialog.dismiss()
                    downloadAndInstallUpdate(update)
                }
            }
        }
    }
}

