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
 * Playlist, Xtream, import and search dialogs.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


internal fun MainActivity.choosePlaylist() {
    AlertDialog.Builder(this)
        .setTitle("Import M3U playlist")
        .setItems(arrayOf("HTTPS playlist URL", "Local playlist file", "Direct stream URL")) { _, which ->
            if (which == 2) askAddress("Open stream", "https://…/live.m3u8") { address ->
                val uri = runCatching { URI.create(address) }.getOrNull()
                if (uri != null && Playlist.validStream(uri)) play(Channel("Custom stream", "Imported", address))
                else toast("Enter a valid HTTP or HTTPS stream URL.")
            } else if (which == 0) askAddress("Playlist URL", "https://…/channels.m3u") { address ->
                importPlaylist({ Playlist.fetch(address) }, SavedPlaylist.Kind.URL, address)
            } else {
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, 41)
                } catch (_: ActivityNotFoundException) {
                    toast("Use a playlist URL on this TV.")
                }
            }
        }.show()
}


internal fun MainActivity.askAddress(title: String, hint: String, action: (String) -> Unit) {
    val dialog = AlertDialog.Builder(this)
    val input = EditText(dialog.context).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        this.hint = hint
    }
    dialog.setTitle(title).setView(dialogFields(input)).setNegativeButton("Cancel", null)
        .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
}


internal fun MainActivity.askText(title: String, hint: String, initial: String = "", action: (String) -> Unit) {
    val dialog = AlertDialog.Builder(this)
    val input = EditText(dialog.context).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        this.hint = hint
        setText(initial)
        setSelection(initial.length)
    }
    dialog.setTitle(title).setView(dialogFields(input)).setNegativeButton("Cancel", null)
        .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
}


internal fun MainActivity.askXtream() {
    val dialog = AlertDialog.Builder(this)
    val serverInput = EditText(dialog.context).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        hint = "https://server:8080"
    }
    val userInput = EditText(dialog.context).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        hint = "Username"
    }
    val passInput = EditText(dialog.context).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        hint = "Password"
    }
    val warning = label("HTTP sends your login without encryption. Use HTTPS when supported.", 14, muted)
    val layout = dialogFields(warning, serverInput, userInput, passInput)
    dialog.setTitle("Xtream Codes login").setView(layout)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Connect") { _, _ ->
            connectXtream(
                serverInput.text.toString().trim(),
                userInput.text.toString().trim(),
                passInput.text.toString(),
            )
        }.show()
}

private fun MainActivity.dialogFields(vararg fields: View): LinearLayout = column().apply {
    setPadding(dp(20), dp(4), dp(20), dp(4))
    for (field in fields) {
        val params = LinearLayout.LayoutParams(-1, -2)
        if (field is EditText) {
            field.background = glassStateList(componentRadiusDp)
            field.minHeight = dp(48)
            field.setPaddingRelative(dp(12), dp(8), dp(12), dp(8))
            params.topMargin = dp(6)
        }
        addView(field, params)
    }
}

internal fun MainActivity.connectXtream(server: String, username: String, password: String) {
    toast("Connecting to Xtream…")
    imports.execute {
        try {
            val channels = Xtream.fetchLiveStreams(server, username, password)
            val host = runCatching { URI.create(server.trim().trimEnd('/')).host }.getOrNull().orEmpty()
            val cleanServer = server.trim().trimEnd('/')
            val playlist = playlistStore.add(
                "Xtream: ${host.ifEmpty { "server" }}",
                SavedPlaylist.Kind.XTREAM, cleanServer, username, password, channels,
            )
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                playlistStore.activeId = playlist.id
                imported = channels
                query = ""
                filter = "M3U"
                populate()
                list.getChildAt(0)?.requestFocus()
                toast("${channels.size} Xtream channels loaded.")
            }
        } catch (error: IOException) {
            runOnUiThread { if (!isDestroyed) toast("Xtream failed: check your connection and playlist settings.") }
        }
    }
}


