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
import android.webkit.WebResourceRequest
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

class MainActivity : Activity() {
    // HarmonyOS TV look, taken from the Qwen HarmonyOS theme file the user
    // sent (2026-11-18) and rebuilt for this Views-based app (no Compose):
    // deep navy-purple gradient, glass cards, cyan accent, red LIVE badges.
    // Only the DESIGN comes from that file — its fake channels, fake scenes,
    // fake program data, PiP, cast/subtitle/audio-track UI and settings sheet
    // are deliberately NOT here: this app only shows real data and real
    // features. Green/amber from the Qwen palette are unused (no 4K/HDR
    // badges in this app) and not declared.
    private val bg0 = 0xff050b2e.toInt()
    private val bg1 = 0xff0a1245.toInt()
    private val bg2 = 0xff2a1b6b.toInt()
    private val glass = 0x8c1e2a66.toInt()
    private val glassHi = 0x992a3a86.toInt()
    private val glassStroke = 0x2496acff.toInt()
    private val strokeHi = 0x4d96acff.toInt()
    private val textColor = 0xfff2f6ff.toInt()
    private val muted = 0xff9aa7d6.toInt()
    private val muted2 = 0xff6e7bad.toInt()
    private val cyan = 0xff22d9ee.toInt()
    private val blue = 0xff3d7bff.toInt()
    private val magenta = 0xffe45cff.toInt()
    private val purple = 0xff8b5cff.toInt()
    private val liveRed = 0xffff3b4e.toInt()
    private val overlayDark = 0x99060a1e.toInt() // Qwen meta chip / overlay pill
    private val cricketBroadcasters = Regex("(?i).*(ptv sports|a sports|dd sports|willow|ten sports|star sports).*")
    private val accent = cyan // focus strokes, status text, list counts
    private val imports = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: ChannelStore
    private lateinit var playlistStore: PlaylistStore
    private lateinit var sleepTimer: SleepTimer
    private lateinit var list: LinearLayout
    private lateinit var screen: FrameLayout
    private lateinit var screenBorder: View
    private lateinit var metaChip: LinearLayout
    private lateinit var metaName: TextView
    private lateinit var metaCategory: TextView
    // The LIVE badges (meta chip + info bar) are hidden while a movie plays
    // — a movie is not live — and shown again for channels.
    private lateinit var metaLiveBadge: TextView
    private lateinit var infoLiveBadge: TextView
    private lateinit var overlayBar: LinearLayout
    private lateinit var status: TextView
    private lateinit var buffering: ProgressBar
    private lateinit var numberOverlay: TextView
    private lateinit var listScroll: ScrollView
    private lateinit var libraryPane: LinearLayout
    private lateinit var rootView: LinearLayout
    private lateinit var headerView: LinearLayout
    private lateinit var playerPane: LinearLayout
    private var rootPaddingPx = 0
    private var paneGapPx = 0
    private lateinit var infoBar: LinearLayout
    private lateinit var infoName: TextView
    private lateinit var quickActions: LinearLayout
    private lateinit var seekRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var seekLabel: TextView
    private lateinit var playPauseButton: Button
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var muteButton: Button
    private lateinit var fullscreenButton: ImageButton
    private lateinit var fullscreenIcon: FullscreenIconDrawable
    private lateinit var qualityButton: Button // TopBar cyan chip
    private lateinit var qualityTile: LinearLayout // quick-actions tile, mobile landscape only
    private lateinit var qualityGlyph: TextView
    private lateinit var speedTile: LinearLayout
    private lateinit var speedGlyph: TextView
    private lateinit var favoriteTile: LinearLayout
    private lateinit var favoriteGlyph: TextView
    private lateinit var catalogTitle: TextView
    private var mutedVolume = false
    private var playerController: PlayerController? = null
    private var web: WebView? = null
    private var selected: Channel? = null
    private var imported = emptyList<Channel>()
    private var filter = "All"
        set(value) {
            field = value
            // Keep the sidebar nav / chip strip in sync when the filter
            // changes from a dialog or a playlist action instead of a tap.
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
    private var updateDownloadDialog: AlertDialog? = null

    // MovieBox movies (v1): a full-screen overlay over the Live TV layout.
    // Movies play in the SAME ExoPlayer and never touch the channel store —
    // a signed movie URL must never land in Recent or last-channel, or the
    // app would auto-play an expired link on the next launch.
    private lateinit var movieBox: MovieBox
    private lateinit var moviesView: LinearLayout
    private lateinit var moviesListPane: LinearLayout
    private lateinit var moviesDetailsPane: LinearLayout
    private lateinit var movieSearchInput: EditText
    private lateinit var movieStatus: TextView
    private lateinit var movieProgress: ProgressBar
    private lateinit var movieResults: LinearLayout
    private lateinit var movieResultsScroll: ScrollView
    private lateinit var movieDetailsTitle: TextView
    private lateinit var movieDetailsMeta: TextView
    private lateinit var movieDetailsDesc: TextView
    private lateinit var movieDubRow: LinearLayout
    private lateinit var movieQualityRow: LinearLayout
    private lateinit var moviePlayButton: Button
    private lateinit var movieDetailsStatus: TextView
    private var moviesOpen = false
    private var playingMovie: MovieBox.Movie? = null
    private var playingMovieUrl: String? = null
    private var playingMovieHeaders: Map<String, String> = emptyMap()
    private var savedMoviePosition = 0L
    private var movieSearchRequest = 0
    private var movieDetailsRequest = 0
    private var movieStreamsRequest = 0
    private var currentMovieDetails: MovieBox.Details? = null
    private var currentMovieStreams: List<MovieBox.MovieStream> = emptyList()
    private var currentPlayTitle = ""
    private var selectedDubId: String? = null
    private var selectedQuality = 0

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
            playingMovie = null
            playingMovieUrl = null
            playingMovieHeaders = emptyMap()
            quickActions.visibility = View.GONE
            infoBar.visibility = View.GONE
            playPauseButton.text = "▶"
            setChannelMeta(null)
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

        // Rotation recreates the activity (no configChanges in the manifest),
        // and entering fullscreen on a phone triggers a rotation — so the
        // fullscreen state is saved and restored here. Playback resumes from
        // the last channel below (live TV, no position to save); the screen
        // never goes blank.
        fullScreen = savedInstanceState?.getBoolean(STATE_FULLSCREEN, false) ?: false
        if (fullScreen && !isTv) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // TV and landscape phones/tablets sit side by side; portrait phones
        // and tablets stack the player above the channel list.
        val sideBySide = isTv || isLandscape
        val rootPaddingDp = if (isTv) 16 else 12
        val rootPadding = dp(rootPaddingDp)
        rootPaddingPx = rootPadding
        val libraryWidthDp = if (isTv) 320 else 300
        val paneGapDp = if (isTv) 14 else 12
        paneGapPx = dp(paneGapDp)
        rootView = column().apply {
            setPadding(rootPadding, dp(if (isTv) 10 else 8), rootPadding, dp(if (isTv) 12 else 8))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg0, bg1, bg2),
            )
        }
        // TopBar (Qwen): logo mark + IPTV title + glass search pill + cyan
        // quality chip. Hidden on phones in landscape (vertical space is
        // tight there; the footer Search button and the quality tile cover
        // it) — the playing card already shows what is on.
        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!isTv && isLandscape) visibility = View.GONE
        }
        headerView.addView(logoMark())
        headerView.addView(label("IPTV", if (isTv) 28 else 22, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(12), 0, dp(16), 0)
        })
        val searchPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 56 else 48)
            background = glassStateList(28)
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener { openSearch() }
        }
        searchPill.addView(label("🔍", if (isTv) 18 else 16, muted).apply {
            setPadding(0, 0, dp(12), 0)
        })
        searchPill.addView(label("Search channels…", if (isTv) 16 else 14, muted2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        headerView.addView(searchPill, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(12)
        })
        // The Quality control lives here as a cyan chip (Qwen TopBar). It
        // starts hidden — applyControlVisibility shows it once a stream
        // reports two or more video tracks.
        qualityButton = Button(this).apply {
            text = "Quality"
            isAllCaps = false
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 17f else 15f
            minimumWidth = 0
            minimumHeight = dp(if (isTv) 48 else 44)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    shape(0xb8070d26.toInt(), cyan, 14, 2))
                addState(intArrayOf(android.R.attr.state_pressed),
                    shape(0xcc1e2a66.toInt(), cyan, 14, 1))
                addState(intArrayOf(),
                    shape(0xb8070d26.toInt(), 0x6622d9ee.toInt(), 14, 1))
            }
            visibility = View.GONE
            setOnClickListener { showQualityDialog() }
        }
        headerView.addView(qualityButton)
        rootView.addView(headerView)
        val split = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        rootView.addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = column().also { libraryPane = it }
        playerPane = column()

        val filterNames = listOf("All", "Favs", "Recent", "Urdu", "Hindi", "Punjabi", "PK", "IN", "Cricket", "Drama", "M3U")
        val filterLabels = mapOf("Favs" to "♥ Favorites", "Recent" to "◷ Recent")
        val chipAction: (String) -> Unit = { name ->
            filter = name
            page = 0
            populate()
        }
        if (isTv) {
            // TV: LEFT glass sidebar with the real channel categories only.
            // This is a Live TV app, so there is no Home/Movies/Series/
            // Catch Up/Settings nav — those sections do not exist. The
            // active item is a cyan→blue gradient pill (Qwen sidebar).
            // Eleven items do not fit a short TV screen, so the list
            // scrolls; D-pad focus auto-scrolls to the focused item.
            val sidebarItems = column()
            for (name in filterNames) {
                val item = navItem(filterLabels[name] ?: name) { chipAction(name) }
                filterChips[name] = item
                sidebarItems.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(6)
                })
            }
            // Movies live outside the channel filters — a plain nav item
            // (not in filterChips, so it never becomes the active filter).
            sidebarItems.addView(navItem("🎬 Movies") { openMovies() },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(8)
                })
            val sidebarScroll = ScrollView(this).apply { addView(sidebarItems) }
            val sidebar = column().apply {
                background = glassShape(28)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                addView(sidebarScroll, LinearLayout.LayoutParams(-1, -1))
            }
            split.addView(sidebar, LinearLayout.LayoutParams(dp(210), -1))
        }
        if (sideBySide) {
            // Library on the left, player filling the rest. The library is
            // wider than before so the larger text fits comfortably.
            split.addView(left, LinearLayout.LayoutParams(dp(libraryWidthDp), -1))
            playerPane.setPadding(dp(paneGapDp), 0, 0, 0)
            split.addView(playerPane, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            // Portrait phone/tablet: player pane on top (wrap content, the
            // screen itself is fixed 16:9 below), library filling the rest.
            split.addView(playerPane, LinearLayout.LayoutParams(-1, -2))
            split.addView(left, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        if (!isTv) {
            // Touch: one horizontally scrolling chip strip (the sidebar is
            // TV-only). The old three stretched rows looked odd on narrow
            // screens, and the strip scrolls naturally by swipe.
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
        actions.addView(button("Search") { openSearch() },
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(4)
            })
        actions.addView(button("Playlists") { showPlaylists() },
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
            })
        // Library footer: two tidy rows of equal buttons instead of stacked
        // full-width ones. The Import dialog still offers a direct stream
        // URL — only the label is shorter.
        val libraryActions = LinearLayout(this)
        left.addView(libraryActions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })
        libraryActions.addView(button("Import M3U", ::choosePlaylist),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(4)
            })
        libraryActions.addView(button("Xtream", ::askXtream),
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(4)
            })
        // Manual update check (UpdateChecker): compares the installed build
        // with the latest GitHub release and offers to install it.
        val updateRow = LinearLayout(this)
        left.addView(updateRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(6)
        })
        if (isTv) {
            updateRow.addView(button("⟳ Update", ::checkForUpdateManually),
                LinearLayout.LayoutParams(-1, -2))
        } else {
            // Touch devices have no sidebar, so Movies gets a footer button
            // next to Update (on TV it is a sidebar item instead).
            updateRow.addView(button("⟳ Update", ::checkForUpdateManually),
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(4)
                })
            updateRow.addView(button("🎬 Movies") { openMovies() },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(4)
                })
        }

        // Player card (Qwen PlayerCard): 16:9, 24dp rounded corners, 1dp
        // stroke. The meta chip (LIVE + channel name) sits ON the video
        // bottom-left on TV; on touch screens it sits top-left so it never
        // collides with the bottom-right overlay controls on narrow phones.
        // The YouTube-style overlay controls (play/pause, next, volume
        // slider, mute, fullscreen toggle) sit bottom-right ON the video.
        screen = FrameLayout(this).apply {
            setBackgroundColor(0xff060b28.toInt())
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
            clipToOutline = true
        }
        buffering = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(cyan)
        }
        screen.addView(buffering, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // 1dp stroke drawn over the video edges (the video surface would
        // cover a plain background stroke). Re-raised whenever a new player
        // view is added to the screen.
        screenBorder = View(this).apply {
            isClickable = false
            isFocusable = false
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), strokeHi)
                cornerRadius = dp(24).toFloat()
            }
        }
        screen.addView(screenBorder, FrameLayout.LayoutParams(-1, -1))
        metaChip = column().apply {
            background = shape(overlayDark, 0x1affffff.toInt(), 16, 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(liveBadge().also { metaLiveBadge = it })
        metaName = TextView(this).apply {
            text = "Select a channel"
            textSize = if (isTv) 18f else 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), 0, 0, 0)
            maxWidth = (resources.displayMetrics.widthPixels * 0.5).toInt()
        }
        metaRow.addView(metaName)
        metaChip.addView(metaRow)
        metaCategory = TextView(this).apply {
            textSize = if (isTv) 14f else 13f
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        metaChip.addView(metaCategory)
        screen.addView(metaChip, FrameLayout.LayoutParams(
            -2, -2, (if (isTv) Gravity.BOTTOM else Gravity.TOP) or Gravity.START,
        ).apply {
            marginStart = dp(if (isTv) 20 else 12)
            topMargin = dp(12)
            bottomMargin = dp(if (isTv) 20 else 12)
        })
        numberOverlay = TextView(this).apply {
            textSize = if (isTv) 22f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = shape(overlayDark, cyan, 16, 1)
            visibility = View.GONE
        }
        screen.addView(numberOverlay, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(16)
        })
        overlayBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(overlayDark, 0x1affffff.toInt(), 24, 1)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        playPauseButton = overlayButton("▶") { playerController?.togglePlayPause() }
        val nextButton = overlayButton("⏭") { stepChannel(1) }
        volumeSeekBar = SeekBar(this).apply {
            max = 100
            progress = (store.volume * 100).toInt()
            progressTintList = ColorStateList.valueOf(cyan)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x38ffffff.toInt())
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setVolume(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            })
        }
        muteButton = overlayButton(if (store.volume == 0f) "🔇" else "🔊") { toggleMute() }
        fullscreenIcon = FullscreenIconDrawable(expanded = false)
        fullscreenButton = ImageButton(this).apply {
            setImageDrawable(fullscreenIcon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Fullscreen"
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = overlayButtonBackground()
            setOnClickListener { setFullScreen(!fullScreen) }
        }
        overlayBar.addView(playPauseButton, overlayParams())
        overlayBar.addView(nextButton, overlayParams())
        overlayBar.addView(volumeSeekBar, LinearLayout.LayoutParams(
            dp(if (isTv) 140 else 80), -2,
        ).apply {
            marginStart = dp(6)
            marginEnd = dp(6)
            gravity = Gravity.CENTER_VERTICAL
        })
        overlayBar.addView(muteButton, overlayParams())
        overlayBar.addView(fullscreenButton, overlayParams())
        screen.addView(overlayBar, FrameLayout.LayoutParams(
            -2, -2, Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = dp(if (isTv) 20 else 12)
            bottomMargin = dp(if (isTv) 20 else 12)
        })
        // Glass info bar under the video (Qwen's 62dp bar): seek slider +
        // real channel name + LIVE badge. No fake "Now/Next" program names —
        // there is no EPG data, so only the real channel name is shown.
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassShape(18)
            setPadding(dp(22), 0, dp(22), 0)
            minimumHeight = dp(62)
            visibility = View.GONE
        }
        seekRow = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
        }
        seekBar = SeekBar(this).apply {
            isFocusable = true
            // A taller seek bar is easier to drag by touch.
            if (!isTv) minimumHeight = dp(48)
            progressTintList = ColorStateList.valueOf(cyan)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x38ffffff.toInt())
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
        seekLabel = label("0:00 / 0:00", if (isTv) 14 else 13, muted)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(seekLabel, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(8)
        })
        infoBar.addView(seekRow, LinearLayout.LayoutParams(0, -2, 1.1f))
        infoName = TextView(this).apply {
            text = "Select a channel"
            textSize = if (isTv) 16f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(18), 0, 0, 0)
        }
        infoBar.addView(infoName, LinearLayout.LayoutParams(0, -2, 1f))
        infoBar.addView(View(this).apply { setBackgroundColor(strokeHi) },
            LinearLayout.LayoutParams(dp(1), dp(26)).apply {
                marginStart = dp(18)
                marginEnd = dp(18)
            })
        infoBar.addView(liveBadge().also { infoLiveBadge = it })
        // Quick Actions glass tiles (Qwen RightPanel style, real actions
        // only — no Cast, Subtitles, Audio Track or PiP tiles): favorite,
        // quality (mobile landscape only — the TopBar chip covers the rest),
        // speed, retry, sleep timer.
        quickActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val (favTile, favGlyph) = actionTile("☆", "Fav") { toggleFavorite() }
        favoriteTile = favTile
        favoriteGlyph = favGlyph
        val (qTile, qGlyph) = actionTile("Auto", "Quality") { showQualityDialog() }
        qualityTile = qTile
        qualityGlyph = qGlyph
        val (spTile, spGlyph) = actionTile("1x", "Speed") { showSpeedDialog() }
        speedTile = spTile
        speedGlyph = spGlyph
        val (retryTile, _) = actionTile("↻", "Retry") { retryPlayback() }
        val (sleepTile, _) = actionTile("⏰", "Sleep") { showSleepDialog() }
        val tiles = listOf(favoriteTile, qualityTile, speedTile, retryTile, sleepTile)
        for ((index, tile) in tiles.withIndex()) {
            quickActions.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (index < tiles.size - 1) marginEnd = dp(8)
            })
        }
        // Status line (buffering/errors/sleep timer): hidden whenever its
        // text is empty, so it takes no space in the normal case.
        status = label("", if (isTv) 15 else 13, accent).apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            })
            visibility = View.GONE
        }
        if (sideBySide) {
            playerPane.addView(screen, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            // Portrait phone/tablet: fixed 16:9 player, capped so very wide
            // screens do not push the list off-screen.
            val playerWidthPx = resources.displayMetrics.widthPixels - 2 * rootPadding
            val playerHeight = (playerWidthPx * 9 / 16).coerceAtMost(dp(320))
            playerPane.addView(screen, LinearLayout.LayoutParams(-1, playerHeight))
        }
        playerPane.addView(infoBar, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
        playerPane.addView(quickActions, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        playerPane.addView(status, LinearLayout.LayoutParams(-1, -2))

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
                    if (!isDestroyed) playPauseButton.text = if (isPlaying) "❚❚" else "▶"
                }
                override fun onTracksChanged(
                    options: List<PlayerController.VideoOption>,
                    selectedIndex: Int,
                ) {
                    if (isDestroyed) return
                    videoOptions = options
                    selectedVideoIndex = selectedIndex
                    applyControlVisibility()
                    // Just the track label ("720p"): the full "Quality:
                    // 720p" wrapped mid-word on narrow phone rows.
                    setQualityText(if (selectedIndex in options.indices) {
                        options[selectedIndex].label
                    } else {
                        "Quality"
                    })
                }
                override fun onSeekableChanged(isSeekable: Boolean, durationMs: Long) {
                    if (isDestroyed) return
                    seekable = isSeekable
                    applyControlVisibility()
                    if (isSeekable) startSeekUpdates() else stopSeekUpdates()
                }
            })
        // The Movies overlay sits on top of the Live TV layout in a frame,
        // so opening it never detaches the player view underneath.
        movieBox = MovieBox(applicationContext)
        moviesView = buildMoviesView()
        val contentFrame = FrameLayout(this)
        contentFrame.addView(rootView, FrameLayout.LayoutParams(-1, -1))
        contentFrame.addView(moviesView, FrameLayout.LayoutParams(-1, -1))
        setContentView(contentFrame)
        populate()
        list.getChildAt(0)?.requestFocus()
        // A movie playing across a rotation (or a process death) restores
        // from its saved signed URL instead of auto-playing the last channel.
        val savedMovieUrl = savedInstanceState?.getString(STATE_MOVIE_URL)
        if (savedMovieUrl != null) {
            restoreMoviePlayback(savedInstanceState)
        } else {
            store.lastChannel?.let { last ->
                val match = (verifiedChannels + imported + store.favoritesList() + store.recentList())
                    .firstOrNull { it.key == last.key } ?: last
                play(match)
            }
        }
        // Restored fullscreen (after the rotation it triggered): hide the
        // library, header and controls again and re-hide the system UI.
        if (fullScreen) updateFullscreenUi()
        refreshStalePlaylists()
        checkForUpdateSilently()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FULLSCREEN, fullScreen)
        // Save a playing movie (URL + headers + position) so a rotation —
        // including the one the fullscreen toggle triggers — resumes it.
        // The signed URL stays valid for hours, so this is safe.
        val movie = playingMovie
        val url = playingMovieUrl
        if (movie != null && url != null) {
            outState.putString(STATE_MOVIE_URL, url)
            outState.putString(STATE_MOVIE_TITLE, movie.title)
            outState.putLong(STATE_MOVIE_POS, playerController?.position() ?: 0L)
            outState.putStringArrayList(
                STATE_MOVIE_HEADERS,
                ArrayList(playingMovieHeaders.map { (k, v) -> "$k\u0001$v" }),
            )
        }
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
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        if (start + pageSize < matches.size) {
            list.addView(button("Next page") { showPage(page + 1) },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                })
        }
        if (matches.isEmpty()) list.addView(label("No matching channels.", if (isTv) 16 else 14, muted))
        listScroll.post { listScroll.scrollTo(0, 0) }
        markPlayingRow()
    }

    /** Glass channel card (Qwen ChannelCard): logo tile, bold name over a
     * muted category, red LIVE badge, and a favorite heart. The playing card
     * gets a cyan border and a cyan playing indicator. No fake scene art —
     * the logo (or a letter tile) is the only artwork. */
    private fun channelRow(channel: Channel): View {
        val isPlaying = channel.key == selected?.key
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            // 48dp+ touch target on phones; roomier on TV for the 10-foot UI.
            minimumHeight = dp(if (isTv) 56 else 52)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            tag = channel.key
            background = rowBackground(isPlaying)
            setOnClickListener { play(channel) }
        }
        val tileSize = dp(if (isTv) 48 else 44)
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = channel.name.take(1).uppercase()
            textSize = if (isTv) 20f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
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
        val texts = column()
        val nameView = TextView(this).apply {
            text = channel.name
            textSize = if (isTv) 16f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(nameView)
        val category = channel.group
        if (category.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = category
                textSize = 12.5f
                setTextColor(muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        // Playing indicator (cyan) — toggled by markPlayingRow without a
        // list rebuild, so channel changes do not lose the scroll position.
        val eq = TextView(this).apply {
            text = "♫"
            textSize = 18f
            setTextColor(cyan)
            tag = "eq"
            visibility = if (isPlaying) View.VISIBLE else View.GONE
            setPadding(dp(4), 0, dp(8), 0)
        }
        row.addView(eq)
        row.addView(liveBadge())
        // Favorite heart — touch only (not D-pad focusable, so remote
        // navigation keeps one stop per row). Tapping toggles this
        // channel's favorite; the tile below the player does the same for
        // the playing channel.
        val isFav = store.isFavorite(channel)
        val heart = Button(this).apply {
            text = if (isFav) "♥" else "♡"
            textSize = 19f
            setTextColor(if (isFav) magenta else Color.WHITE)
            gravity = Gravity.CENTER
            isFocusable = false
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = shape(0x9904081a.toInt(), 0x1affffff.toInt(), 18, 1)
            setOnClickListener { toggleFavoriteFor(channel) }
        }
        row.addView(heart, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(8)
        })
        return row
    }

    private fun showPage(index: Int) {
        page = index
        populate()
        list.getChildAt(if (index > 0) 1 else 0)?.requestFocus()
    }

    private fun setFullScreen(enabled: Boolean) {
        if (fullScreen == enabled) return
        fullScreen = enabled
        if (!isTv) {
            // Rotation recreates the activity; the state is saved and
            // re-applied in onCreate, so there is no toggle loop.
            requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        updateFullscreenUi()
        if (enabled) {
            if (fullscreenButton.isShown) fullscreenButton.requestFocus()
        } else {
            list.getChildAt(0)?.requestFocus()
        }
    }

    /**
     * Applies the current [fullScreen] state to the UI. Called from
     * [setFullScreen] and from onCreate when restoring after rotation.
     * Touch devices get real fullscreen: immersive system UI, and only the
     * player on screen (the landscape layout already lets it fill the pane).
     * The on-video overlay — including the fullscreen toggle — stays visible
     * so the user can exit from the video itself; Back also exits.
     * On TV there is no rotation or system UI to hide — only the library
     * pane hides, as before, and the controls stay usable with the remote.
     */
    private fun updateFullscreenUi() {
        fullscreenIcon.expanded = fullScreen
        fullscreenIcon.invalidateSelf()
        fullscreenButton.contentDescription = if (fullScreen) "Exit fullscreen" else "Fullscreen"
        if (isTv) {
            libraryPane.visibility = if (fullScreen) View.GONE else View.VISIBLE
            return
        }
        if (fullScreen) {
            hideSystemUi()
            headerView.visibility = View.GONE
            libraryPane.visibility = View.GONE
            infoBar.visibility = View.GONE
            quickActions.visibility = View.GONE
            status.visibility = View.GONE
            rootView.setPadding(0, 0, 0, 0)
            playerPane.setPadding(0, 0, 0, 0)
        } else {
            showSystemUi()
            headerView.visibility = if (!isTv && isLandscape) View.GONE else View.VISIBLE
            libraryPane.visibility = View.VISIBLE
            val showBars = selected != null || playingMovie != null
            infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
            quickActions.visibility = if (showBars) View.VISIBLE else View.GONE
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            rootView.setPadding(rootPaddingPx, dp(if (isTv) 10 else 8), rootPaddingPx, dp(if (isTv) 12 else 8))
            playerPane.setPadding(if (isTv || isLandscape) paneGapPx else 0, 0, 0, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        // Immersive sticky: the bars stay hidden and re-hide after a swipe.
        // These flags are deprecated on API 30+ but still honored on every
        // version this app supports (minSdk 23).
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    @Suppress("DEPRECATION")
    private fun showSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun play(channel: Channel) {
        stopWeb()
        selected = channel
        playingMovie = null
        playingMovieUrl = null
        playingMovieHeaders = emptyMap()
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
        setChannelMeta(channel)
        // In phone fullscreen the bars stay hidden (Back exits first);
        // a channel change must not pop them back over the video.
        val showBars = !(fullScreen && !isTv)
        quickActions.visibility = if (showBars) View.VISIBLE else View.GONE
        infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
        playPauseButton.text = "❚❚"
        // A fresh player starts at the stored volume, so a muted session
        // does not survive a channel change — reset the mute state here.
        mutedVolume = false
        muteButton.text = if (store.volume == 0f) "🔇" else "🔊"
        volumeSeekBar.progress = (store.volume * 100).toInt()
        speed = 1f
        playerController?.setSpeed(1f)
        setSpeedText("1x")
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
        // PlayerView (and the WebView) are added to the screen after the
        // overlays; re-raise the border, meta chip and overlay controls.
        bringPlayerOverlaysToFront()
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
        if (playingMovie != null) {
            playerController?.retry()
            return
        }
        val channel = selected ?: return
        if (channel.web) play(channel) else playerController?.retry()
    }

    private fun updateFavoriteButton() {
        val channel = selected
        favoriteGlyph.text =
            if (channel != null && store.isFavorite(channel)) "★" else "☆"
    }

    private fun toggleFavorite() {
        val channel = selected ?: return
        val nowFavorite = store.toggleFavorite(channel)
        updateFavoriteButton()
        populate()
        toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
    }

    /** Favorite toggle from a channel card's heart (any channel, not just
     * the playing one). The list rebuilds, as it does for the tile toggle. */
    private fun toggleFavoriteFor(channel: Channel) {
        val nowFavorite = store.toggleFavorite(channel)
        updateFavoriteButton()
        populate()
        toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
    }

    /** Shows only the controls that apply to the current channel and stream. */
    private fun applyControlVisibility() {
        val webChannel = selected?.web == true
        playPauseButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        volumeSeekBar.visibility = if (webChannel) View.GONE else View.VISIBLE
        muteButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        // Quality lives in the TopBar chip, except on phones in landscape
        // where the TopBar is hidden — there the quick-actions tile shows it.
        val showQuality = !webChannel && videoOptions.size >= 2
        qualityButton.visibility =
            if (showQuality && (isTv || !isLandscape)) View.VISIBLE else View.GONE
        qualityTile.visibility =
            if (showQuality && !isTv && isLandscape) View.VISIBLE else View.GONE
        speedTile.visibility = if (webChannel) View.GONE else View.VISIBLE
        seekRow.visibility = if (webChannel || !seekable) View.GONE else View.VISIBLE
    }

    /** Keeps the TopBar quality chip and the quick-actions quality tile in
     * sync; both open the same dialog. */
    private fun setQualityText(label: String) {
        qualityButton.text = label
        qualityGlyph.text = if (label == "Quality") "Auto" else label
    }

    private fun setSpeedText(label: String) {
        speedGlyph.text = label
    }

    private fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        mutedVolume = false
        store.volume = clamped
        playerController?.setVolume(clamped)
        volumeSeekBar.progress = (clamped * 100).toInt()
        muteButton.text = if (clamped == 0f) "🔇" else "🔊"
    }

    private fun toggleMute() {
        mutedVolume = !mutedVolume
        playerController?.setVolume(if (mutedVolume) 0f else store.volume)
        volumeSeekBar.progress = if (mutedVolume) 0 else (store.volume * 100).toInt()
        muteButton.text = if (mutedVolume) "🔇" else "🔊"
    }

    private fun showQualityDialog() {
        val items = mutableListOf("Auto")
        items += videoOptions.map { it.label }
        val checked = if (selectedVideoIndex in videoOptions.indices) selectedVideoIndex + 1 else 0
        AlertDialog.Builder(this).setTitle("Quality")
            .setSingleChoiceItems(items.toTypedArray(), checked) { dialog, which ->
                if (which == 0) {
                    playerController?.selectTrack(null)
                    setQualityText("Quality")
                } else {
                    val option = videoOptions[which - 1]
                    playerController?.selectTrack(option)
                    setQualityText(option.label)
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
                setSpeedText(labels[which])
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
        // While the Movies browser is open, channel number jumps and
        // up/down channel switching stay off — D-pad navigation belongs to
        // the movie list.
        if (moviesOpen) return super.onKeyDown(keyCode, event)
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
     * so list navigation with the remote keeps working. Focus on an overlay
     * control (play, volume, fullscreen…) keeps normal D-pad navigation
     * instead — only the video surface itself switches channels. */
    private fun channelSwitchKeysActive(): Boolean {
        if (fullScreen) return true
        var view: View? = currentFocus
        while (view != null) {
            if (view === overlayBar || view === metaChip || view === numberOverlay) return false
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
        bringPlayerOverlaysToFront()
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

    /** Search dialog, opened from the TopBar search pill and the footer
     * Search button (the pill is hidden on phones in landscape). */
    private fun openSearch() {
        askText("Search channels", "Channel name") { value ->
            query = value
            page = 0
            populate()
            list.getChildAt(0)?.requestFocus()
        }
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

    /** Silent once-a-day update check: only shows anything when a newer
     * release is actually available. Network/parse failures stay silent. */
    private fun checkForUpdateSilently() {
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

    private fun checkForUpdateManually() {
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

    private fun showUpdateDialog(update: UpdateChecker.Update) {
        AlertDialog.Builder(this)
            .setTitle("App Update")
            .setMessage("Naya version aa gaya hai (${update.tag}). Download kar ke install karna hai?")
            .setPositiveButton("Download") { _, _ -> downloadAndInstallUpdate(update) }
            .setNegativeButton("Baad mein", null)
            .show()
    }

    private fun downloadAndInstallUpdate(update: UpdateChecker.Update) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val progressText = label("Download ho raha hai… 0%", 14, textColor)
        val layout = column().apply {
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(progressBar, LinearLayout.LayoutParams(-1, -2))
            addView(progressText)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update download ho raha hai")
            .setView(layout)
            .setCancelable(false)
            .create()
        updateDownloadDialog = dialog
        dialog.show()
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
            val movie = playingMovie
            val url = playingMovieUrl
            if (movie != null && url != null) {
                playMovieStream(movie, url, playingMovieHeaders, savedMoviePosition)
            } else {
                selected?.let(::play)
            }
        }
    }

    override fun onStop() {
        sleepTimer.cancel()
        // Capture the movie position BEFORE stopPlayback releases the player,
        // so coming back from background resumes the movie where it left off.
        if (playingMovie != null) savedMoviePosition = playerController?.position() ?: 0L
        stopPlayback()
        stoppedForBackground = true
        super.onStop()
    }

    override fun onDestroy() {
        // Never leak the update download dialog across a rotation/destroy.
        updateDownloadDialog?.dismiss()
        updateDownloadDialog = null
        imports.shutdownNow()
        // LogoLoader's pool is shared across activity instances: killing it
        // on a rotation would crash the recreated activity the first time it
        // loads a channel logo — and fullscreen rotates on every toggle.
        if (!isChangingConfigurations) LogoLoader.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Use the system back gesture callback on newer Android versions")
    override fun onBackPressed() {
        if (moviesOpen) {
            if (moviesDetailsPane.visibility == View.VISIBLE) {
                showMoviesList()
            } else {
                closeMovies()
            }
        } else if (fullScreen) {
            setFullScreen(false)
        } else if (selected == null && playingMovie == null) {
            super.onBackPressed()
        } else {
            sleepTimer.cancel()
            stopPlayback()
            selected = null
            playingMovie = null
            playingMovieUrl = null
            playingMovieHeaders = emptyMap()
            quickActions.visibility = View.GONE
            infoBar.visibility = View.GONE
            playPauseButton.text = "▶"
            setChannelMeta(null)
            status.text = ""
            list.getChildAt(0)?.requestFocus()
        }
    }

    // ---- MovieBox movies (v1) ----
    //
    // A full-screen overlay (same Qwen navy-glass look) over the Live TV
    // layout: search → results (poster + name + year + HINDI badge) →
    // details → audio (dub) + quality pickers → Play in the main player.
    // All MovieBox calls run on the `imports` executor; stale results are
    // dropped with a per-request counter, the same pattern as playEmbed.

    private fun buildMoviesView(): LinearLayout {
        val view = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg0, bg1, bg2),
            )
            isClickable = true
            isFocusable = true
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ Back") { closeMovies() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        header.addView(label("🎬 Movies", if (isTv) 26 else 22, textColor).apply {
            setTypeface(null, Typeface.BOLD)
        })
        view.addView(header)

        // ---- Search + results pane ----
        moviesListPane = column()
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        movieSearchInput = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            hint = "Movie ka naam…"
            setHintTextColor(muted2)
            setTextColor(textColor)
            textSize = if (isTv) 17f else 15f
            minimumHeight = dp(if (isTv) 56 else 52)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = glassStateList(28)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    searchMovies()
                    true
                } else {
                    false
                }
            }
        }
        searchRow.addView(movieSearchInput, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(8)
        })
        searchRow.addView(button("Search") { searchMovies() })
        moviesListPane.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        movieStatus = label(
            "Movie ka naam likh kar search karein — Hindi dubbed bhi milti hai.",
            if (isTv) 15 else 13, muted,
        )
        moviesListPane.addView(movieStatus)
        movieProgress = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(cyan)
        }
        moviesListPane.addView(movieProgress, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        movieResults = column()
        movieResultsScroll = ScrollView(this).apply { addView(movieResults) }
        moviesListPane.addView(movieResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(moviesListPane, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- Details pane ----
        moviesDetailsPane = column().apply { visibility = View.GONE }
        val detailsScroll = ScrollView(this)
        val detailsInner = column()
        detailsScroll.addView(detailsInner)
        movieDetailsTitle = TextView(this).apply {
            textSize = if (isTv) 24f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        detailsInner.addView(movieDetailsTitle)
        movieDetailsMeta = label("", if (isTv) 15 else 13, muted)
        detailsInner.addView(movieDetailsMeta)
        movieDetailsDesc = TextView(this).apply {
            textSize = if (isTv) 15f else 14f
            setTextColor(muted)
        }
        detailsInner.addView(movieDetailsDesc, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(12)
        })
        detailsInner.addView(label("Audio", if (isTv) 15 else 13, textColor).apply {
            setTypeface(null, Typeface.BOLD)
        })
        val dubScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        movieDubRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dubScroll.addView(movieDubRow)
        detailsInner.addView(dubScroll, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        detailsInner.addView(label("Quality", if (isTv) 15 else 13, textColor).apply {
            setTypeface(null, Typeface.BOLD)
        })
        val qualityScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        movieQualityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qualityScroll.addView(movieQualityRow)
        detailsInner.addView(qualityScroll, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
        movieDetailsStatus = label("", if (isTv) 15 else 13, muted)
        detailsInner.addView(movieDetailsStatus)
        moviePlayButton = Button(this).apply {
            text = "▶ Play"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 18f else 16f
            minimumHeight = dp(if (isTv) 56 else 52)
            background = gradientPill(28)
            isEnabled = false
            setOnClickListener { playSelectedMovie() }
        }
        detailsInner.addView(moviePlayButton, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
        })
        detailsInner.addView(button("‹ Back to results") { showMoviesList() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        moviesDetailsPane.addView(detailsScroll, LinearLayout.LayoutParams(-1, -1))
        view.addView(moviesDetailsPane, LinearLayout.LayoutParams(-1, 0, 1f))
        return view
    }

    private fun openMovies() {
        moviesOpen = true
        moviesView.visibility = View.VISIBLE
        showMoviesList()
    }

    private fun closeMovies() {
        moviesOpen = false
        moviesView.visibility = View.GONE
        // A movie keeps playing underneath when the browser is closed.
        list.getChildAt(0)?.requestFocus()
    }

    private fun showMoviesList() {
        moviesDetailsPane.visibility = View.GONE
        moviesListPane.visibility = View.VISIBLE
        movieSearchInput.requestFocus()
    }

    private fun showMoviesDetails() {
        moviesListPane.visibility = View.GONE
        moviesDetailsPane.visibility = View.VISIBLE
    }

    private fun searchMovies() {
        val query = movieSearchInput.text.toString().trim()
        if (query.isEmpty()) {
            toast("Movie ka naam likhein.")
            return
        }
        val request = ++movieSearchRequest
        // Hide the keyboard so the results are visible on phones.
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(movieSearchInput.windowToken, 0)
        movieProgress.visibility = View.VISIBLE
        movieStatus.text = "Search ho raha hai…"
        movieResults.removeAllViews()
        imports.execute {
            try {
                val results = movieBox.search(query)
                runOnUiThread {
                    if (isDestroyed || request != movieSearchRequest) return@runOnUiThread
                    movieProgress.visibility = View.GONE
                    if (results.isEmpty()) {
                        movieStatus.text = "Koi movie nahi mili — naam badal kar dobara try karein."
                    } else {
                        movieStatus.text = "${results.size} movies mili."
                        showMovieResults(results)
                    }
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isDestroyed || request != movieSearchRequest) return@runOnUiThread
                    movieProgress.visibility = View.GONE
                    movieStatus.text = "Search nahi ho saka: ${error.message}"
                }
            }
        }
    }

    private fun showMovieResults(movies: List<MovieBox.Movie>) {
        movieResults.removeAllViews()
        for (movie in movies) {
            movieResults.addView(movieRow(movie), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
        movieResults.getChildAt(0)?.requestFocus()
    }

    /** Glass movie card: 2:3 poster (letter tile behind it while it loads),
     * bold title + year, and a cyan HINDI badge for Hindi-dubbed titles. */
    private fun movieRow(movie: MovieBox.Movie): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 96 else 88)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rowBackground(false)
            setOnClickListener { openMovieDetails(movie) }
        }
        val posterWidth = dp(if (isTv) 60 else 56)
        val posterHeight = dp(if (isTv) 90 else 84)
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = movie.title.take(1).uppercase()
            textSize = if (isTv) 22f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (movie.posterUrl.isNotEmpty()) {
            val posterView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tileView.addView(posterView, FrameLayout.LayoutParams(-1, -1))
            LogoLoader.load(this, movie.posterUrl, posterView)
        }
        row.addView(tileView, LinearLayout.LayoutParams(posterWidth, posterHeight))
        val texts = column()
        texts.addView(TextView(this).apply {
            text = movie.title
            textSize = if (isTv) 17f else 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (movie.year.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = movie.year
                textSize = 13f
                setTextColor(muted)
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        if (movie.isHindi) {
            row.addView(TextView(this).apply {
                text = "HINDI"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = shape(cyan, cyan, 8, 0)
            })
        }
        return row
    }

    private fun openMovieDetails(movie: MovieBox.Movie) {
        val request = ++movieDetailsRequest
        currentMovieDetails = null
        currentMovieStreams = emptyList()
        currentPlayTitle = movie.title
        selectedDubId = movie.subjectId
        selectedQuality = 0
        movieDetailsTitle.text = movie.title
        movieDetailsMeta.text = ""
        movieDetailsDesc.text = ""
        movieDubRow.removeAllViews()
        movieQualityRow.removeAllViews()
        moviePlayButton.isEnabled = false
        movieDetailsStatus.text = "Details load ho rahe hain…"
        showMoviesDetails()
        imports.execute {
            try {
                val details = movieBox.details(movie.subjectId)
                runOnUiThread {
                    if (isDestroyed || request != movieDetailsRequest) return@runOnUiThread
                    showMovieDetails(details)
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isDestroyed || request != movieDetailsRequest) return@runOnUiThread
                    movieDetailsStatus.text = "Details nahi mile: ${error.message}"
                }
            }
        }
    }

    private fun showMovieDetails(details: MovieBox.Details) {
        currentMovieDetails = details
        currentPlayTitle = details.movie.title
        selectedDubId = details.movie.subjectId
        movieDetailsTitle.text = details.movie.title
        val metaParts = mutableListOf<String>()
        if (details.movie.year.isNotEmpty()) metaParts += details.movie.year
        if (details.durationSec > 0) metaParts += formatDuration(details.durationSec)
        if (details.rating.isNotEmpty()) metaParts += "★ ${details.rating}"
        if (details.genres.isNotEmpty()) metaParts += details.genres.take(3).joinToString(", ")
        movieDetailsMeta.text = metaParts.joinToString(" · ")
        val descParts = mutableListOf<String>()
        if (details.description.isNotEmpty()) descParts += details.description
        if (details.director.isNotEmpty()) descParts += "Director: ${details.director}"
        if (details.stars.isNotEmpty()) descParts += "Cast: ${details.stars}"
        movieDetailsDesc.text = descParts.joinToString("\n\n")
        rebuildDubChips(details)
        loadMovieStreams()
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    private fun rebuildDubChips(details: MovieBox.Details) {
        movieDubRow.removeAllViews()
        val options = mutableListOf(details.movie.subjectId to "Original")
        for (dub in details.dubs) {
            if (dub.subjectId == details.movie.subjectId) continue
            options += dub.subjectId to dub.language.ifEmpty { dub.label }
        }
        for ((subjectId, label) in options) {
            val chipView = chip(label) {
                if (selectedDubId != subjectId) {
                    selectedDubId = subjectId
                    markChipByTag(movieDubRow, subjectId)
                    loadMovieStreams()
                }
            }
            chipView.tag = subjectId
            chipView.isSelected = subjectId == selectedDubId
            refreshFilterText(chipView)
            movieDubRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(8)
            })
        }
    }

    private fun markChipByTag(row: LinearLayout, tag: Any?) {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child is Button) {
                child.isSelected = child.tag == tag
                refreshFilterText(child)
            }
        }
    }

    private fun loadMovieStreams() {
        val subjectId = selectedDubId ?: return
        val request = ++movieStreamsRequest
        currentMovieStreams = emptyList()
        selectedQuality = 0
        movieQualityRow.removeAllViews()
        moviePlayButton.isEnabled = false
        movieDetailsStatus.text = "Streams load ho rahe hain…"
        imports.execute {
            try {
                // A dub is a different subject with its own files — reuse the
                // details we already have when the original is selected.
                val details = if (subjectId == currentMovieDetails?.movie?.subjectId) {
                    currentMovieDetails!!
                } else {
                    movieBox.details(subjectId)
                }
                currentPlayTitle = details.movie.title
                val streams = movieBox.streams(details)
                runOnUiThread {
                    if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                    currentMovieStreams = streams
                    showMovieStreams(streams)
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isDestroyed || request != movieStreamsRequest) return@runOnUiThread
                    movieDetailsStatus.text = "Streams nahi mile: ${error.message}"
                }
            }
        }
    }

    private fun showMovieStreams(streams: List<MovieBox.MovieStream>) {
        val resolutions = streams.map { it.resolution }.filter { it > 0 }
            .distinct().sortedDescending()
        if (resolutions.isEmpty()) {
            // Streams exist but none reports a resolution — one Auto entry
            // that plays the best (first) stream.
            selectedQuality = 0
            val chipView = chip("Auto") {
                selectedQuality = 0
                markChipByTag(movieQualityRow, 0)
            }
            chipView.tag = 0
            chipView.isSelected = true
            refreshFilterText(chipView)
            movieQualityRow.addView(chipView)
            movieDetailsStatus.text = "Stream ready hai — Play dabayein."
            moviePlayButton.isEnabled = true
            return
        }
        // Default: the highest available quality.
        selectedQuality = resolutions.first()
        for (res in resolutions) {
            val chipView = chip("${res}p") {
                selectedQuality = res
                markChipByTag(movieQualityRow, res)
            }
            chipView.tag = res
            chipView.isSelected = res == selectedQuality
            refreshFilterText(chipView)
            movieQualityRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(8)
            })
        }
        val best = streams.first()
        val sizeText = if (best.sizeBytes > 0) " · ${formatSize(best.sizeBytes)}" else ""
        movieDetailsStatus.text = "${resolutions.size} qualities available$sizeText — Play dabayein."
        moviePlayButton.isEnabled = true
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 ->
                String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 ->
                String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
            bytes > 0 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> ""
        }
    }

    private fun playSelectedMovie() {
        val details = currentMovieDetails ?: return
        val streams = currentMovieStreams
        if (streams.isEmpty()) return
        val stream = streams.firstOrNull { it.resolution == selectedQuality } ?: streams.first()
        closeMovies()
        playMovieStream(details.movie.copy(title = currentPlayTitle), stream.url, stream.headers)
    }

    /**
     * Plays a resolved movie file in the main player, with the same setup
     * as [play] but without touching the channel store: no recent entry, no
     * last-channel, no favorite — the signed URL must not outlive this
     * session. Seek/pause/volume/speed/fullscreen all work through the
     * existing controls; movies are seekable, so the seek bar appears.
     */
    private fun playMovieStream(
        movie: MovieBox.Movie,
        url: String,
        headers: Map<String, String>,
        positionMs: Long = 0L,
    ) {
        stopWeb()
        selected = null
        playingMovie = movie
        playingMovieUrl = url
        playingMovieHeaders = headers
        setMovieMeta(movie)
        val showBars = !(fullScreen && !isTv)
        quickActions.visibility = if (showBars) View.VISIBLE else View.GONE
        infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
        playPauseButton.text = "❚❚"
        mutedVolume = false
        muteButton.text = if (store.volume == 0f) "🔇" else "🔊"
        volumeSeekBar.progress = (store.volume * 100).toInt()
        speed = 1f
        playerController?.setSpeed(1f)
        setSpeedText("1x")
        videoOptions = emptyList()
        selectedVideoIndex = -1
        seekable = false
        stopSeekUpdates()
        updateFavoriteButton()
        applyControlVisibility()
        markPlayingRow()
        status.text = "Connecting to stream…"
        playerController?.play(url, headers)
        if (positionMs > 0) playerController?.seekTo(positionMs)
        bringPlayerOverlaysToFront()
    }

    private fun setMovieMeta(movie: MovieBox.Movie) {
        metaName.text = movie.title
        infoName.text = movie.title
        metaCategory.text = "Movie"
        metaCategory.visibility = View.VISIBLE
        metaLiveBadge.visibility = View.GONE
        infoLiveBadge.visibility = View.GONE
    }

    private fun restoreMoviePlayback(saved: Bundle?) {
        if (saved == null) return
        val url = saved.getString(STATE_MOVIE_URL) ?: return
        val title = saved.getString(STATE_MOVIE_TITLE).orEmpty()
        val position = saved.getLong(STATE_MOVIE_POS, 0L)
        val headers = saved.getStringArrayList(STATE_MOVIE_HEADERS).orEmpty()
            .mapNotNull { raw ->
                val parts = raw.split("\u0001")
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
        playMovieStream(MovieBox.Movie(title = title), url, headers, position)
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
        setTextColor(textColor)
        textSize = if (isTv) 15f else 14f
        minimumWidth = 0
        // 48dp touch target on phones/tablets; generous on TV too.
        minimumHeight = dp(if (isTv) 46 else 48)
        val sidePadding = dp(if (isTv) 14 else 10)
        setPadding(sidePadding, dp(8), sidePadding, dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                glassShape(10, cyan, if (isTv) 3 else 2))
            // Pressed state: without it taps on a phone gave no feedback.
            addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 10))
            addState(intArrayOf(), glassShape(10))
        }
        setOnClickListener { action() }
    }

    /** Pill-shaped filter chip with a visible selected state (cyan→blue
     * gradient pill, Qwen tabs). */
    private fun chip(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(muted)
        textSize = if (isTv) 15f else 14f
        minimumWidth = 0
        minimumHeight = dp(if (isTv) 44 else 48)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), gradientPill(24))
            addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 24))
            addState(intArrayOf(), glassShape(24))
        }
        setOnFocusChangeListener { _, _ -> refreshFilterText(this) }
        setOnClickListener { action() }
    }

    /** White text on the active (gradient) or focused filter, muted otherwise. */
    private fun refreshFilterText(view: Button) {
        view.setTextColor(if (view.isSelected || view.isFocused) Color.WHITE else muted)
    }

    private fun markActiveFilter() {
        for ((name, chipView) in filterChips) {
            chipView.isSelected = name == filter
            refreshFilterText(chipView)
        }
    }

    /** Channel-card background: glass, cyan border while this row is playing. */
    private fun rowBackground(playing: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), glassShape(22, cyan, 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 22))
        addState(intArrayOf(), if (playing) glassShape(22, cyan, 2) else glassShape(22))
    }

    /** Highlights the playing row (rows carry the channel key as their tag)
     * and toggles its cyan playing indicator. */
    private fun markPlayingRow() {
        val playingKey = selected?.key
        for (index in 0 until list.childCount) {
            val child = list.getChildAt(index)
            if (child is LinearLayout) {
                val key = child.tag as? String ?: continue
                val playing = key == playingKey
                child.background = rowBackground(playing)
                child.findViewWithTag<View>("eq")?.visibility =
                    if (playing) View.VISIBLE else View.GONE
            }
        }
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

    // ---- Qwen HarmonyOS look helpers ----

    /** Shows the playing channel in the on-video meta chip and the info bar. */
    private fun setChannelMeta(channel: Channel?) {
        val name = channel?.name ?: "Select a channel"
        metaName.text = name
        infoName.text = name
        val category = channel?.group.orEmpty()
        metaCategory.text = category
        metaCategory.visibility = if (category.isEmpty()) View.GONE else View.VISIBLE
        metaLiveBadge.visibility = View.VISIBLE
        infoLiveBadge.visibility = View.VISIBLE
    }

    /** PlayerView/WebView are added to [screen] after the overlays were built;
     * re-raise the border, meta chip, overlay controls and number popup above
     * them. Called at the end of [play] and after adding a WebView. */
    private fun bringPlayerOverlaysToFront() {
        screenBorder.bringToFront()
        metaChip.bringToFront()
        overlayBar.bringToFront()
        numberOverlay.bringToFront()
        buffering.bringToFront()
    }

    /** Glass fill: vertical gradient GlassHi → Glass + 1dp stroke. */
    private fun glassShape(radiusDp: Int = 22, strokeColor: Int = glassStroke, strokeDp: Int = 1) =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(glassHi, glass),
        ).apply {
            setStroke(dp(strokeDp), strokeColor)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** Cyan → blue horizontal gradient pill (active nav item / chip). */
    private fun gradientPill(radiusDp: Int = 25) =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(cyan, blue),
        ).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }

    /** Glass background with a cyan focus ring (search pill). */
    private fun glassStateList(radiusDp: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), glassShape(radiusDp, cyan, 2))
        addState(intArrayOf(android.R.attr.state_pressed), glassShape(radiusDp, strokeHi, 1))
        addState(intArrayOf(), glassShape(radiusDp))
    }

    /** TV sidebar item: transparent at rest, gradient pill when selected
     * (active filter), cyan ring when D-pad focused. */
    private fun navItemBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_selected), gradientPill())
        addState(intArrayOf(android.R.attr.state_focused), glassShape(25, cyan, 2))
        addState(intArrayOf(android.R.attr.state_pressed), glassShape(25, strokeHi, 1))
        addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 25, 0))
    }

    private fun navItem(labelText: String, action: () -> Unit) = Button(this).apply {
        text = labelText
        isAllCaps = false
        setTextColor(muted)
        textSize = 16f
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumWidth = 0
        minimumHeight = dp(48)
        setPadding(dp(19), dp(12), dp(19), dp(12))
        background = navItemBackground()
        setOnFocusChangeListener { _, _ -> refreshFilterText(this) }
        setOnClickListener { action() }
    }

    /** Qwen "Quick Actions" glass tile: a glyph over a small label. Returns
     * the tile and its glyph view, so favorite/quality/speed can update the
     * glyph when their state changes. */
    private fun actionTile(glyph: String, labelText: String, action: () -> Unit): Pair<LinearLayout, TextView> {
        val glyphView = TextView(this).apply {
            text = glyph
            textSize = if (isTv) 22f else 20f
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }
        val labelView = TextView(this).apply {
            text = labelText
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        val tile = column().apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 64 else 60)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glassShape(18, cyan, 2))
                addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 18))
                addState(intArrayOf(), glassShape(18))
            }
            addView(glyphView)
            addView(labelView)
            setOnClickListener { action() }
        }
        return tile to glyphView
    }

    /** Round button inside the on-video overlay pill: transparent at rest
     * (the pill is the dark background), cyan ring on D-pad focus. */
    private fun overlayButtonBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(0xcc16224f.toInt(), cyan, 24, 2))
        addState(intArrayOf(android.R.attr.state_pressed), shape(0xcc2a3a86.toInt(), cyan, 24, 1))
        addState(intArrayOf(), shape(Color.TRANSPARENT, Color.TRANSPARENT, 24, 0))
    }

    private fun overlayButton(glyph: String, action: () -> Unit) = Button(this).apply {
        text = glyph
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = if (isTv) 20f else 18f
        gravity = Gravity.CENTER
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = overlayButtonBackground()
        setOnClickListener { action() }
    }

    private fun overlayParams() = LinearLayout.LayoutParams(
        dp(if (isTv) 48 else 44), dp(if (isTv) 48 else 44),
    ).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    /** Red LIVE badge (Qwen). Every channel here is live, so the badge shows
     * on every card, on the meta chip and in the info bar. */
    private fun liveBadge() = TextView(this).apply {
        text = "LIVE"
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(4), dp(11), dp(4))
        background = shape(liveRed, liveRed, 8, 0)
    }

    /** Qwen logo mark: two overlapping gradient dots (cyan/blue + magenta/purple). */
    private fun logoMark(): View {
        val size = dp(44)
        val dot = dp(26)
        fun dotDrawable(from: Int, to: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(from, to)
            orientation = GradientDrawable.Orientation.TL_BR
        }
        return FrameLayout(this).apply {
            addView(View(this@MainActivity).apply {
                background = dotDrawable(cyan, blue)
            }, FrameLayout.LayoutParams(dot, dot).apply {
                leftMargin = dp(3)
                topMargin = dp(14)
            })
            addView(View(this@MainActivity).apply {
                background = dotDrawable(magenta, purple)
            }, FrameLayout.LayoutParams(dot, dot).apply {
                leftMargin = dp(16)
                topMargin = dp(4)
            })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    /** YouTube-style fullscreen toggle icon, drawn in code (no vector asset
     * needed): four corner brackets pointing out when windowed (expand) and
     * in when fullscreen (collapse). [expanded] is set from
     * [updateFullscreenUi] and the drawable invalidated. */
    private inner class FullscreenIconDrawable(var expanded: Boolean) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            if (size <= 0f) return
            val arm = size * 0.32f
            val gap = size * 0.10f
            val left = b.left + gap
            val top = b.top + gap
            val right = b.right - gap
            val bottom = b.bottom - gap
            if (!expanded) {
                // Expand: vertices at the outer corners, arms toward center.
                canvas.drawLine(left, top + arm, left, top, paint)
                canvas.drawLine(left, top, left + arm, top, paint)
                canvas.drawLine(right - arm, top, right, top, paint)
                canvas.drawLine(right, top, right, top + arm, paint)
                canvas.drawLine(left, bottom - arm, left, bottom, paint)
                canvas.drawLine(left, bottom, left + arm, bottom, paint)
                canvas.drawLine(right - arm, bottom, right, bottom, paint)
                canvas.drawLine(right, bottom, right, bottom - arm, paint)
            } else {
                // Collapse: vertices inset, arms pointing outward.
                val ix = left + arm
                val iy = top + arm
                val ax = right - arm
                val ay = bottom - arm
                canvas.drawLine(ix, iy, ix, iy - arm, paint)
                canvas.drawLine(ix, iy, ix - arm, iy, paint)
                canvas.drawLine(ax, iy, ax, iy - arm, paint)
                canvas.drawLine(ax, iy, ax + arm, iy, paint)
                canvas.drawLine(ix, ay, ix, ay + arm, paint)
                canvas.drawLine(ix, ay, ix - arm, ay, paint)
                canvas.drawLine(ax, ay, ax, ay + arm, paint)
                canvas.drawLine(ax, ay, ax + arm, ay, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = dp(28)

        override fun getIntrinsicHeight(): Int = dp(28)
    }

    companion object {
        private const val PAGE_SIZE = 60
        private const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
        private const val STATE_FULLSCREEN = "fullscreen"
        private const val STATE_MOVIE_URL = "movie_url"
        private const val STATE_MOVIE_TITLE = "movie_title"
        private const val STATE_MOVIE_POS = "movie_pos"
        private const val STATE_MOVIE_HEADERS = "movie_headers"
    }
}
