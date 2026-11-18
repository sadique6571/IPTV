package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val background = 0xff141218.toInt()
    private val surface = 0xff241e2a.toInt()
    private val tile = 0xff3a3340.toInt()
    private val cricketBroadcasters = Regex("(?i).*(ptv sports|a sports|dd sports|willow|ten sports|star sports).*")
    private val accent = 0xffffca86.toInt()
    private val imports = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: ChannelStore
    private lateinit var playlistStore: PlaylistStore
    private lateinit var sleepTimer: SleepTimer
    private lateinit var list: LinearLayout
    private lateinit var screen: FrameLayout
    private lateinit var titleText: TextView
    private lateinit var status: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var numberOverlay: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var libraryPane: LinearLayout
    private lateinit var playerControls: LinearLayout
    private lateinit var seekRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var playPauseButton: Button
    private lateinit var fullscreenButton: Button
    private lateinit var qualityButton: Button
    private lateinit var speedButton: Button
    private lateinit var volumeDownButton: Button
    private lateinit var volumeUpButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var catalogTitle: TextView
    private var playerController: PlayerController? = null
    private var web: WebView? = null
    private var selected: Channel? = null
    private var imported = emptyList<Channel>()
    private var filter = "All"
    private var page = 0
    private var query = ""
    private var verifiedChannels = emptyList<Channel>()
    private var playbackRequest = 0
    private var fullScreen = false
    private var currentMatches = emptyList<Channel>()
    private var videoOptions = emptyList<PlayerController.VideoOption>()
    private var selectedVideoIndex = -1
    private var seekable = false
    private var scrubbing = false
    private var numberBuffer = ""
    private var numberTimeout: Runnable? = null
    private var speed = 1f
    private var stoppedForBackground = false

    private val seekUpdater = object : Runnable {
        override fun run() {
            val controller = playerController
            if (seekable && controller != null && !scrubbing) {
                val position = controller.position()
                val duration = controller.duration()
                if (duration > 0) {
                    seekBar.max = (duration / 1000).toInt()
                    seekBar.progress = (position / 1000).toInt()
                    seekLabel.text = formatTime(position) + " / " + formatTime(duration)
                }
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        store = ChannelStore(this)
        playlistStore = PlaylistStore(this)
        sleepTimer = SleepTimer {
            stopPlayback()
            selected = null
            playerControls.visibility = View.GONE
            titleText.text = "Select a channel"
            setStatusIdle()
            toast("Sleep timer: playback stopped.")
        }
        playlistStore.migrateLegacy(File(filesDir, "channels.m3u"))
        verifiedChannels = try {
            assets.open("verified-channels.m3u").use { Playlist.parse(Playlist.read(it)) }
        } catch (_: IOException) {
            emptyList()
        }
        imported = playlistStore.active()?.let { playlistStore.channelsOf(it) } ?: emptyList()

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
        val left = column().also { libraryPane = it }
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
        val moreFilters = LinearLayout(this)
        left.addView(moreFilters)
        val extraFilters = LinearLayout(this)
        left.addView(extraFilters)
        for (name in listOf("All", "Urdu", "Hindi", "Punjabi", "PK", "IN", "Cricket", "Drama", "M3U", "Favs", "Recent")) {
            val row = when (name) {
                "All", "Urdu", "Hindi", "Punjabi" -> filters
                "Favs", "Recent" -> extraFilters
                else -> moreFilters
            }
            row.addView(button(name) {
                filter = name
                page = 0
                populate()
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        catalogTitle = label("", 12, accent)
        left.addView(catalogTitle)
        list = column()
        listScroll = ScrollView(this).apply { addView(list) }
        left.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this)
        left.addView(actions)
        actions.addView(button("Search") {
            askText("Search channels", "Channel name") { value ->
                query = value
                page = 0
                populate()
                list.getChildAt(0)?.requestFocus()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Playlists") { showPlaylists() },
            LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(button("Import M3U or play URL", ::choosePlaylist))
        left.addView(button("Xtream login", ::askXtream))

        titleText = label("Select a channel", 23, Color.WHITE)
        status = label("", 14, accent)
        numberOverlay = label("", 20, accent).apply { visibility = View.GONE }
        screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        buffering = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            visibility = View.GONE
        }
        screen.addView(buffering, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playerControls = column().apply { visibility = View.GONE }
        val controlsRow1 = LinearLayout(this)
        playPauseButton = button("Play") { playerController?.togglePlayPause() }
        fullscreenButton = button("Full screen") { setFullScreen(!fullScreen) }
        val retryButton = button("Retry") { retryPlayback() }
        qualityButton = button("Quality") { showQualityDialog() }
        speedButton = button("Speed 1x") { showSpeedDialog() }
        controlsRow1.addView(playPauseButton)
        controlsRow1.addView(fullscreenButton)
        controlsRow1.addView(retryButton)
        controlsRow1.addView(qualityButton)
        controlsRow1.addView(speedButton)
        playerControls.addView(controlsRow1)
        val controlsRow2 = LinearLayout(this)
        volumeDownButton = button("Vol −") { changeVolume(-0.1f) }
        volumeLabel = label("Vol ${(store.volume * 100).toInt()}%", 13, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        volumeUpButton = button("Vol +") { changeVolume(0.1f) }
        favoriteButton = button("☆ Favorite") { toggleFavorite() }
        val sleepButton = button("Sleep timer") { showSleepDialog() }
        controlsRow2.addView(volumeDownButton)
        controlsRow2.addView(volumeLabel)
        controlsRow2.addView(volumeUpButton)
        controlsRow2.addView(favoriteButton)
        controlsRow2.addView(sleepButton)
        playerControls.addView(controlsRow2)
        seekRow = LinearLayout(this).apply { visibility = View.GONE }
        seekBar = SeekBar(this).apply {
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        playerController?.seekTo(progress * 1000L)
                        seekLabel.text = formatTime(progress * 1000L) + " / " +
                            formatTime(playerController?.duration() ?: 0L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    scrubbing = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    scrubbing = false
                }
            })
        }
        seekLabel = label("0:00 / 0:00", 13, Color.WHITE)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(seekLabel)
        playerControls.addView(seekRow)
        right.addView(titleText)
        right.addView(status)
        right.addView(numberOverlay)
        right.addView(playerControls)
        right.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))

        playerController = PlayerController(this, screen, store.volume,
            object : PlayerController.Listener {
                override fun onBuffering(isBuffering: Boolean) {
                    if (isDestroyed) return
                    if (isBuffering) {
                        buffering.bringToFront()
                        buffering.visibility = View.VISIBLE
                        status.text = "Buffering…"
                    } else {
                        buffering.visibility = View.GONE
                        setStatusIdle()
                    }
                }
                override fun onError(message: String) {
                    if (!isDestroyed) status.text = message
                }
                override fun onEnded() {
                    if (!isDestroyed) status.text = "Playback ended."
                }
                override fun onPlayingChanged(isPlaying: Boolean) {
                    if (!isDestroyed) playPauseButton.text = if (isPlaying) "Pause" else "Play"
                }
                override fun onTracksChanged(
                    options: List<PlayerController.VideoOption>,
                    selectedIndex: Int,
                ) {
                    if (isDestroyed) return
                    videoOptions = options
                    selectedVideoIndex = selectedIndex
                    applyControlVisibility()
                    qualityButton.text = if (selectedIndex in options.indices) {
                        "Quality: ${options[selectedIndex].label}"
                    } else {
                        "Quality"
                    }
                }
                override fun onSeekableChanged(isSeekable: Boolean, durationMs: Long) {
                    if (isDestroyed) return
                    seekable = isSeekable
                    applyControlVisibility()
                    if (isSeekable) startSeekUpdates() else stopSeekUpdates()
                }
            })
        setContentView(root)
        populate()
        list.getChildAt(0)?.requestFocus()
        store.lastChannel?.let { last ->
            val match = (verifiedChannels + imported + store.favoritesList() + store.recentList())
                .firstOrNull { it.key == last.key } ?: last
            play(match)
        }
        refreshStalePlaylists()
    }

    private fun populate() {
        list.removeAllViews()
        val candidates = when (filter) {
            "M3U" -> imported
            "Favs" -> store.favoritesList()
            "Recent" -> store.recentList()
            else -> verifiedChannels
        }
        val matches = candidates.filter { channel ->
            val group = channel.group
            val accepted = when (filter) {
                "Urdu" -> "urd" in channel.languages
                "Hindi" -> "hin" in channel.languages
                "Punjabi" -> "pan" in channel.languages
                "PK" -> channel.country == "PK" || group == "Pakistan"
                "IN" -> channel.country == "IN" || group == "India"
                "Cricket" -> group == "Cricket" ||
                    (group.equals("Sports", true) && cricketBroadcasters.matches(channel.name))
                "Drama" -> group.equals("Drama", true) || group.equals("Entertainment", true) ||
                    group.equals("Series", true)
                else -> true
            }
            accepted && (query.isEmpty() || channel.name.contains(query, ignoreCase = true))
        }
        currentMatches = matches
        val pageSize = PAGE_SIZE
        val start = page * pageSize
        val sourceName = when (filter) {
            "M3U" -> playlistStore.active()?.name ?: "My playlist"
            "Favs" -> "Favorites"
            "Recent" -> "Recent"
            else -> "Checked streams"
        }
        catalogTitle.text = "$sourceName · ${matches.size}" +
            if (query.isEmpty()) "" else " · $query"
        if (page > 0) list.addView(button("Previous page") { showPage(page - 1) })
        matches.drop(start).take(pageSize).forEach { channel ->
            list.addView(channelRow(channel))
        }
        if (start + pageSize < matches.size) {
            list.addView(button("Next page") { showPage(page + 1) })
        }
        if (matches.isEmpty()) list.addView(label("No matching channels.", 14, accent))
        listScroll.post { listScroll.scrollTo(0, 0) }
    }

    private fun channelRow(channel: Channel): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), shape(0xff563a29.toInt(), accent))
                addState(intArrayOf(), shape(surface, this@MainActivity.background))
            }
            setOnClickListener { play(channel) }
        }
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = channel.name.take(1).uppercase()
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(tile)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (channel.logo.isNotEmpty()) {
            val logoView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tileView.addView(logoView, FrameLayout.LayoutParams(-1, -1))
            LogoLoader.load(this, channel.logo, logoView)
        }
        row.addView(tileView, LinearLayout.LayoutParams(dp(40), dp(40)))
        val nameView = TextView(this).apply {
            text = (if (store.isFavorite(channel)) "★ " else "") + channel.name
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(nameView, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun showPage(index: Int) {
        page = index
        populate()
        list.getChildAt(if (index > 0) 1 else 0)?.requestFocus()
    }

    private fun setFullScreen(enabled: Boolean) {
        fullScreen = enabled
        fullscreenButton.text = if (enabled) "Exit full screen" else "Full screen"
        libraryPane.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) fullscreenButton.requestFocus()
        else list.getChildAt(0)?.requestFocus()
    }

    private fun play(channel: Channel) {
        stopWeb()
        selected = channel
        // Keep the list on the page the playing channel belongs to.
        val matchIndex = currentMatches.indexOfFirst { it.key == channel.key }
        if (matchIndex >= 0) {
            val targetPage = matchIndex / PAGE_SIZE
            if (targetPage != page) {
                page = targetPage
                populate()
            }
            focusChannel(channel)
        }
        store.addRecent(channel)
        store.lastChannel = channel
        titleText.text = channel.name
        playerControls.visibility = View.VISIBLE
        playPauseButton.text = "Pause"
        speed = 1f
        playerController?.setSpeed(1f)
        speedButton.text = "Speed 1x"
        videoOptions = emptyList()
        selectedVideoIndex = -1
        seekable = false
        stopSeekUpdates()
        updateFavoriteButton()
        applyControlVisibility()
        when {
            channel.url.isEmpty() -> status.text = "No verified player feed available."
            channel.web -> {
                playerController?.stop()
                playEmbed(channel.url)
            }
            else -> {
                status.text = "Connecting to stream…"
                playerController?.play(channel.url)
            }
        }
    }

    private fun focusChannel(channel: Channel) {
        val index = currentMatches.indexOfFirst { it.key == channel.key }
        if (index < 0) return
        val positionInPage = index - page * PAGE_SIZE
        if (positionInPage < 0 || positionInPage >= PAGE_SIZE) return
        val childIndex = positionInPage + if (page > 0) 1 else 0
        list.getChildAt(childIndex)?.requestFocus()
    }

    private fun retryPlayback() {
        val channel = selected ?: return
        if (channel.web) play(channel) else playerController?.retry()
    }

    private fun updateFavoriteButton() {
        val channel = selected
        favoriteButton.text =
            if (channel != null && store.isFavorite(channel)) "★ Favorite" else "☆ Favorite"
    }

    private fun toggleFavorite() {
        val channel = selected ?: return
        val nowFavorite = store.toggleFavorite(channel)
        updateFavoriteButton()
        populate()
        toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
    }

    /** Shows only the controls that apply to the current channel and stream. */
    private fun applyControlVisibility() {
        val webChannel = selected?.web == true
        playPauseButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        qualityButton.visibility = if (webChannel || videoOptions.size < 2) View.GONE else View.VISIBLE
        speedButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        volumeDownButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        volumeUpButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        volumeLabel.visibility = if (webChannel) View.GONE else View.VISIBLE
        seekRow.visibility = if (webChannel || !seekable) View.GONE else View.VISIBLE
    }

    private fun changeVolume(delta: Float) {
        val value = (store.volume + delta).coerceIn(0f, 1f)
        store.volume = value
        playerController?.setVolume(value)
        volumeLabel.text = "Vol ${(value * 100).toInt()}%"
    }

    private fun showQualityDialog() {
        val items = mutableListOf("Auto")
        items += videoOptions.map { it.label }
        val checked = if (selectedVideoIndex in videoOptions.indices) selectedVideoIndex + 1 else 0
        AlertDialog.Builder(this).setTitle("Quality")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                if (which == 0) {
                    playerController?.selectTrack(null)
                    qualityButton.text = "Quality"
                } else {
                    val option = videoOptions[which - 1]
                    playerController?.selectTrack(option)
                    qualityButton.text = "Quality: ${option.label}"
                }
                dialog.dismiss()
            }.show()
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x")
        val values = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val checked = values.indexOfFirst { it == speed }.let { if (it < 0) 2 else it }
        AlertDialog.Builder(this).setTitle("Playback speed")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                speed = values[which]
                playerController?.setSpeed(speed)
                speedButton.text = "Speed ${labels[which]}"
                dialog.dismiss()
            }.show()
    }

    private fun showSleepDialog() {
        val options = arrayOf("Off", "15 min", "30 min", "60 min", "90 min")
        val minutes = intArrayOf(0, 15, 30, 60, 90)
        val checked = minutes.indexOfFirst { it == sleepTimer.minutesRemaining }
            .let { if (it < 0) 0 else it }
        AlertDialog.Builder(this).setTitle("Sleep timer")
            .setSingleChoiceItems(options, checked) { dialog, which ->
                val value = minutes[which]
                if (value == 0) {
                    sleepTimer.cancel()
                    toast("Sleep timer off.")
                } else {
                    sleepTimer.start(value)
                    toast("Sleep timer set: $value min.")
                }
                setStatusIdle()
                dialog.dismiss()
            }.show()
    }

    private fun setStatusIdle() {
        status.text = if (sleepTimer.minutesRemaining > 0) {
            "Sleep timer: ${sleepTimer.minutesRemaining} min"
        } else {
            ""
        }
    }

    private fun startSeekUpdates() {
        mainHandler.removeCallbacks(seekUpdater)
        if (seekable) mainHandler.post(seekUpdater)
    }

    private fun stopSeekUpdates() {
        mainHandler.removeCallbacks(seekUpdater)
    }

    private fun formatTime(valueMs: Long): String {
        val totalSeconds = valueMs / 1000
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.onKeyDown(keyCode, event)
        val digit = when (keyCode) {
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> 0
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> 1
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> 2
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> 3
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> 4
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> 5
            KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> 6
            KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> 7
            KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> 8
            KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> 9
            else -> -1
        }
        if (digit >= 0) {
            if (event.repeatCount == 0) {
                if (numberBuffer.length < 4) numberBuffer += digit
                showNumberBuffer()
                scheduleNumberJump()
            }
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER
        ) {
            if (numberBuffer.isNotEmpty()) {
                jumpToNumber()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (channelSwitchKeysActive()) {
                if (event.repeatCount == 0) {
                    stepChannel(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showNumberBuffer() {
        numberOverlay.text = "Channel $numberBuffer"
        numberOverlay.visibility = View.VISIBLE
    }

    private fun scheduleNumberJump() {
        numberTimeout?.let { mainHandler.removeCallbacks(it) }
        val jump = Runnable { jumpToNumber() }
        numberTimeout = jump
        mainHandler.postDelayed(jump, 2500)
    }

    private fun jumpToNumber() {
        numberTimeout?.let { mainHandler.removeCallbacks(it) }
        numberTimeout = null
        val number = numberBuffer.toIntOrNull()
        numberBuffer = ""
        numberOverlay.visibility = View.GONE
        if (number == null) return
        if (number in 1..currentMatches.size) {
            play(currentMatches[number - 1])
        } else {
            toast("No channel $number in this list.")
        }
    }

    /** Up/down switch channels only in fullscreen or while the player has focus,
     * so list navigation with the remote keeps working. */
    private fun channelSwitchKeysActive(): Boolean {
        if (fullScreen) return true
        var view: View? = currentFocus
        while (view != null) {
            if (view === screen) return true
            view = view.parent as? View
        }
        return false
    }

    private fun stepChannel(delta: Int) {
        if (currentMatches.isEmpty()) return
        val current = currentMatches.indexOfFirst { it.key == selected?.key }
        val next = if (current < 0) 0
            else (current + delta + currentMatches.size) % currentMatches.size
        play(currentMatches[next])
    }

    private fun playEmbed(videoId: String) {
        if (videoId.startsWith("yt-live:")) {
            status.text = "Finding the live video…"
            val request = playbackRequest
            imports.execute {
                val resolved = runCatching { Playlist.resolveYouTubeLive(videoId.substringAfter(':')) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed || request != playbackRequest) return@runOnUiThread
                    if (resolved == null) status.text = "No embeddable live video found."
                    else playEmbed(resolved)
                }
            }
            return
        }
        val embedUrl = when {
            videoId.matches(Regex("[A-Za-z0-9_-]{11}")) ->
                "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&amp;playsinline=1"
            videoId.matches(Regex("yt-channel:UC[A-Za-z0-9_-]{22}")) ->
                "https://www.youtube-nocookie.com/embed/live_stream?channel=${videoId.substringAfter(':')}&amp;autoplay=1"
            videoId.matches(Regex("dm:[A-Za-z0-9]{5,12}")) ->
                "https://www.dailymotion.com/embed/video/${videoId.substringAfter(':')}?autoplay=1"
            else -> {
                status.text = "This listing needs a direct video or embeddable stream URL."
                return
            }
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
                    (host !in setOf("www.youtube.com", "www.youtube-nocookie.com", "www.dailymotion.com"))
            }
        }
        screen.addView(player, FrameLayout.LayoutParams(-1, -1))
        val playerDocument = """
            <!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:black}iframe{display:block;width:100vw;height:100vh;border:0}</style>
            <iframe title="Live channel player" src="$embedUrl"
              referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture"></iframe>
            </html>
        """.trimIndent()
        player.loadDataWithBaseURL("https://iptv-design-preview-2edf.roarai.app/", playerDocument,
            "text/html", "UTF-8", null)
    }

    private fun choosePlaylist() {
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

    private fun askAddress(title: String, hint: String, action: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            this.hint = hint
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
    }

    private fun askText(title: String, hint: String, action: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            this.hint = hint
        }
        AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancel", null)
            .setPositiveButton("OK") { _, _ -> action(input.text.toString().trim()) }.show()
    }

    private fun askXtream() {
        val serverInput = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://server:8080"
        }
        val userInput = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Username"
        }
        val passInput = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Password"
        }
        val layout = column().apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(serverInput)
            addView(userInput)
            addView(passInput)
        }
        AlertDialog.Builder(this).setTitle("Xtream Codes login").setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect") { _, _ ->
                connectXtream(
                    serverInput.text.toString().trim(),
                    userInput.text.toString().trim(),
                    passInput.text.toString().trim(),
                )
            }.show()
    }

    private fun connectXtream(server: String, username: String, password: String) {
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
                    page = 0
                    populate()
                    list.getChildAt(0)?.requestFocus()
                    toast("${channels.size} Xtream channels loaded.")
                }
            } catch (error: IOException) {
                runOnUiThread { if (!isDestroyed) toast("Xtream failed: ${error.message}") }
            }
        }
    }

    private fun showPlaylists() {
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
                    page = 0
                    query = ""
                    populate()
                } else {
                    managePlaylist(saved[which - 1])
                }
            }.show()
    }

    private fun managePlaylist(playlist: SavedPlaylist) {
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
                        page = 0
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
                        page = 0
                        populate()
                        toast("Playlist deleted.")
                    }
                }
            }.show()
    }

    private fun refreshPlaylist(playlist: SavedPlaylist) {
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
                runOnUiThread { if (!isDestroyed) toast("Refresh failed: ${error.message}") }
            }
        }
    }

    private fun refreshStalePlaylists() {
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

    @Deprecated("The document picker delivers its result through this Android callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 41 && resultCode == RESULT_OK) importPlaylist({
            contentResolver.openInputStream(uri)?.use { Playlist.parse(Playlist.read(it)) }
                ?: throw IOException("Cannot open the file.")
        }, SavedPlaylist.Kind.FILE, "")
    }

    private fun importPlaylist(
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
                                    if (!isDestroyed) toast("Could not save playlist: ${error.message}")
                                }
                                return@execute
                            }
                            runOnUiThread {
                                if (isDestroyed) return@runOnUiThread
                                playlistStore.activeId = playlist.id
                                imported = channels
                                query = ""
                                filter = "M3U"
                                page = 0
                                populate()
                                list.getChildAt(0)?.requestFocus()
                                toast("${channels.size} channels imported.")
                            }
                        }
                    }
                }
            } catch (error: IOException) {
                runOnUiThread { if (!isDestroyed) toast("Playlist import failed: ${error.message}") }
            } catch (_: SecurityException) {
                runOnUiThread { if (!isDestroyed) toast("Cannot access this playlist.") }
            }
        }
    }

    private fun stopPlayback() {
        stopWeb()
        playerController?.stop()
        buffering.visibility = View.GONE
        stopSeekUpdates()
    }

    private fun stopWeb() {
        playbackRequest++
        web?.let { player ->
            screen.removeView(player)
            player.destroy()
        }
        web = null
    }

    override fun onStart() {
        super.onStart()
        if (stoppedForBackground) {
            stoppedForBackground = false
            selected?.let(::play)
        }
    }

    override fun onStop() {
        sleepTimer.cancel()
        stopPlayback()
        stoppedForBackground = true
        super.onStop()
    }

    override fun onDestroy() {
        imports.shutdownNow()
        LogoLoader.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Use the system back gesture callback on newer Android versions")
    override fun onBackPressed() {
        if (fullScreen) {
            setFullScreen(false)
        } else if (selected == null) {
            super.onBackPressed()
        } else {
            sleepTimer.cancel()
            stopPlayback()
            selected = null
            playerControls.visibility = View.GONE
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

    companion object {
        private const val PAGE_SIZE = 60
        private const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
    }
}
