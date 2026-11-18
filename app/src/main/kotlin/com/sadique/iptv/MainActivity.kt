package com.sadique.iptv

import android.app.Activity
import android.app.AlertDialog
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import android.widget.HorizontalScrollView
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
        set(value) {
            field = value
            // Keep the chip strip in sync when the filter changes from a
            // dialog or a playlist action instead of a chip tap.
            markActiveFilter()
        }
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

    // Adaptive UI: TV (D-pad, 10-foot) vs touch devices, and orientation.
    // Computed once in onCreate; rotation recreates the activity (no
    // configChanges in the manifest), so the layout rebuilds for the new mode.
    private var isTv = false
    private var isLandscape = false
    private val filterChips = mutableMapOf<String, Button>()

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

        isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        isTv = detectTv()

        // TV and landscape phones/tablets sit side by side; portrait phones
        // and tablets stack the player above the channel list.
        val sideBySide = isTv || isLandscape
        val rootPaddingDp = if (isTv) 16 else 12
        val rootPadding = dp(rootPaddingDp)
        val libraryWidthDp = if (isTv) 320 else 300
        val paneGapDp = if (isTv) 14 else 12
        val root = column().apply {
            setPadding(rootPadding, dp(if (isTv) 10 else 8), rootPadding, dp(if (isTv) 12 else 8))
            setBackgroundColor(this@MainActivity.background)
        }
        // The header is hidden on phones in landscape: vertical space is
        // tight there and the playing row already shows what is on.
        val header = label("IPTV", if (isTv) 28 else 22, accent).apply {
            if (!isTv && isLandscape) visibility = View.GONE
        }
        root.addView(header)
        val split = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        root.addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = column().also { libraryPane = it }
        val right = column()
        if (sideBySide) {
            // Library on the left, player filling the rest. The library is
            // wider than before so the larger text fits comfortably.
            split.addView(left, LinearLayout.LayoutParams(dp(libraryWidthDp), -1))
            right.setPadding(dp(paneGapDp), 0, 0, 0)
            split.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            // Portrait phone/tablet: player pane on top (wrap content, the
            // screen itself is fixed 16:9 below), library filling the rest.
            split.addView(right, LinearLayout.LayoutParams(-1, -2))
            split.addView(left, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val filterNames = listOf("All", "Urdu", "Hindi", "Punjabi", "PK", "IN", "Cricket", "Drama", "M3U", "Favs", "Recent")
        val chipAction: (String) -> Unit = { name ->
            filter = name
            page = 0
            populate()
        }
        if (isTv) {
            // TV: every chip visible in three rows, D-pad navigable.
            val filters = LinearLayout(this)
            left.addView(filters)
            val moreFilters = LinearLayout(this)
            left.addView(moreFilters)
            val extraFilters = LinearLayout(this)
            left.addView(extraFilters)
            for (name in filterNames) {
                val row = when (name) {
                    "All", "Urdu", "Hindi", "Punjabi" -> filters
                    "Favs", "Recent" -> extraFilters
                    else -> moreFilters
                }
                val chipView = chip(name) { chipAction(name) }
                filterChips[name] = chipView
                row.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(6)
                    topMargin = dp(3)
                    bottomMargin = dp(3)
                })
            }
        } else {
            // Touch: one horizontally scrolling chip strip. The old three
            // stretched rows looked odd on narrow screens (two giant
            // Favs/Recent buttons), and the strip scrolls naturally by swipe.
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (name in filterNames) {
                val chipView = chip(name) { chipAction(name) }
                filterChips[name] = chipView
                chipRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(8)
                })
            }
            val strip = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(chipRow)
            }
            left.addView(strip, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        markActiveFilter()
        catalogTitle = label("", if (isTv) 14 else 12, accent)
        left.addView(catalogTitle, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
            bottomMargin = dp(2)
        })
        list = column()
        listScroll = ScrollView(this).apply { addView(list) }
        left.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this)
        left.addView(actions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        actions.addView(button("Search") {
            askText("Search channels", "Channel name") { value ->
                query = value
                page = 0
                populate()
                list.getChildAt(0)?.requestFocus()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(4)
        })
        actions.addView(button("Playlists") { showPlaylists() },
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
            })
        left.addView(button("Import M3U or play URL", ::choosePlaylist),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            })
        left.addView(button("Xtream login", ::askXtream),
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            })

        // On phones in landscape the title is hidden with the header: the
        // playing row already shows the channel, and the player needs the
        // vertical space. Status stays visible (buffering/errors).
        titleText = label("Select a channel", if (isTv) 26 else 20, Color.WHITE).apply {
            if (!isTv && isLandscape) visibility = View.GONE
        }
        status = label("", if (isTv) 15 else 13, accent)
        numberOverlay = label("", if (isTv) 22 else 18, accent).apply { visibility = View.GONE }
        screen = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        buffering = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            visibility = View.GONE
        }
        screen.addView(buffering, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playerControls = column().apply { visibility = View.GONE }
        playPauseButton = button("Play") { playerController?.togglePlayPause() }
        fullscreenButton = button("Full screen") { setFullScreen(!fullScreen) }
        val retryButton = button("Retry") { retryPlayback() }
        qualityButton = button("Quality") { showQualityDialog() }
        speedButton = button("Speed 1x") { showSpeedDialog() }
        volumeDownButton = button("Vol −") { changeVolume(-0.1f) }
        volumeLabel = label("Vol ${(store.volume * 100).toInt()}%", if (isTv) 14 else 13, Color.WHITE).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        volumeUpButton = button("Vol +") { changeVolume(0.1f) }
        favoriteButton = button("☆ Favorite") { toggleFavorite() }
        val sleepButton = button("Sleep timer") { showSleepDialog() }
        // Five wrap-content buttons overflowed a narrow phone row and the
        // labels wrapped mid-word. Below ~560dp of player width the controls
        // split into three compact rows instead of two wide ones; the
        // fullscreen button gets extra weight because its text grows to
        // "Exit full screen" when active. Weight 0 means wrap content.
        val screenWidthDp =
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt()
        val playerPaneWidthDp = if (sideBySide) {
            screenWidthDp - 2 * rootPaddingDp - libraryWidthDp - paneGapDp
        } else {
            screenWidthDp - 2 * rootPaddingDp
        }
        val compactControls = !isTv && playerPaneWidthDp < 560
        val controlRows: List<List<Pair<View, Float>>> = if (compactControls) {
            listOf(
                listOf(playPauseButton to 1f, fullscreenButton to 2f, retryButton to 1f),
                listOf(qualityButton to 1f, speedButton to 1f, favoriteButton to 1f),
                listOf(volumeDownButton to 1f, volumeLabel to 0f, volumeUpButton to 1f, sleepButton to 2f),
            )
        } else {
            listOf(
                listOf(playPauseButton to 1f, fullscreenButton to 1f, retryButton to 1f, qualityButton to 1f, speedButton to 1f),
                listOf(volumeDownButton to 1f, volumeLabel to 0f, volumeUpButton to 1f, favoriteButton to 1f, sleepButton to 1f),
            )
        }
        for ((rowIndex, row) in controlRows.withIndex()) {
            val rowLayout = LinearLayout(this)
            for ((view, weight) in row) {
                rowLayout.addView(view, if (weight > 0f) controlParams(weight) else
                    LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL })
            }
            playerControls.addView(rowLayout, LinearLayout.LayoutParams(-1, -2).apply {
                if (rowIndex > 0) topMargin = dp(6)
            })
        }
        seekRow = LinearLayout(this).apply { visibility = View.GONE }
        seekBar = SeekBar(this).apply {
            isFocusable = true
            // A taller seek bar is easier to drag by touch.
            if (!isTv) minimumHeight = dp(48)
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
        seekLabel = label("0:00 / 0:00", if (isTv) 14 else 13, Color.WHITE)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(seekLabel, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        playerControls.addView(seekRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })
        right.addView(titleText)
        right.addView(status)
        right.addView(numberOverlay)
        right.addView(playerControls, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(4)
            bottomMargin = dp(6)
        })
        if (sideBySide) {
            right.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            // Portrait phone/tablet: fixed 16:9 player under the controls,
            // capped so very wide screens do not push the list off-screen.
            val playerWidthPx = resources.displayMetrics.widthPixels - 2 * rootPadding
            val playerHeight = (playerWidthPx * 9 / 16).coerceAtMost(dp(320))
            right.addView(screen, LinearLayout.LayoutParams(-1, playerHeight))
        }

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
                        // Just the track label ("720p"): the full "Quality:
                        // 720p" wrapped mid-word on narrow phone rows.
                        options[selectedIndex].label
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
        if (page > 0) list.addView(button("Previous page") { showPage(page - 1) },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        matches.drop(start).take(pageSize).forEach { channel ->
            list.addView(channelRow(channel), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(2)
                bottomMargin = dp(2)
            })
        }
        if (start + pageSize < matches.size) {
            list.addView(button("Next page") { showPage(page + 1) },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                })
        }
        if (matches.isEmpty()) list.addView(label("No matching channels.", if (isTv) 16 else 14, accent))
        listScroll.post { listScroll.scrollTo(0, 0) }
        markPlayingRow()
    }

    private fun channelRow(channel: Channel): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            // 48dp+ touch target on phones; roomier on TV for the 10-foot UI.
            minimumHeight = dp(if (isTv) 56 else 52)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            tag = channel.key
            background = rowBackground(channel.key == selected?.key)
            setOnClickListener { play(channel) }
        }
        val tileSize = dp(if (isTv) 48 else 44)
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = channel.name.take(1).uppercase()
            textSize = if (isTv) 20f else 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(tile, tile, 8)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (channel.logo.isNotEmpty()) {
            val logoView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            tileView.addView(logoView, FrameLayout.LayoutParams(-1, -1))
            LogoLoader.load(this, channel.logo, logoView)
        }
        row.addView(tileView, LinearLayout.LayoutParams(tileSize, tileSize))
        val nameView = TextView(this).apply {
            text = (if (store.isFavorite(channel)) "★ " else "") + channel.name
            textSize = if (isTv) 16f else 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, 0, 0)
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
        markPlayingRow()
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
                    qualityButton.text = option.label
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
        textSize = if (isTv) 15f else 14f
        minimumWidth = 0
        minWidth = 0
        // 48dp touch target on phones/tablets; generous on TV too.
        minimumHeight = dp(if (isTv) 46 else 48)
        val sidePadding = dp(if (isTv) 14 else 10)
        setPadding(sidePadding, dp(8), sidePadding, dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(0xff563a29.toInt(), accent, 10, if (isTv) 3 else 2))
            // Pressed state: without it taps on a phone gave no feedback.
            addState(intArrayOf(android.R.attr.state_pressed), shape(tile, accent))
            addState(intArrayOf(), shape(surface, this@MainActivity.background))
        }
        setOnClickListener { action() }
    }

    /** Pill-shaped filter chip with a visible selected state (amber fill). */
    private fun chip(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = if (isTv) 15f else 14f
        minimumWidth = 0
        minWidth = 0
        minimumHeight = dp(if (isTv) 44 else 48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                shape(0xff563a29.toInt(), accent, 24, if (isTv) 3 else 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(0xffe8b96f.toInt(), accent, 24))
            addState(intArrayOf(android.R.attr.state_selected), shape(accent, accent, 24))
            addState(intArrayOf(), shape(surface, this@MainActivity.background, 24))
        }
        setOnFocusChangeListener { _, _ -> refreshChipText(this) }
        setOnClickListener { action() }
    }

    /** Dark text on the amber selected chip, white everywhere else. */
    private fun refreshChipText(chip: Button) {
        chip.setTextColor(if (chip.isSelected && !chip.isFocused) background else Color.WHITE)
    }

    private fun markActiveFilter() {
        for ((name, chipView) in filterChips) {
            chipView.isSelected = name == filter
            refreshChipText(chipView)
        }
    }

    /** Channel-row background: amber-tinted when this row is playing. */
    private fun rowBackground(playing: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused),
            shape(0xff563a29.toInt(), accent, 10, if (isTv) 3 else 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(tile, accent))
        addState(intArrayOf(), if (playing) shape(0xff4a3a22.toInt(), accent)
            else shape(surface, this@MainActivity.background))
    }

    /** Highlights the playing row (rows carry the channel key as their tag). */
    private fun markPlayingRow() {
        val playingKey = selected?.key
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            if (child is LinearLayout) {
                val key = child.tag as? String ?: continue
                child.background = rowBackground(key == playingKey)
            }
        }
    }

    /** Layout params shared by the player-control buttons: even widths, gaps. */
    private fun controlParams(weight: Float = 1f) =
        LinearLayout.LayoutParams(0, -2, weight).apply {
            marginEnd = dp(4)
        }

    private fun shape(color: Int, stroke: Int, radiusDp: Int = 10, strokeDp: Int = 2) =
        GradientDrawable().apply {
            setColor(color)
            setStroke(dp(strokeDp), stroke)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** TV (Leanback) detection: UI mode first, hardware features as fallback. */
    private fun detectTv(): Boolean {
        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val modeType = uiModeManager?.currentModeType
            ?: (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK)
        if (modeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
        // Some boxes report a generic UI mode: Leanback without a touchscreen
        // is a TV for layout purposes.
        return packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PAGE_SIZE = 60
        private const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
    }
}
