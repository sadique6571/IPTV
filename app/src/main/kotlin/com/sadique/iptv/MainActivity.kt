package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val background = 0xff141218.toInt()
    private val surface = 0xff241e2a.toInt()
    private val accent = 0xffffca86.toInt()
    private val imports = Executors.newSingleThreadExecutor()
    private lateinit var playlistFile: AtomicFile
    private lateinit var list: LinearLayout
    private lateinit var screen: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var status: TextView
    private var video: VideoView? = null
    private var web: WebView? = null
    private var selected: Channel? = null
    private var imported = emptyList<Channel>()
    private var filter = "All"
    private var limit = 80

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playlistFile = AtomicFile(File(filesDir, "channels.m3u"))
        try {
            imported = playlistFile.openRead().use { Playlist.parse(Playlist.read(it)) }
        } catch (_: IOException) {
            // Built-in channels are always available.
        }

        val root = column().apply {
            setPadding(dp(16), dp(10), dp(16), dp(12))
            setBackgroundColor(this@MainActivity.background)
        }
        root.addView(label("IPTV", 26, accent))
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val split = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = column()
        val right = column()
        if (wide) {
            split.addView(left, LinearLayout.LayoutParams(dp(260), -1))
            right.setPadding(dp(14), 0, 0, 0)
            split.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            split.addView(right, LinearLayout.LayoutParams(-1, 0, 1f))
            split.addView(left, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val filters = LinearLayout(this)
        left.addView(filters)
        for (name in listOf("All", "PK", "IN", "Cricket", "M3U")) {
            filters.addView(button(name) {
                filter = name
                limit = 80
                populate()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list = column()
        left.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        left.addView(button("Import M3U playlist", ::choosePlaylist))
        left.addView(button("Open HTTPS stream") {
            askAddress("Open HTTPS stream", "https://…/live.m3u8") { address ->
                val uri = runCatching { URI.create(address) }.getOrNull()
                if (uri != null && Playlist.valid(uri)) {
                    play(Channel("Custom stream", "Imported", address))
                } else {
                    toast("Enter a valid HTTPS stream URL.")
                }
            }
        })

        titleText = label("Select a channel", 23, Color.WHITE)
        status = label("", 14, accent)
        screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        right.addView(titleText)
        right.addView(status)
        right.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        populate()
        list.getChildAt(0)?.requestFocus()
    }

    private fun populate() {
        list.removeAllViews()
        val candidates = if (filter == "M3U") imported else builtInChannels + imported
        val matches = candidates.filter { channel ->
            when (filter) {
                "PK" -> channel.group == "Pakistan"
                "IN" -> channel.group == "India"
                "Cricket" -> channel.group == "Cricket"
                else -> true
            }
        }
        matches.take(limit).forEach { channel ->
            list.addView(button(channel.name) { play(channel) }.apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            })
        }
        if (matches.size > limit) {
            list.addView(button("Show more channels") {
                val firstNew = limit
                limit += 80
                populate()
                list.getChildAt(firstNew)?.requestFocus()
            })
        }
        if (matches.isEmpty()) list.addView(label("Import a playlist to add channels.", 14, accent))
    }

    private fun play(channel: Channel) {
        stopPlayback()
        selected = channel
        titleText.text = channel.name
        when {
            channel.url.isEmpty() -> status.text = "No verified player feed available."
            channel.web -> playEmbed(channel.url)
            else -> playStream(channel.url)
        }
    }

    private fun playStream(url: String) {
        status.text = "Connecting to stream…"
        val player = VideoView(this)
        video = player
        screen.addView(player, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        player.setOnPreparedListener {
            if (video === player) {
                status.text = ""
                player.start()
            }
        }
        player.setOnCompletionListener { if (video === player) status.text = "Playback ended." }
        player.setOnErrorListener { _, _, _ ->
            if (video === player) status.text = "Stream unavailable. Select the channel to retry."
            true
        }
        player.setVideoURI(Uri.parse(url))
    }

    private fun playEmbed(videoId: String) {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) {
            status.text = "No verified player feed available."
            return
        }
        status.text = "Press Play if prompted."
        val player = WebView(this)
        web = player
        player.isFocusable = true
        player.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        player.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return request.isForMainFrame || request.url.scheme != "https" ||
                    (host != "www.youtube.com" && host != "www.youtube-nocookie.com")
            }
        }
        screen.addView(player, FrameLayout.LayoutParams(-1, -1))
        val playerDocument = """
            <!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:black}iframe{display:block;width:100vw;height:100vh;border:0}</style>
            <iframe title="Live channel player" src="https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1"
              referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture"></iframe>
            </html>
        """.trimIndent()
        player.loadDataWithBaseURL("https://iptv-design-preview-2edf.roarai.app/", playerDocument,
            "text/html", "UTF-8", null)
    }

    private fun choosePlaylist() {
        AlertDialog.Builder(this)
            .setTitle("Import M3U playlist")
            .setItems(arrayOf("HTTPS playlist URL", "Local playlist file")) { _, which ->
                if (which == 0) askAddress("Playlist URL", "https://…/channels.m3u") { address ->
                    importPlaylist { Playlist.fetch(address) }
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

    private fun askAddress(title: String, hint: String, action: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            this.hint = hint
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
    }

    @Deprecated("The document picker delivers its result through this Android callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 41 && resultCode == RESULT_OK) importPlaylist {
            contentResolver.openInputStream(uri)?.use { Playlist.parse(Playlist.read(it)) }
                ?: throw IOException("Cannot open the file.")
        }
    }

    private fun importPlaylist(load: () -> List<Channel>) {
        toast("Importing playlist…")
        imports.execute {
            try {
                val channels = load()
                savePlaylist(channels)
                runOnUiThread {
                    if (!isDestroyed) {
                        imported = channels
                        filter = "M3U"
                        limit = 80
                        populate()
                        list.getChildAt(0)?.requestFocus()
                        toast("${channels.size} channels imported.")
                    }
                }
            } catch (error: IOException) {
                runOnUiThread { if (!isDestroyed) toast("Playlist import failed: ${error.message}") }
            } catch (_: SecurityException) {
                runOnUiThread { if (!isDestroyed) toast("Cannot access this playlist.") }
            }
        }
    }

    private fun savePlaylist(channels: List<Channel>) {
        val data = buildString {
            appendLine("#EXTM3U")
            for (channel in channels) {
                appendLine("#EXTINF:-1 group-title=\"${channel.group}\",${channel.name}")
                appendLine(channel.url)
            }
        }.toByteArray()
        if (data.size > Playlist.MAX_BYTES) throw IOException("Playlist exceeds 2 MB.")
        val output = playlistFile.startWrite()
        try {
            output.write(data)
            playlistFile.finishWrite(output)
        } catch (error: IOException) {
            playlistFile.failWrite(output)
            throw error
        }
    }

    private fun stopPlayback() {
        video?.stopPlayback()
        video = null
        web?.let { player ->
            screen.removeView(player)
            player.destroy()
        }
        web = null
        screen.removeAllViews()
    }

    override fun onStart() {
        super.onStart()
        selected?.let(::play)
    }

    override fun onStop() {
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        imports.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Use the system back gesture callback on newer Android versions")
    override fun onBackPressed() {
        if (selected == null) {
            super.onBackPressed()
        } else {
            stopPlayback()
            selected = null
            titleText.text = "Select a channel"
            status.text = ""
            list.getChildAt(0)?.requestFocus()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        minimumWidth = 0
        minWidth = 0
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(0xff563a29.toInt(), accent))
            addState(intArrayOf(), shape(surface, this@MainActivity.background))
        }
        setOnClickListener { action() }
    }

    private fun shape(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        setStroke(dp(2), stroke)
        cornerRadius = dp(8).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