internal fun MainActivity.showPlaylists() {
    val saved = playlistStore.list()
    val items = mutableListOf("Checked streams (built-in)")
    items += saved.map { playlist ->
        val kindLabel = when (playlist.kind) {
            SavedPlaylist.Kind.URL -> "URL"
            SavedPlaylist.Kind.FILE -> "File"
            SavedPlaylist.Kind.XTREAM -> "Xtream"
        }
        "${playlist.name} [$kindLabel]"
    }
    AlertDialog.Builder(this).setTitle("Playlists")
        .setItems(items.toTypedArray()) { _, which ->
            if (which == 0) {
                playlistStore.activeId = null
                imported = emptyList()
                filter = "All"
                query = ""
                populate()
            } else {
                managePlaylist(saved[which - 1])
            }
        }.show()
}


internal fun MainActivity.managePlaylist(playlist: SavedPlaylist) {
    val actions = if (playlist.kind == SavedPlaylist.Kind.FILE) {
        arrayOf("Open", "Delete")
    } else {
        arrayOf("Open", "Refresh", "Delete")
    }
    AlertDialog.Builder(this).setTitle(playlist.name)
        .setItems(actions) { _, which ->
            when (actions[which]) {
                "Open" -> {
                    playlistStore.activeId = playlist.id
                    imported = playlistStore.channelsOf(playlist)
                    filter = "M3U"
                    query = ""
                    populate()
                    list.getChildAt(0)?.requestFocus()
                }
                "Refresh" -> refreshPlaylist(playlist)
                "Delete" -> {
                    playlistStore.remove(playlist)
                    if (filter == "M3U" && playlistStore.active() == null) {
                        imported = emptyList()
                        filter = "All"
                    }
                    populate()
                    toast("Playlist deleted.")
                }
            }
        }.show()
}


internal fun MainActivity.refreshPlaylist(playlist: SavedPlaylist) {
    toast("Refreshing ${playlist.name}…")
    imports.execute {
        try {
            val fresh = playlistStore.refresh(playlist)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (playlistStore.activeId == playlist.id) {
                    imported = fresh
                    if (filter == "M3U") populate()
                }
                toast("${playlist.name}: ${fresh.size} channels.")
            }
        } catch (error: IOException) {
            runOnUiThread { if (!isDestroyed) toast("Refresh failed: check your connection and playlist settings.") }
        }
    }
}


internal fun MainActivity.refreshStalePlaylists() {
    imports.execute {
        val now = System.currentTimeMillis()
        for (playlist in playlistStore.list()) {
            if (playlist.kind == SavedPlaylist.Kind.FILE) continue
            if (now - playlist.lastRefresh < AUTO_REFRESH_AFTER_MS) continue
            try {
                val fresh = playlistStore.refresh(playlist)
                runOnUiThread {
                    if (!isDestroyed && playlistStore.activeId == playlist.id && filter == "M3U") {
                        imported = fresh
                        populate()
                    }
                }
            } catch (_: IOException) {
                // Keep the saved copy; the user can refresh manually.
            }
        }
    }
}


internal fun MainActivity.importPlaylist(
    load: () -> List<Channel>,
    kind: SavedPlaylist.Kind,
    source: String,
) {
    toast("Importing playlist…")
    imports.execute {
        try {
            val channels = load()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                askText("Playlist name", "My playlist") { name ->
                    imports.execute {
                        val playlist = try {
                            playlistStore.add(name, kind, source, "", "", channels)
                        } catch (error: IOException) {
                            runOnUiThread {
                                if (!isDestroyed) toast("Could not save playlist: check your connection and playlist settings.")
                            }
                            return@execute
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            playlistStore.activeId = playlist.id
                            imported = channels
                            query = ""
                            filter = "M3U"
                            populate()
                            list.getChildAt(0)?.requestFocus()
                            toast("${channels.size} channels imported.")
                        }
                    }
                }
            }
        } catch (error: IOException) {
            runOnUiThread { if (!isDestroyed) toast("Playlist import failed: check your connection and playlist settings.") }
        } catch (_: SecurityException) {
            runOnUiThread { if (!isDestroyed) toast("Cannot access this playlist.") }
        }
    }
}
