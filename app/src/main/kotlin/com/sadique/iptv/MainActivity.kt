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
    // Group-titles that have their own category. A channel whose
    // group matches none of these falls under General (the catch-all), so
    // no channel is reachable only through "All". "cricket" and
    // "religious" stay as legacy matches for imported playlists.
    private val categoryGroups = setOf(
        "news", "sports", "cricket", "islamic", "religious",
        "kids", "animation",
    )
    private val accent = cyan // focus strokes, status text, list counts
    private val imports = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: ChannelStore
    private lateinit var playlistStore: PlaylistStore
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
    // On-video corner buttons (the tiles under the player are gone — the
    // user asked for the buttons ON the screen itself): Favorite
    // top-right, Retry bottom-left, Quality bottom-right. Speed lives in
    // the overlay pill (movies only — channels never need it).
    private lateinit var favButton: Button
    private lateinit var retryButton: Button
    private lateinit var qualityButton: Button
    private lateinit var speedButton: Button
    private lateinit var seekRow: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var seekLabel: TextView
    // YouTube-style on-video controls (touch only): tapping the video shows
    // the overlay pill + seek bar, and they auto-hide after 3 seconds. On TV
    // the controls always stay visible (D-pad navigation needs them).
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { hidePlayerControls() }
    private lateinit var playPauseButton: Button
    private lateinit var volumeSeekBar: SeekBar
    private lateinit var muteButton: Button
    private lateinit var fullscreenButton: ImageButton
    private lateinit var fullscreenIcon: FullscreenIconDrawable
    private lateinit var catalogTitle: TextView
    // Shown under the catalog title while a search query is active: the
    // query text + a button that clears it and restores the full list.
    private lateinit var searchClearRow: LinearLayout
    private lateinit var searchClearLabel: TextView
    // Search bar at the top of the browse pane, as wide as the list —
    // the channel search lives where the channels are (the TopBar pill
    // stays as the second way in).
    private lateinit var paneSearchBar: LinearLayout
    private lateinit var paneSearchLabel: TextView
    private var mutedVolume = false
    private var playerController: PlayerController? = null
    private var web: WebView? = null
    // Transparent tap catcher above an iframe embed (below the app's own
    // overlay controls): taps otherwise reach YouTube, which pops its
    // "more videos" overlay over the channel. Direct web players have no
    // catcher — their taps must reach the page (Cloudflare check).
    private var webTapCatcher: View? = null
    private var selected: Channel? = null
    private var imported = emptyList<Channel>()
    private var filter = "All"
        set(value) {
            field = value
            // Keep the categories pane / chip strip in sync when the filter
            // changes from a dialog or a playlist action instead of a tap.
            markActiveFilter()
        }
    private var query = ""
    // TV browse levels: the left pane shows either the categories or the
    // channels of the picked category — never both (the old sidebar is
    // gone). Touch devices have no levels; they always stay on CHANNELS.
    private enum class BrowseLevel { CATEGORIES, CHANNELS }
    private var browseLevel = BrowseLevel.CHANNELS
    private lateinit var categoryListScroll: ScrollView // TV only
    // True once a playing movie has ended (it still counts as the
    // current movie for Retry, but the browse pane comes back).
    private var movieEnded = false
    private var verifiedChannels = emptyList<Channel>()
    private var playbackRequest = 0
    // YouTube live (yt-live:) tracking: the token to re-resolve when the
    // stream ends or restarts, and the video ID currently in the embed
    // (so a re-resolve that returns the same ID does not reload).
    private var playingYtLive: String? = null
    private var playingEmbedId: String? = null
    // Main-frame navigation is blocked for embeds — except these hosts,
    // and only while a direct web: player is showing (playsza + its
    // Cloudflare check need real top-level navigation).
    private var allowMainFrameHosts: Set<String> = emptySet()
    private val ytLivePollRunnable = Runnable { pollYtLive() }
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
    private lateinit var movieStore: MovieStore
    // The Movies/Dramas overlays build on first open, not at startup —
    // they are big view trees and the app opened on a black screen while
    // they inflated. Nothing touches them before the first open.
    private lateinit var contentFrame: FrameLayout
    private var moviesViewBuilt = false
    private var dramasViewBuilt = false
    private lateinit var moviesView: LinearLayout
    private lateinit var moviesListPane: LinearLayout
    private lateinit var moviesDetailsPane: LinearLayout
    private lateinit var movieSearchInput: EditText
    // Home chip in the Movies quick row — the focus fallback when the
    // section opens with no rows (the search box is NOT the landing:
    // focusing it pops the keyboard and D-pad up/down can die on it).
    private lateinit var movieHomeChip: Button
    private lateinit var movieStatus: TextView
    // Now-playing bar at the top of the Movies section: shown when a movie
    // is already playing, with a shortcut back to the player.
    private lateinit var movieNowPlayingBar: LinearLayout
    private lateinit var movieNowPlayingLabel: TextView
    private lateinit var movieProgress: ProgressBar
    private lateinit var movieResults: LinearLayout
    private lateinit var movieResultsScroll: ScrollView
    private lateinit var movieDetailsTitle: TextView
    private lateinit var movieDetailsMeta: TextView
    private lateinit var movieDetailsDesc: TextView
    private lateinit var movieDubRow: LinearLayout
    private lateinit var movieQualityRow: LinearLayout
    private lateinit var moviePlayButton: Button
    private lateinit var movieFavButton: Button
    private lateinit var movieDetailsStatus: TextView
    private var moviesOpen = false
    // True while search results are on screen; false while the Movies home
    // (Continue Watching + Fav Movies) is showing.
    private var movieResultsAreSearch = false
    private var movieResumeRequest = 0
    // seekUpdater ticks every 500ms; every 20th tick (~10s) the playing
    // movie's position is saved to Continue Watching.
    private var movieProgressTick = 0
    private var playingMovie: MovieBox.Movie? = null
    private var playingMovieUrl: String? = null
    private var playingMovieHeaders: Map<String, String> = emptyMap()
    private var savedMoviePosition = 0L
    private var movieSearchRequest = 0
    // Index of the movie row the details pane opened from — Back from
    // details returns focus to it instead of the search box (-1: none).
    private var moviesReturnRow = -1
    private var movieDetailsRequest = 0
    private var movieStreamsRequest = 0
    private var currentMovieDetails: MovieBox.Details? = null
    private var currentMovieStreams: List<MovieBox.MovieStream> = emptyList()
    private var currentPlayTitle = ""
    private var selectedDubId: String? = null
    private var selectedQuality = 0

    // Geo Dramas (v17): Har Pal Geo dramas from YouTube (GeoDramas client).
    // Same full-screen overlay pattern as Movies: drama list → episode
    // list → Play in the main player (WebView embed, loop off). Episodes
    // never touch the channel store — no recent entry, no last-channel.
    private lateinit var geoDramas: GeoDramas
    private lateinit var dramasView: LinearLayout
    private lateinit var dramasListPane: LinearLayout
    private lateinit var dramasEpisodesPane: LinearLayout
    private lateinit var dramaAvatar: ImageView
    private lateinit var dramaStatus: TextView
    private lateinit var dramaProgress: ProgressBar
    private lateinit var dramaResults: LinearLayout
    private lateinit var dramaResultsScroll: ScrollView
    private lateinit var dramaNowPlayingBar: LinearLayout
    private lateinit var dramaNowPlayingLabel: TextView
    private lateinit var dramaEpisodesTitle: TextView
    private lateinit var dramaEpisodesMeta: TextView
    private lateinit var dramaEpisodesStatus: TextView
    private lateinit var dramaEpisodesList: LinearLayout
    private lateinit var dramaEpisodesScroll: ScrollView
    private var dramasOpen = false
    private var playingDrama: GeoDramas.Episode? = null
    private var playingDramaTitle = ""
    private var dramaListRequest = 0
    private var dramaEpisodesRequest = 0
    private var dramaDatesRequest = 0
    private var currentDramas: List<GeoDramas.Drama> = emptyList()
    private var dramasLoadedAt = 0L
    // The latest-episode date refresh runs here — NOT on `imports` — so a
    // long background re-sort never queues behind (or blocks) a user's
    // episode tap or movie search.
    private val dramaDates = Executors.newSingleThreadExecutor()

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
                // Save the playing movie's position every ~10 seconds so
                // Continue Watching stays fresh without touching the player
                // thread — the store write itself runs on `imports`.
                if (playingMovie != null) {
                    movieProgressTick++
                    if (movieProgressTick >= MOVIE_PROGRESS_SAVE_TICKS) {
                        movieProgressTick = 0
                        saveMovieProgress(async = true)
                    }
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
        // TV overscan: cheap TVs crop a few percent off every edge, and
        // the list was visibly cut at the sides — 32dp keeps the content
        // inside the safe area.
        val rootPaddingDp = if (isTv) 32 else 12
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
        // TopBar (Qwen): logo mark + IPTV title + glass search pill + the
        // round action buttons (TV). Hidden on phones in landscape
        // (vertical space is tight there; the footer buttons cover it) —
        // the playing card already shows what is on.
        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!isTv && isLandscape) visibility = View.GONE
        }
        // The app's real launcher icon (manifest: android:icon) at the
        // top-left corner, next to the IPTV title.
        headerView.addView(ImageView(this).apply {
            setImageResource(R.drawable.iptv_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "IPTV"
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        headerView.addView(label("IPTV", if (isTv) 28 else 22, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.03f
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
        if (isTv) {
            // Small round action buttons (TV): Playlists, Import M3U,
            // Xtream, Update. They used to sit under the channel list
            // and eat its height; the search pill already lives up here,
            // so the actions join it and the space below stays free.
            // (No Search button — the pill IS the search bar.)
            val topActions = listOf(
                Triple("📋", "Playlists") { showPlaylists() },
                Triple("⬇", "Import M3U") { choosePlaylist() },
                Triple("📡", "Xtream") { askXtream() },
                Triple("⬆", "Update") { checkForUpdateManually() },
            )
            for ((glyph, description, action) in topActions) {
                headerView.addView(topRoundButton(glyph, description, action),
                    LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                        marginEnd = dp(8)
                    })
            }
        }
        rootView.addView(headerView)
        val split = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        rootView.addView(split, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = column().also { libraryPane = it }
        playerPane = column()

        // Search bar at the top of the browse pane, as wide as the list —
        // the channel search lives where the channels are. It sits above
        // both browse levels (categories + channels) and shows the active
        // query while one is running (see populate).
        paneSearchBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 52 else 48)
            background = glassStateList(24)
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { openSearch() }
        }
        paneSearchBar.addView(label("🔍", if (isTv) 17 else 15, muted).apply {
            setPadding(0, 0, dp(10), 0)
        })
        paneSearchLabel = label("Search channels…", if (isTv) 15 else 14, muted2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        paneSearchBar.addView(paneSearchLabel, LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(paneSearchBar, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(8)
        })

        // Categories follow the M3U group-titles — only five: News,
        // Sports, Islamic, Kids, General. Everything that is not news,
        // sports, Islamic or kids lives under General (the catch-all),
        // so the categories list stays short.
        val filterNames = listOf("All", "Favs", "Recent", "News", "Sports", "Islamic", "Kids", "General", "M3U")
        val filterLabels = mapOf("Favs" to "♥ Favorites", "Recent" to "◷ Recent")
        val chipAction: (String) -> Unit = { name ->
            filter = name
            populate()
            // TV: a category pick swaps the browse pane to its channels.
            // (No-op on touch — the chip strip and the list share the pane.)
            setBrowseLevel(BrowseLevel.CHANNELS)
        }
        if (isTv) {
            // TV: categories and channels share ONE browse pane (the old
            // 210dp sidebar is gone, so the player gets its width).
            // Categories level: the real channel categories + Movies +
            // Geo Dramas. Picking a category swaps the same pane to its
            // channels; Back returns to the categories.
            val categoryItems = column()
            for (name in filterNames) {
                val item = navItem(filterLabels[name] ?: name) { chipAction(name) }
                filterChips[name] = item
                categoryItems.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(4)
                })
            }
            // Movies live outside the channel filters — a plain nav item
            // (not in filterChips, so it never becomes the active filter).
            categoryItems.addView(navItem("🎬 Movies") { openMovies() },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                })
            // Geo Dramas live outside the channel filters too.
            categoryItems.addView(navItem("📺 Geo Dramas") { openDramas() },
                LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = dp(4)
                })
            categoryListScroll = ScrollView(this).apply {
                addView(categoryItems)
                // Starts at the channels level (All) — the familiar view.
                visibility = View.GONE
            }
            left.addView(categoryListScroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
            // Touch: one horizontally scrolling chip strip (the categories
            // pane is TV-only). The old three stretched rows looked odd on narrow
            // screens, and the strip scrolls naturally by swipe.
            val chipRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            for (name in filterNames) {
                val chipView = chip(name) { chipAction(name) }
                filterChips[name] = chipView
                chipRow.addView(chipView, LinearLayout.LayoutParams(-2, -2).apply {
                    marginEnd = dp(10)
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
        // Search clear row (task 2): visible only while a search query is
        // active, so the user always has a way back to the full list — even
        // when the search found nothing. It lives OUTSIDE `list` on purpose:
        // list children are exactly the channel rows (index = currentMatches
        // index), and an extra child there would break focusChannel math.
        searchClearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        searchClearLabel = TextView(this).apply {
            textSize = if (isTv) 14f else 13f
            setTextColor(muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        searchClearRow.addView(searchClearLabel, LinearLayout.LayoutParams(0, -2, 1f))
        searchClearRow.addView(button("✕ Saaf karein") { clearSearch() },
            LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = dp(8)
            })
        left.addView(searchClearRow, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(4)
        })
        list = column()
        listScroll = ScrollView(this).apply { addView(list) }
        left.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (!isTv) {
            // Touch: the action buttons live under the list (the narrow
            // top bar has no room for them). On TV they are round buttons
            // in the TopBar instead, so the list gets the full height.
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
            // Touch devices have no categories pane, so Movies gets a footer
            // button next to Update (on TV it is a category item instead).
            val updateRow = LinearLayout(this)
            left.addView(updateRow, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            })
            updateRow.addView(button("⬆ Update", ::checkForUpdateManually),
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginEnd = dp(4)
                })
            updateRow.addView(button("🎬 Movies") { openMovies() },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = dp(4)
                })
        }
        if (!isTv) {
            // Touch devices have no categories pane, so Geo Dramas gets its own
            // footer row under Update/Movies (on TV it is a category item).
            val dramasRow = LinearLayout(this)
            left.addView(dramasRow, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(6)
            })
            dramasRow.addView(button("📺 Geo Dramas") { openDramas() },
                LinearLayout.LayoutParams(-1, -2))
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
                // Slightly stronger ring than the generic stroke so the
                // video card reads as a raised panel.
                setStroke(dp(1), 0x6696acff.toInt())
                cornerRadius = dp(24).toFloat()
            }
        }
        screen.addView(screenBorder, FrameLayout.LayoutParams(-1, -1))
        metaChip = column().apply {
            background = shape(overlayDark, 0x1affffff.toInt(), 18, 1)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            // TV: no channel name on the video at all — the bottom-left
            // chip ran into the overlay pill and the seek bar. The name
            // already shows in the info bar under the player. Phones keep
            // the chip at the top, where nothing overlaps it.
            if (isTv) visibility = View.GONE
        }
        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metaRow.addView(liveBadge().also { metaLiveBadge = it })
        metaName = TextView(this).apply {
            text = "Select a channel"
            // Mobile par channel naam chhota (13sp) — pehle 16sp bohat bara
            // lagta tha; TV par 18sp waisa hi hai.
            textSize = if (isTv) 18f else 13f
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
            setTextColor(muted2)
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
            elevation = dp(6).toFloat()
        }
        playPauseButton = overlayButton("▶") {
            playerController?.togglePlayPause()
            showPlayerControls()
        }
        playPauseButton.id = View.generateViewId()
        val nextButton = overlayButton("⏭") {
            stepChannel(1)
            showPlayerControls()
        }
        nextButton.id = View.generateViewId()
        volumeSeekBar = SeekBar(this).apply {
            max = 100
            progress = (store.volume * 100).toInt()
            id = View.generateViewId()
            progressTintList = ColorStateList.valueOf(cyan)
            // Custom round thumb (white dot + cyan ring); the tint is
            // cleared so it does not wash out the ring.
            thumbTintList = null
            thumb = seekThumb()
            progressBackgroundTintList = ColorStateList.valueOf(0x38ffffff.toInt())
            isFocusable = true
            setOnFocusChangeListener { _, hasFocus ->
                // D-pad focus must be visible on TV: the bar brightens.
                animateFocusScale(this, hasFocus)
                progressTintList = ColorStateList.valueOf(if (hasFocus) Color.WHITE else cyan)
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) setVolume(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    scheduleHideControls()
                }
            })
        }
        muteButton = overlayButton(if (store.volume == 0f) "🔇" else "🔊") {
            toggleMute()
            showPlayerControls()
        }
        muteButton.id = View.generateViewId()
        // Playback speed in the pill (movies only — the old tile under the
        // player is gone). Narrow text, so it gets its own sizing instead
        // of the fixed 48dp overlay slot ("1.25x" would clip in it).
        speedButton = Button(this).apply {
            text = "1x"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            id = View.generateViewId()
            minimumWidth = dp(48)
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            background = overlayButtonBackground()
            visibility = View.GONE
            setOnClickListener {
                showSpeedDialog()
                showPlayerControls()
            }
        }
        fullscreenIcon = FullscreenIconDrawable(expanded = false)
        fullscreenButton = ImageButton(this).apply {
            setImageDrawable(fullscreenIcon)
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Fullscreen"
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = overlayButtonBackground()
            setOnClickListener {
                setFullScreen(!fullScreen)
                showPlayerControls()
            }
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
        overlayBar.addView(speedButton, LinearLayout.LayoutParams(-2, dp(if (isTv) 48 else 44)).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
            gravity = Gravity.CENTER_VERTICAL
        })
        overlayBar.addView(fullscreenButton, overlayParams())
        screen.addView(overlayBar, FrameLayout.LayoutParams(
            -2, -2, Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = dp(if (isTv) 20 else 12)
            bottomMargin = dp(if (isTv) 20 else 12)
        })
        // Seek row ON the video, YouTube-style: it floats just above the
        // overlay pill at the bottom of the screen and only appears for
        // seekable playback (movies) — live channels never show it. On
        // touch devices it hides together with the pill after 3 seconds;
        // in fullscreen it is on the video too, so it stays reachable.
        seekRow = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
            background = shape(overlayDark, 0x1affffff.toInt(), 24, 1)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            elevation = dp(6).toFloat()
        }
        seekBar = SeekBar(this).apply {
            isFocusable = true
            id = View.generateViewId()
            setOnFocusChangeListener { _, hasFocus ->
                // D-pad focus must be visible on TV: the bar brightens.
                animateFocusScale(this, hasFocus)
                progressTintList = ColorStateList.valueOf(if (hasFocus) Color.WHITE else cyan)
            }
            // A taller seek bar is easier to drag by touch.
            if (!isTv) minimumHeight = dp(48)
            progressTintList = ColorStateList.valueOf(cyan)
            // Custom round thumb (white dot + cyan ring); the tint is
            // cleared so it does not wash out the ring.
            thumbTintList = null
            thumb = seekThumb()
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
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    scrubbing = false
                    scheduleHideControls()
                }
            })
        }
        seekLabel = label("0:00 / 0:00", if (isTv) 14 else 13, muted)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f))
        seekRow.addView(seekLabel, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(8)
        })
        // The overlay pill reaches ~80dp (TV) / ~68dp (touch) up from the
        // bottom edge; the seek row floats above it with clearance.
        screen.addView(seekRow, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            marginStart = dp(if (isTv) 20 else 12)
            marginEnd = dp(if (isTv) 20 else 12)
            bottomMargin = dp(if (isTv) 92 else 72)
        })
        // On-video corner buttons (the tiles under the player are gone):
        // Favorite top-right, Retry bottom-left, Quality bottom-right.
        // They sit ON the video, so they stay reachable in fullscreen too.
        // Quality is a text pill ("720p") — a round glyph button would
        // clip the label. All three start hidden; applyControlVisibility
        // owns them (Favorite/Retry while a channel plays, Quality for
        // multi-track streams).
        val cornerMargin = dp(if (isTv) 20 else 12)
        favButton = Button(this).apply {
            text = "☆"
            isAllCaps = false
            contentDescription = "Favorite"
            setTextColor(Color.WHITE)
            textSize = if (isTv) 22f else 20f
            gravity = Gravity.CENTER
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 2))
                addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 24))
                addState(intArrayOf(), glassShape(24))
            }
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { toggleFavorite() }
        }
        screen.addView(favButton, FrameLayout.LayoutParams(
            dp(if (isTv) 48 else 44), dp(if (isTv) 48 else 44),
            Gravity.TOP or Gravity.END,
        ).apply {
            marginEnd = cornerMargin
            topMargin = cornerMargin
        })
        retryButton = Button(this).apply {
            text = "↻"
            isAllCaps = false
            contentDescription = "Retry"
            setTextColor(Color.WHITE)
            textSize = if (isTv) 22f else 20f
            gravity = Gravity.CENTER
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), glassShape(24, cyan, 2))
                addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, 24))
                addState(intArrayOf(), glassShape(24))
            }
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { retryPlayback() }
        }
        screen.addView(retryButton, FrameLayout.LayoutParams(
            dp(if (isTv) 48 else 44), dp(if (isTv) 48 else 44),
            Gravity.BOTTOM or Gravity.START,
        ).apply {
            marginStart = cornerMargin
            bottomMargin = cornerMargin
        })
        qualityButton = Button(this).apply {
            text = "Quality"
            isAllCaps = false
            contentDescription = "Quality"
            setTextColor(cyan)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 16f else 14f
            gravity = Gravity.CENTER
            id = View.generateViewId()
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
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { showQualityDialog() }
        }
        screen.addView(qualityButton, FrameLayout.LayoutParams(
            -2, -2, Gravity.BOTTOM or Gravity.END,
        ).apply {
            marginEnd = cornerMargin
            bottomMargin = cornerMargin
        })
        // D-pad between the corners: right column (Fav ↓ Quality),
        // bottom row (Retry → Quality).
        favButton.nextFocusDownId = qualityButton.id
        qualityButton.nextFocusUpId = favButton.id
        retryButton.nextFocusRightId = qualityButton.id
        qualityButton.nextFocusLeftId = retryButton.id
        // Tap the video to show/hide the controls (touch only). The
        // PlayerView has no click handler of its own (useController=false),
        // so taps on it reach this listener; taps on the overlay buttons
        // are consumed by the buttons themselves. WebView embeds (YouTube)
        // consume their own touches, so this does not fire for web channels
        // — their controls never auto-hide either (see hidePlayerControls).
        screen.setOnClickListener { if (!isTv) togglePlayerControls() }
        // Glass info bar under the video (Qwen's 62dp bar): real channel
        // name + LIVE badge. No fake "Now/Next" program names — there is no
        // EPG data, so only the real channel name is shown. The seek slider
        // used to live here; it moved ONTO the video (YouTube-style).
        infoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassShape(18)
            setPadding(dp(22), 0, dp(22), 0)
            minimumHeight = dp(62)
            elevation = dp(2).toFloat()
            visibility = View.GONE
        }
        infoName = TextView(this).apply {
            text = "Select a channel"
            // Mobile par chhota naam (13sp), TV par 16sp waisa hi.
            textSize = if (isTv) 16f else 13f
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
        // Status line (buffering/errors): an overlay ON the video, never
        // a row under it. It used to sit in playerPane below the player,
        // and its TextWatcher flipped it VISIBLE on every "Connecting…"
        // / "Buffering…" — on TV/landscape the player has weight 1, so
        // the video shrank by the status height and grew back when the
        // text cleared, even in fullscreen (the watcher overrode the
        // fullscreen GONE). As an overlay it can never resize the video.
        status = label("", if (isTv) 15 else 13, accent).apply {
            background = shape(overlayDark, 0x1affffff.toInt(), 18, 1)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            isFocusable = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                }
            })
            visibility = View.GONE
        }
        screen.addView(status, FrameLayout.LayoutParams(
            -2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(12)
        })
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
                        // Only clear the buffering line — an error or
                        // retry message set by onError must survive.
                        if (status.text.toString() == "Buffering…") status.text = ""
                    }
                }
                override fun onError(message: String) {
                    if (!isDestroyed) status.text = message
                }
                override fun onEnded() {
                    if (isDestroyed) return
                    status.text = "Playback ended."
                    // A finished movie leaves Continue Watching by itself.
                    val movie = playingMovie
                    if (movie != null && movie.subjectId.isNotEmpty()) {
                        movieStore.removeContinue(movie.subjectId)
                        if (moviesOpen && !movieResultsAreSearch) showMoviesHome()
                    }
                    // A finished movie is not playing anymore — the browse
                    // pane comes back (Retry still replays it).
                    if (movie != null) {
                        movieEnded = true
                        updateBrowseVisibility()
                    }
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
                    // Live channels report seekable when the stream has a
                    // DVR window (a 24s window showed a seek bar on a live
                    // channel) — the seek bar is movies-only, so ignore
                    // the signal for everything else.
                    seekable = isSeekable && playingMovie != null
                    applyControlVisibility()
                    if (seekable) startSeekUpdates() else stopSeekUpdates()
                }
            })
        // The Movies overlay sits on top of the Live TV layout in a frame,
        // so opening it never detaches the player view underneath. Both
        // overlays build on first open (ensureMoviesView/ensureDramasView),
        // not here — building them at startup kept the app on a black
        // screen while they inflated.
        movieBox = MovieBox(applicationContext)
        movieStore = MovieStore(applicationContext)
        geoDramas = GeoDramas(applicationContext)
        contentFrame = FrameLayout(this)
        contentFrame.addView(rootView, FrameLayout.LayoutParams(-1, -1))
        setContentView(contentFrame)
        populate()
        list.getChildAt(0)?.requestFocus()
        // A movie playing across a rotation (or a process death) restores
        // from its saved signed URL instead of auto-playing the last channel.
        // A drama episode restores the same way — without this, going
        // fullscreen during an episode (rotation) played the last channel.
        val savedMovieUrl = savedInstanceState?.getString(STATE_MOVIE_URL)
        val savedDramaVideo = savedInstanceState?.getString(STATE_DRAMA_VIDEO)
        if (savedDramaVideo != null) {
            // Episodes restart from the beginning — WebView playback has
            // no position to restore (same as the background-return path).
            startDramaPlayback(
                GeoDramas.Episode(
                    videoId = savedDramaVideo,
                    title = savedInstanceState.getString(STATE_DRAMA_EPISODE).orEmpty(),
                    episodeNumber = savedInstanceState.getInt(STATE_DRAMA_EP_NUM, 0),
                ),
                savedInstanceState.getString(STATE_DRAMA_TITLE).orEmpty(),
            )
        } else if (savedMovieUrl != null) {
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
        // Playlist refresh + update check run after the first frame is up
        // (3s) — they are background network jobs and must not compete
        // with the app opening on a 1.5 GB TV.
        mainHandler.postDelayed({ refreshStalePlaylists() }, 3000)
        mainHandler.postDelayed({ checkForUpdateSilently() }, 3000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FULLSCREEN, fullScreen)
        // A rotation must not lose the movie's place in Continue Watching.
        saveMovieProgress(async = false)
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
        // Save a playing drama episode the same way, so a rotation —
        // including the one the fullscreen toggle triggers — resumes the
        // episode instead of the last channel.
        val drama = playingDrama
        if (drama != null) {
            outState.putString(STATE_DRAMA_VIDEO, drama.videoId)
            outState.putString(STATE_DRAMA_TITLE, playingDramaTitle)
            outState.putString(STATE_DRAMA_EPISODE, drama.title)
            outState.putInt(STATE_DRAMA_EP_NUM, drama.episodeNumber)
        }
    }

    /** Switches the TV browse pane between its two levels: the
     * categories, or the channels of the picked category. Focus lands on
     * the active category (categories) or the first row (channels). */
    private fun setBrowseLevel(level: BrowseLevel) {
        if (!isTv) return
        browseLevel = level
        val showingCategories = level == BrowseLevel.CATEGORIES
        categoryListScroll.visibility = if (showingCategories) View.VISIBLE else View.GONE
        catalogTitle.visibility = if (showingCategories) View.GONE else View.VISIBLE
        searchClearRow.visibility =
            if (!showingCategories && query.isNotEmpty()) View.VISIBLE else View.GONE
        listScroll.visibility = if (showingCategories) View.GONE else View.VISIBLE
        if (showingCategories) {
            filterChips[filter]?.requestFocus()
        } else {
            focusPlayingRowOrFirst()
        }
    }

    /** Focuses the playing channel's row when it is on screen, else the
     * first row — Back and level switches used to drop focus on channel
     * #1 even while another channel was playing. */
    private fun focusPlayingRowOrFirst(preferChannel: Channel? = selected) {
        val channel = preferChannel
        if (channel != null) {
            focusChannel(channel)
            // focusChannel is a no-op when the channel is not in this
            // list (filter changed) — but if focus already sits on a row,
            // that is still a good landing, so only fall back to the
            // first row when focus is nowhere in the list.
            if (currentFocus != null && list.indexOfChild(currentFocus) >= 0) return
        }
        list.getChildAt(0)?.requestFocus()
    }

    /** Puts D-pad focus where the current browse state needs it: the
     * playing movie's controls when the pane is hidden for a movie,
     * else the active category (categories level) or the playing
     * channel's row (channels level). */
    private fun focusBrowse(preferChannel: Channel? = selected) {
        if (playingMovie != null && !movieEnded && (isTv || isLandscape)) {
            playPauseButton.requestFocus()
            return
        }
        if (isTv && browseLevel == BrowseLevel.CATEGORIES) {
            filterChips[filter]?.requestFocus()
            return
        }
        focusPlayingRowOrFirst(preferChannel)
    }

    /** The browse pane (categories/channels) hides while a movie plays
     * on a side-by-side screen, so the video gets the space; channels
     * and dramas keep it (you surf during those). Fullscreen owns
     * visibility while it lasts, so this is a no-op there. */
    private fun updateBrowseVisibility() {
        if (fullScreen) return
        val visible = playingMovie == null || movieEnded || !(isTv || isLandscape)
        libraryPane.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Rebuilds the channel list as ONE list (no pages — the user asked
     * for a single scroll). With preserveScroll, the scroll position is
     * kept (favorite toggles); otherwise the list starts at the top
     * (filter/search changes). */
    private fun populate(preserveScroll: Boolean = false) {
        val savedScrollY = if (preserveScroll) listScroll.scrollY else 0
        list.removeAllViews()
        // Search clear row (task 2): always visible while a query is active,
        // so there is always a way back to the full list — but only on the
        // channels level (on the categories level it would be a stray row).
        if (query.isNotEmpty()) {
            searchClearLabel.text = "Search: \"$query\""
            searchClearRow.visibility =
                if (browseLevel == BrowseLevel.CHANNELS) View.VISIBLE else View.GONE
        } else {
            searchClearRow.visibility = View.GONE
        }
        // The pane search bar shows the active query too.
        paneSearchLabel.text =
            if (query.isNotEmpty()) "Search: \"$query\"" else "Search channels…"
        val candidates = when {
            // Search is global: a query searches every bundled channel,
            // whatever category is showing — otherwise a search from the
            // News filter can never find a General channel like Express.
            query.isNotEmpty() -> verifiedChannels
            filter == "M3U" -> imported
            filter == "Favs" -> store.favoritesList()
            filter == "Recent" -> store.recentList()
            else -> verifiedChannels
        }
        // Categories match the M3U group-title — case-insensitive,
        // multi-group titles ("Animation;Kids") match every part. General
        // is the catch-all: every group without its own category
        // (Entertainment, Movies, Music, Drama, Legislative…) lands
        // there, so every channel is reachable through some category.
        val matches = candidates.filter { channel ->
            val accepted = if (query.isNotEmpty()) true else when (filter) {
                "News" -> groupMatches(channel, "news")
                "Sports" -> groupMatches(channel, "sports", "cricket")
                "Islamic" -> groupMatches(channel, "islamic", "religious")
                "Kids" -> groupMatches(channel, "kids", "animation")
                "General" -> groupMatches(channel, "general", "undefined") ||
                    !categoryGroups.any { groupMatches(channel, it) }
                else -> true
            }
            accepted && (query.isEmpty() || channel.name.contains(query, ignoreCase = true))
        }.let { filtered ->
            // Pakistani channels first, Indian last (stable sort keeps the
            // M3U order inside each group). Favorites/Recent keep their
            // own order; imported playlists keep theirs.
            if (filter == "Favs" || filter == "Recent" || filter == "M3U") filtered
            else filtered.sortedWith(compareBy { channel ->
                when (channel.country.uppercase(Locale.ROOT)) {
                    "PK" -> 0
                    "IN" -> 2
                    else -> 1
                }
            })
        }
        currentMatches = matches
        val sourceName = when (filter) {
            "M3U" -> playlistStore.active()?.name ?: "My playlist"
            "Favs" -> "Favorites"
            "Recent" -> "Recent"
            else -> "Checked streams"
        }
        catalogTitle.text = "$sourceName · ${matches.size}" +
            if (query.isEmpty()) "" else " · $query"
        matches.forEach { channel ->
            list.addView(channelRow(channel), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(if (isTv) 3 else 5)
                bottomMargin = dp(if (isTv) 3 else 5)
            })
        }
        if (matches.isEmpty()) {
            if (query.isNotEmpty()) {
                // No-result state (task 2): say so AND offer the way back —
                // the user was stuck here with no exit before.
                list.addView(label("Koi natija nahi mila — \"$query\"", if (isTv) 16 else 14, muted))
                list.addView(button("← Poori list wapas") { clearSearch() },
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(8)
                    })
            } else {
                // Contextual empty states (task 10): say WHY the list is
                // empty and what fixes it, instead of a bare "No matches".
                val emptyText = when (filter) {
                    "Favs" -> "Koi favorite nahi hai — kisi channel card par ♥ dabayein."
                    "Recent" -> "Abhi koi recent channel nahi hai."
                    "M3U" -> "Koi playlist import nahi hui — Import M3U dabayein."
                    else -> "Is category mein koi channel nahi hai."
                }
                list.addView(label(emptyText, if (isTv) 16 else 14, muted))
            }
        }
        listScroll.post { listScroll.scrollTo(0, savedScrollY) }
        markPlayingRow()
    }

    /** Case-insensitive group-title match; multi-group titles ("Animation;Kids")
     * match each part, so the channel appears under every category it belongs to. */
    private fun groupMatches(channel: Channel, vararg names: String): Boolean {
        val parts = channel.group.split(';').map { it.trim().lowercase(Locale.ROOT) }
        return names.any { it.lowercase(Locale.ROOT) in parts }
    }

    /** Clears the active search and restores the full list (task 2). */
    private fun clearSearch() {
        query = ""
        populate()
        list.getChildAt(0)?.requestFocus()
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
            // 48dp+ touch target on phones; compact on TV so more rows fit.
            minimumHeight = dp(if (isTv) 48 else 52)
            setPadding(dp(14), dp(if (isTv) 6 else 10), dp(14), dp(if (isTv) 6 else 10))
            tag = channel.key
            background = rowBackground(isPlaying)
            elevation = dp(3).toFloat()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { play(channel) }
            // TV D-pad: RIGHT from a row goes onto the video's own
            // controls (play/pause, volume, fullscreen) — the target is
            // the first overlay control that is actually visible, and
            // applyControlVisibility re-syncs it on every playback change.
            nextFocusRightId = firstOverlayControlId()
        }
        val tileSize = dp(if (isTv) 40 else 44)
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
            textSize = if (isTv) 15f else 15f
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
                textSize = 12f
                setTextColor(muted2)
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
        // channel's favorite; the on-screen corner button does the same
        // for the playing channel.
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
            focusBrowse()
        }
    }

    /**
     * Applies the current [fullScreen] state to the UI. Called from
     * [setFullScreen] and from onCreate when restoring after rotation.
     * Touch devices get real fullscreen: immersive system UI, and only the
     * player on screen (the landscape layout already lets it fill the pane).
     * The on-video overlay — including the fullscreen toggle — stays visible
     * so the user can exit from the video itself; Back also exits.
     * On TV there is no rotation or system UI to hide, but fullscreen is
     * still real fullscreen: the browse pane, header and info bar all
     * hide and the paddings go to zero, so the player pane (weight 1)
     * fills the whole 1920x1080 screen. Only the video + on-video controls
     * stay; Back also exits.
     */
    private fun updateFullscreenUi() {
        fullscreenIcon.expanded = fullScreen
        fullscreenIcon.invalidateSelf()
        fullscreenButton.contentDescription = if (fullScreen) "Exit fullscreen" else "Fullscreen"
        if (isTv) {
            if (fullScreen) {
                headerView.visibility = View.GONE
                libraryPane.visibility = View.GONE
                infoBar.visibility = View.GONE
                rootView.setPadding(0, 0, 0, 0)
                playerPane.setPadding(0, 0, 0, 0)
            } else {
                headerView.visibility = View.VISIBLE
                // The browse pane stays hidden while a movie plays.
                updateBrowseVisibility()
                val showBars = selected != null || playingMovie != null || playingDrama != null
                infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
                rootView.setPadding(rootPaddingPx, dp(10), rootPaddingPx, dp(12))
                playerPane.setPadding(paneGapPx, 0, 0, 0)
            }
            return
        }
        if (fullScreen) {
            hideSystemUi()
            headerView.visibility = View.GONE
            libraryPane.visibility = View.GONE
            infoBar.visibility = View.GONE
            rootView.setPadding(0, 0, 0, 0)
            playerPane.setPadding(0, 0, 0, 0)
        } else {
            showSystemUi()
            headerView.visibility = if (!isTv && isLandscape) View.GONE else View.VISIBLE
            // The browse pane stays hidden while a movie plays.
            updateBrowseVisibility()
            val showBars = selected != null || playingMovie != null || playingDrama != null
            infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
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

    private fun play(channel: Channel, forceRestart: Boolean = false) {
        // Pressing OK on the channel that is already playing does NOT
        // restart it — it takes it fullscreen. The channel screen has
        // no controls anymore, so OK is the fullscreen switch (Back
        // leaves fullscreen). Web channels join in: the selected-key
        // match is the only signal a WebView gives. Retry bypasses
        // this with forceRestart — otherwise the Retry button on a web
        // channel would just go fullscreen instead of reloading.
        if (!forceRestart && channel.key == selected?.key &&
            (channel.web || playerController?.isPlaying() == true)) {
            focusChannel(channel)
            if (!fullScreen) setFullScreen(true)
            return
        }
        // Switching to a channel must not lose the movie's place in
        // Continue Watching — save it before the movie state is cleared.
        saveMovieProgress(async = false)
        stopWeb()
        selected = channel
        playingMovie = null
        playingMovieUrl = null
        playingMovieHeaders = emptyMap()
        playingDrama = null
        // The list is one list now — the playing channel's row is always
        // on screen, so focus just goes to it.
        val matchIndex = currentMatches.indexOfFirst { it.key == channel.key }
        if (matchIndex >= 0) focusChannel(channel)
        store.addRecent(channel)
        store.lastChannel = channel
        setChannelMeta(channel)
        // In phone fullscreen the bars stay hidden (Back exits first);
        // a channel change must not pop them back over the video.
        val showBars = !(fullScreen && !isTv)
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
        updateBrowseVisibility()
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
        // Touch: show the on-video controls for the new channel, then let
        // them auto-hide (YouTube-style). On TV this is a no-op.
        showPlayerControls()
    }

    private fun focusChannel(channel: Channel) {
        // One list, no pages: the row index IS the match index.
        val index = currentMatches.indexOfFirst { it.key == channel.key }
        if (index < 0) return
        list.getChildAt(index)?.requestFocus()
    }

    private fun retryPlayback() {
        val drama = playingDrama
        if (drama != null) {
            startDramaPlayback(drama, playingDramaTitle)
            return
        }
        if (playingMovie != null) {
            playerController?.retry()
            return
        }
        val channel = selected ?: return
        if (channel.web) play(channel, forceRestart = true) else playerController?.retry()
    }

    private fun updateFavoriteButton() {
        val channel = selected
        val isFav = channel != null && store.isFavorite(channel)
        favButton.text = if (isFav) "★" else "☆"
        favButton.setTextColor(if (isFav) magenta else Color.WHITE)
    }

    private fun toggleFavorite() {
        val channel = selected ?: return
        val nowFavorite = store.toggleFavorite(channel)
        updateFavoriteButton()
        populate(preserveScroll = true)
        toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
    }

    /** Favorite toggle from a channel card's heart (any channel, not just
     * the playing one). The list rebuilds, as it does for the tile toggle. */
    private fun toggleFavoriteFor(channel: Channel) {
        val nowFavorite = store.toggleFavorite(channel)
        updateFavoriteButton()
        populate(preserveScroll = true)
        toast(if (nowFavorite) "Added to favorites." else "Removed from favorites.")
    }

    /** First on-video control that is currently visible — the D-pad
     * target for RIGHT from the channel list. Movies/dramas show the
     * overlay pill, so the first visible pill button is the landing.
     * Channels play on a clean screen (no pill) — RIGHT goes to the
     * on-video corner buttons: Quality first when it is showing
     * (multi-track stream), else Favorite, else Retry. */
    private fun firstOverlayControlId(): Int {
        if (overlayBar.visibility == View.VISIBLE) {
            return when {
                playPauseButton.visibility == View.VISIBLE -> playPauseButton.id
                volumeSeekBar.visibility == View.VISIBLE -> volumeSeekBar.id
                muteButton.visibility == View.VISIBLE -> muteButton.id
                speedButton.visibility == View.VISIBLE -> speedButton.id
                else -> fullscreenButton.id
            }
        }
        return when {
            qualityButton.visibility == View.VISIBLE -> qualityButton.id
            favButton.visibility == View.VISIBLE -> favButton.id
            retryButton.visibility == View.VISIBLE -> retryButton.id
            // Nothing on screen (no channel playing yet) — the pill is
            // gone, so FocusFinder falls back to geometry; any id works.
            else -> fullscreenButton.id
        }
    }

    /** Re-points every visible channel row's RIGHT target at the overlay
     * after the visible controls changed (channel switch, drama, movie). */
    private fun syncRowFocusTargets() {
        val target = firstOverlayControlId()
        for (i in 0 until list.childCount) {
            list.getChildAt(i)?.nextFocusRightId = target
        }
    }

    private fun applyControlVisibility() {
        // A drama episode plays in the same WebView as a web channel, so
        // the ExoPlayer controls (play/pause, volume, speed, seek) stay
        // hidden for it too — the WebView consumes its own touches.
        val webChannel = selected?.web == true || playingDrama != null
        // A channel plays on a clean screen: no overlay pill, no meta
        // chip — the video is just the video. Movies keep their controls
        // (play/pause, seek); dramas keep the fullscreen button.
        val channelPlaying = selected != null && playingMovie == null && playingDrama == null
        overlayBar.visibility = if (channelPlaying) View.GONE else View.VISIBLE
        metaChip.visibility = if (channelPlaying || isTv) View.GONE else View.VISIBLE
        playPauseButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        volumeSeekBar.visibility = if (webChannel) View.GONE else View.VISIBLE
        muteButton.visibility = if (webChannel) View.GONE else View.VISIBLE
        // On-video corner buttons: Favorite + Retry show while a channel
        // plays (the tiles under the player are gone); Quality shows for
        // multi-track streams, on the video's bottom-right corner. Speed
        // lives in the overlay pill and only applies to movies.
        val showQuality = !webChannel && videoOptions.size >= 2
        qualityButton.visibility = if (showQuality) View.VISIBLE else View.GONE
        favButton.visibility = if (channelPlaying) View.VISIBLE else View.GONE
        retryButton.visibility = if (channelPlaying) View.VISIBLE else View.GONE
        speedButton.visibility = if (playingMovie != null) View.VISIBLE else View.GONE
        // The seek row lives ON the video (YouTube-style): movies only —
        // live channels report "seekable" when the stream has a DVR
        // window, and the bar must never appear on a channel. On touch
        // it hides together with the pill after 3 seconds.
        seekRow.visibility = if (webChannel || playingMovie == null || !seekable) View.GONE
            else if (isTv || controlsVisible) View.VISIBLE else View.GONE
        // D-pad routing onto the video: UP from play/pause reaches the
        // seek bar only while it is shown (DOWN from the seek bar comes
        // back); rows always point RIGHT at the first visible control.
        playPauseButton.nextFocusUpId =
            if (seekRow.visibility == View.VISIBLE) seekBar.id else View.NO_ID
        seekBar.nextFocusDownId = playPauseButton.id
        syncRowFocusTargets()
    }

    /** Shows the on-video controls (overlay pill + seek row) and schedules
     * their auto-hide on touch devices. On TV this is a no-op — the controls
     * always stay visible for D-pad navigation. Called on video tap, on any
     * control interaction, and when playback starts. */
    private fun showPlayerControls() {
        if (isTv) return
        mainHandler.removeCallbacks(hideControlsRunnable)
        controlsVisible = true
        overlayBar.visibility = View.VISIBLE
        applyControlVisibility()
        scheduleHideControls()
    }

    /** Hides the on-video controls (touch only). Never hides while the user
     * is scrubbing, and never hides for web channels — a WebView consumes
     * its own touches, so a tap could not bring the controls back. */
    private fun hidePlayerControls() {
        if (isTv || scrubbing || selected?.web == true || playingDrama != null) return
        controlsVisible = false
        overlayBar.visibility = View.GONE
        seekRow.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        if (isTv || selected?.web == true || playingDrama != null) return
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
    }

    private fun togglePlayerControls() {
        if (controlsVisible) {
            mainHandler.removeCallbacks(hideControlsRunnable)
            hidePlayerControls()
        } else {
            showPlayerControls()
        }
    }

    /** Keeps the on-video quality button's label in sync with the
     * selected track ("720p", or "Quality" for Auto). */
    private fun setQualityText(label: String) {
        qualityButton.text = label
    }

    private fun setSpeedText(label: String) {
        speedButton.text = label
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
        // While the Movies browser or the Geo Dramas browser is open,
        // channel number jumps and up/down channel switching stay off —
        // D-pad navigation belongs to the open section.
        if (moviesOpen || dramasOpen) return super.onKeyDown(keyCode, event)
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

    /** Up/down switch channels whenever the Live TV screen is in charge
     * — the remote's channel +/-. The first press used to zap and the
     * rest did nothing: the zap only fired while focus sat on the video
     * surface, and play() pulled focus into the list right after it.
     * Now a channel row zaps too (focus follows the new channel, so the
     * next press zaps again). The only exceptions keep their own D-pad
     * navigation: the categories pane, the TopBar, the on-screen corner
     * buttons, the overlay pill and any text field. Movies and dramas
     * never zap — their up/down belongs to the player controls. */
    private fun channelSwitchKeysActive(): Boolean {
        if (fullScreen) return true
        if (playingMovie != null || playingDrama != null) return false
        var view: View? = currentFocus ?: return false
        while (view != null) {
            // Navigation surfaces keep their own up/down.
            if (view === categoryListScroll || view === headerView ||
                view === overlayBar || view === metaChip || view === numberOverlay ||
                view === favButton || view === retryButton || view === qualityButton) return false
            // A channel row — or the video surface itself — zaps. (With
            // no matches on screen the list holds only the empty-state
            // button, so up/down keeps navigating instead of zapping
            // into nothing and trapping focus there.)
            if (view === list) return currentMatches.isNotEmpty()
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

    private fun playEmbed(videoId: String, loop: Boolean = true) {
        if (videoId.startsWith("yt-live:")) {
            // Remember the token: when the stream ends or restarts, the
            // IFrame API reports ENDED and the 5-minute poll re-checks —
            // both re-resolve through here.
            playingYtLive = videoId
            status.text = "Finding the live video…"
            val request = playbackRequest
            imports.execute {
                val resolved = runCatching { Playlist.resolveYouTubeLive(videoId.substringAfter(':')) }.getOrNull()
                runOnUiThread {
                    if (isDestroyed || request != playbackRequest) return@runOnUiThread
                    if (resolved == null) {
                        status.text = "No embeddable live video found."
                    } else if (resolved == playingEmbedId) {
                        // The live stream ended and YouTube still points
                        // at the same video — reloading would loop on
                        // the related-videos end screen. The poll keeps
                        // checking and rejoins when a new stream starts.
                        status.text = "Live stream khatam ho gaya — thori der baad Retry dabao."
                    } else {
                        // Live embeds never loop: when the stream ends,
                        // the player must report ENDED (so we re-resolve)
                        // instead of replaying the finished video.
                        // Stop the old embed first (a re-resolve replaces
                        // it); keep the yt-live token across the stop.
                        val token = playingYtLive
                        stopWeb()
                        playingYtLive = token
                        playEmbed(resolved, loop = false)
                        scheduleYtLivePoll()
                    }
                }
            }
            return
        }
        // Embed params hide the source platform: no player controls, no
        // logo/branding, no related videos, no keyboard shortcuts, no
        // in-player fullscreen — the app's own overlay controls (incl. its
        // fullscreen toggle) are the only UI the user ever sees, so the
        // channel looks like any other channel, not a YouTube embed.
        // Plain video IDs (recorded videos) loop so the
        // channel never goes silent when the video ends. Drama episodes
        // pass loop=false (see playDramaEpisode) so an episode ends
        // instead of replaying itself; live streams also play with
        // loop=false (see the yt-live branch above) so a restart is
        // detected instead of replaying the finished stream.
        val isDirectWeb = videoId.startsWith("web:")
        val isPlainVideo = videoId.matches(Regex("[A-Za-z0-9_-]{11}"))
        // The embed token currently showing (plain video ID or yt-channel
        // token) — a re-resolve returning the same token does not reload.
        playingEmbedId = if (isPlainVideo || videoId.startsWith("yt-channel:")) videoId else null
        val isYouTube = isPlainVideo || videoId.matches(Regex("yt-channel:UC[A-Za-z0-9_-]{22}"))
        val loopParams = if (loop && isPlainVideo) "&amp;loop=1&amp;playlist=$videoId" else ""
        val embedUrl = when {
            isDirectWeb -> ""
            isPlainVideo ->
                "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&amp;playsinline=1&amp;controls=0&amp;modestbranding=1&amp;rel=0&amp;iv_load_policy=3&amp;disablekb=1&amp;fs=0&amp;enablejsapi=1$loopParams"
            videoId.matches(Regex("yt-channel:UC[A-Za-z0-9_-]{22}")) ->
                "https://www.youtube-nocookie.com/embed/live_stream?channel=${videoId.substringAfter(':')}&amp;autoplay=1&amp;playsinline=1&amp;controls=0&amp;modestbranding=1&amp;rel=0&amp;iv_load_policy=3&amp;disablekb=1&amp;fs=0&amp;enablejsapi=1"
            videoId.matches(Regex("dm:[A-Za-z0-9]{5,12}")) ->
                "https://www.dailymotion.com/embed/video/${videoId.substringAfter(':')}?autoplay=1&amp;ui-logo=false&amp;ui-start-screen-info=false&amp;sharing-enable=false"
            else -> {
                status.text = "This listing needs a direct video or embeddable stream URL."
                return
            }
        }
        status.text = ""
        // A direct web player (playsza.st) needs real top-level
        // navigation on its own host + the Cloudflare challenge host;
        // every other embed keeps main-frame navigation blocked.
        allowMainFrameHosts = if (isDirectWeb) setOf("playsza.st", "challenges.cloudflare.com") else emptySet()
        val player = WebView(this)
        web = player
        // D-pad focus: only a direct web player (playsza.st) may take
        // it — its page needs real interaction (Cloudflare check). An
        // iframe embed must never take focus: a center press inside it
        // pops YouTube's own "more videos" overlay, and the full-screen
        // WebView rect would trap D-pad navigation.
        player.isFocusable = isDirectWeb
        player.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            // Web players sometimes serve video over plain HTTP while
            // the page is HTTPS — allow media, still block scripts.
            if (isDirectWeb) mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        player.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                return (request.isForMainFrame && host !in allowMainFrameHosts) ||
                    request.url.scheme != "https" ||
                    (host !in setOf("www.youtube.com", "www.youtube-nocookie.com", "www.dailymotion.com",
                        "playsza.st", "challenges.cloudflare.com"))
            }
        }
        if (isYouTube) player.addJavascriptInterface(YtEndedBridge(), "AndroidBridge")
        screen.addView(player, FrameLayout.LayoutParams(-1, -1))
        if (!isDirectWeb) {
            // Taps on an iframe embed reach YouTube/Dailymotion, which
            // pops its own "more videos" overlay over the channel — the
            // app's own tap handler never sees them (a WebView consumes
            // its touches). This transparent catcher swallows those taps
            // and toggles the app's controls instead. It sits below the
            // overlay controls (raised right after this) so the buttons
            // and seek bar still work.
            val catcher = View(this).apply {
                isClickable = true
                isFocusable = false
                setOnClickListener { if (!isTv) togglePlayerControls() }
            }
            webTapCatcher = catcher
            screen.addView(catcher, FrameLayout.LayoutParams(-1, -1))
        }
        bringPlayerOverlaysToFront()
        if (isDirectWeb) {
            // Generic web-player embed (playsza.st): load the player page
            // directly. Inside an iframe it is often blocked by the
            // host's X-Frame-Options, and its Cloudflare check needs
            // real top-level navigation. A Cloudflare check may appear
            // on first load; on a real phone it usually passes on its
            // own, otherwise the user taps it.
            player.loadUrl(videoId.substringAfter(':'))
            return
        }
        // YouTube embeds report ENDED through the IFrame API so a live
        // stream that ends (broadcaster restart) re-resolves instead of
        // sitting on the related-videos end screen.
        val apiScript = if (isYouTube) """
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
            function onYouTubeIframeAPIReady() {
              new YT.Player('ytplayer', { events: { 'onStateChange': function(e) {
                if (e.data == YT.PlayerState.ENDED) { AndroidBridge.onYouTubeEnded(); }
              }}});
            }
            </script>
        """.trimIndent() else ""
        val playerDocument = """
            <!doctype html><html lang="en"><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{margin:0;background:black}iframe{display:block;width:100vw;height:100vh;border:0}</style>
            <iframe id="ytplayer" title="Live channel player" src="$embedUrl"
              referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture"></iframe>
            $apiScript</html>
        """.trimIndent()
        player.loadDataWithBaseURL("https://iptv-design-preview-2edf.roarai.app/", playerDocument,
            "text/html", "UTF-8", null)
    }

    /** JS bridge for YouTube embeds: the IFrame API calls this when the
     * video ends. Only yt-live channels act on it (re-resolve); a looped
     * video or a drama episode ending needs no action. */
    private inner class YtEndedBridge {
        @JavascriptInterface
        fun onYouTubeEnded() {
            runOnUiThread { onEmbedEnded() }
        }
    }

    private fun onEmbedEnded() {
        // A YouTube live stream ended (the broadcaster restarted it) —
        // re-resolve so the embed rejoins the live stream instead of
        // sitting on the related-videos end screen.
        val token = playingYtLive ?: return
        playEmbed(token)
    }

    private fun scheduleYtLivePoll() {
        mainHandler.removeCallbacks(ytLivePollRunnable)
        mainHandler.postDelayed(ytLivePollRunnable, YT_LIVE_POLL_MS)
    }

    /** While a yt-live channel plays, re-check /live every 5 minutes and
     * rejoin when the video ID changes (the broadcaster restarted the
     * stream). This catches restarts the player never reports as ENDED
     * (e.g. a stall). One small fetch per check; a failed check just
     * waits for the next one. */
    private fun pollYtLive() {
        val token = playingYtLive ?: return
        val request = playbackRequest
        imports.execute {
            val resolved = runCatching { Playlist.resolveYouTubeLive(token.substringAfter(':')) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || request != playbackRequest) return@runOnUiThread
                if (playingYtLive == null) return@runOnUiThread
                if (resolved != null && resolved != playingEmbedId &&
                    resolved.matches(Regex("[A-Za-z0-9_-]{11}"))
                ) {
                    // The live stream restarted under a new video.
                    stopWeb()
                    playingYtLive = token
                    playEmbed(resolved, loop = false)
                }
                mainHandler.postDelayed(ytLivePollRunnable, YT_LIVE_POLL_MS)
            }
        }
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
     * Search button (the pill is hidden on phones in landscape). The
     * current query is pre-filled, so the user can refine it instead of
     * retyping; clearing it restores the full list. */
    private fun openSearch() {
        askText("Search channels", "Channel name", query) { value ->
            query = value
            populate()
            // Results are channels — the TV pane switches to that level.
            setBrowseLevel(BrowseLevel.CHANNELS)
            list.getChildAt(0)?.requestFocus()
        }
    }

    private fun askText(title: String, hint: String, initial: String = "", action: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
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
        mainHandler.removeCallbacks(ytLivePollRunnable)
        playingYtLive = null
        playingEmbedId = null
        allowMainFrameHosts = emptySet()
        webTapCatcher?.let { screen.removeView(it) }
        webTapCatcher = null
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
            val drama = playingDrama
            val movie = playingMovie
            val url = playingMovieUrl
            if (drama != null) {
                // A drama episode restarts from the beginning — WebView
                // playback has no position to restore.
                startDramaPlayback(drama, playingDramaTitle)
            } else if (movie != null && url != null) {
                playMovieStream(movie, url, playingMovieHeaders, savedMoviePosition)
            } else {
                selected?.let(::play)
            }
        }
    }

    override fun onStop() {
        // Capture the movie position BEFORE stopPlayback releases the player,
        // so coming back from background resumes the movie where it left off.
        if (playingMovie != null) savedMoviePosition = playerController?.position() ?: 0L
        saveMovieProgress(async = false)
        stopPlayback()
        stoppedForBackground = true
        super.onStop()
    }

    override fun onDestroy() {
        // Never leak the update download dialog across a rotation/destroy.
        updateDownloadDialog?.dismiss()
        updateDownloadDialog = null
        imports.shutdownNow()
        dramaDates.shutdownNow()
        // LogoLoader's pool is shared across activity instances: killing it
        // on a rotation would crash the recreated activity the first time it
        // loads a channel logo — and fullscreen rotates on every toggle.
        if (!isChangingConfigurations) LogoLoader.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Use the system back gesture callback on newer Android versions")
    override fun onBackPressed() {
        if (dramasOpen) {
            if (dramasEpisodesPane.visibility == View.VISIBLE) {
                showDramasList()
            } else {
                closeDramas()
            }
        } else if (moviesOpen) {
            if (moviesDetailsPane.visibility == View.VISIBLE) {
                showMoviesList()
            } else {
                closeMovies()
            }
        } else if (fullScreen) {
            setFullScreen(false)
        } else if (query.isNotEmpty()) {
            // Back from a search restores the full list (task 2) — the user
            // is never stuck on a filtered (or empty) result.
            clearSearch()
        } else if (isTv && browseLevel == BrowseLevel.CHANNELS) {
            // Back from the channels list returns to the categories —
            // playback keeps going; another Back stops it.
            setBrowseLevel(BrowseLevel.CATEGORIES)
        } else if (selected == null && playingMovie == null && playingDrama == null) {
            super.onBackPressed()
        } else {
            // Back while something plays stops it — save the movie's place
            // in Continue Watching before the player is released.
            saveMovieProgress(async = false)
            val stoppedChannel = selected
            stopPlayback()
            selected = null
            playingMovie = null
            playingMovieUrl = null
            playingMovieHeaders = emptyMap()
            playingDrama = null
            playingDramaTitle = ""
            movieEnded = false
            infoBar.visibility = View.GONE
            playPauseButton.text = "▶"
            setChannelMeta(null)
            status.text = ""
            applyControlVisibility()
            markPlayingRow()
            updateBrowseVisibility()
            focusBrowse(stoppedChannel)
        }
    }

    /** D-pad LEFT/RIGHT during a movie seek 10 seconds per press — but
     * only when focus sits on the play/pause button or the seek bar.
     * Everywhere else LEFT/RIGHT keep navigating (corner buttons, pill
     * buttons, header), and a focused volume bar keeps adjusting volume.
     * The intercept runs in dispatchKeyEvent so the seek bar's own ±1s
     * key handling never fires alongside it. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playingMovie != null && !movieEnded && event.action == KeyEvent.ACTION_DOWN) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val focused = currentFocus
                if (focused === playPauseButton || focused === seekBar) {
                    seekMovieBy(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Seeks the playing movie by a relative amount, clamped to its
     * length; the seek UI updates at once (the 500ms ticker catches up
     * after). */
    private fun seekMovieBy(deltaMs: Long) {
        val controller = playerController ?: return
        if (playingMovie == null) return
        val duration = controller.duration()
        val target = if (duration > 0) {
            (controller.position() + deltaMs).coerceIn(0L, duration)
        } else {
            (controller.position() + deltaMs).coerceAtLeast(0L)
        }
        controller.seekTo(target)
        if (duration > 0) {
            seekBar.max = (duration / 1000).toInt()
            seekBar.progress = (target / 1000).toInt()
            seekLabel.text = formatTime(target) + " / " + formatTime(duration)
        }
        showPlayerControls()
    }

    // ---- MovieBox movies (v1) ----
    //
    // A full-screen Movies SECTION (same Qwen navy-glass look) beside Live
    // TV: header with a "Live TV" way back, a now-playing bar, quick-search
    // chips, then search → results (poster + name + year + HINDI badge) →
    // details → audio (dub) + quality pickers → Play in the main player.
    // Technically it is an overlay view over the Live TV layout, so opening
    // it never detaches the player view underneath.
    // All MovieBox calls run on the `imports` executor; stale results are
    // dropped with a per-request counter, the same pattern as playEmbed.

    private fun buildMoviesView(): LinearLayout {
        val view = column().apply {
            // TV overscan: 32dp sides so nothing is cut (same as the
            // main layout's root padding).
            setPadding(dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12),
                dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg0, bg1, bg2),
            )
            // Clickable so touches don't fall through to the Live TV UI
            // underneath — but NOT focusable: a focusable full-screen root
            // traps D-pad focus (every child sits inside its rect, so no
            // direction finds a next view) and navigation dies.
            isClickable = true
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backButton = button("‹ Live TV") { closeMovies() }
        backButton.id = View.generateViewId()
        header.addView(backButton,
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        header.addView(label("🎬 Movies", if (isTv) 26 else 22, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        view.addView(header)
        // Now-playing bar: when a movie is already playing, the section
        // says so and offers a shortcut back to the player.
        movieNowPlayingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassShape(16)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        movieNowPlayingLabel = TextView(this).apply {
            textSize = if (isTv) 15f else 14f
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        movieNowPlayingBar.addView(movieNowPlayingLabel, LinearLayout.LayoutParams(0, -2, 1f))
        movieNowPlayingBar.addView(button("▶ Player") { closeMovies() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        view.addView(movieNowPlayingBar, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

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
            // A focused EditText can swallow D-pad up/down on some TVs
            // (the Leanback keyboard session eats them) — forward them
            // to focus search so navigation never dies on the search box.
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN
                        else View.FOCUS_UP
                    focusSearch(direction)?.requestFocus()
                    true
                } else {
                    false
                }
            }
        }
        // No Search button next to the bar — the keyboard's search key
        // runs the search (the button was a redundant tap target).
        searchRow.addView(movieSearchInput, LinearLayout.LayoutParams(0, -2, 1f))
        moviesListPane.addView(searchRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(8)
        })
        // Quick searches: one tap runs a real MovieBox search for a popular
        // term (no fake listings — these are just search shortcuts). The
        // Home chip goes back to Continue Watching + Fav Movies.
        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val homeChip = chip("⌂ Home") { showMoviesHome() }
        homeChip.id = View.generateViewId()
        movieHomeChip = homeChip
        quickRow.addView(homeChip, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(8)
        })
        for (term in listOf("Marvel", "DC", "Bollywood", "Punjabi", "Action", "Comedy")) {
            val quickChip = chip(term) { quickSearchMovie(term) }
            quickRow.addView(quickChip, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(8)
            })
        }
        // Explicit vertical links for the search box — geometry alone is
        // unreliable here (the chips sit inside a horizontal scroller).
        movieSearchInput.nextFocusDownId = homeChip.id
        movieSearchInput.nextFocusUpId = backButton.id
        val quickScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(quickRow)
        }
        moviesListPane.addView(quickScroll, LinearLayout.LayoutParams(-1, -2).apply {
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
        movieFavButton = button("♡ Favorite") { toggleFavoriteMovie() }
        detailsInner.addView(movieFavButton, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        detailsInner.addView(button("‹ Back to results") { showMoviesList() },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        moviesDetailsPane.addView(detailsScroll, LinearLayout.LayoutParams(-1, -1))
        view.addView(moviesDetailsPane, LinearLayout.LayoutParams(-1, 0, 1f))
        return view
    }

    /** Stops the currently playing channel (if any) — the Movies browser
     * opens over a silent screen instead of a channel that keeps playing
     * behind a movie search. Movies and dramas keep playing underneath. */
    private fun stopPlayingChannel() {
        if (selected == null) return
        stopPlayback()
        selected = null
        infoBar.visibility = View.GONE
        playPauseButton.text = "▶"
        setChannelMeta(null)
        status.text = ""
        applyControlVisibility()
        markPlayingRow()
        updateBrowseVisibility()
    }

    /** Builds the Movies overlay on first open (see onCreate — it is not
     * built at startup, so the app opens faster). */
    private fun ensureMoviesView() {
        if (moviesViewBuilt) return
        moviesViewBuilt = true
        moviesView = buildMoviesView()
        contentFrame.addView(moviesView, FrameLayout.LayoutParams(-1, -1))
    }

    /** Builds the Geo Dramas overlay on first open (same reason). */
    private fun ensureDramasView() {
        if (dramasViewBuilt) return
        dramasViewBuilt = true
        dramasView = buildDramasView()
        contentFrame.addView(dramasView, FrameLayout.LayoutParams(-1, -1))
    }

    private fun openMovies() {
        ensureMoviesView()
        // A channel must not keep playing behind the Movies browser.
        stopPlayingChannel()
        moviesOpen = true
        moviesView.visibility = View.VISIBLE
        // Now-playing bar: show it only when a movie is actually playing.
        val movie = playingMovie
        if (movie != null) {
            movieNowPlayingLabel.text = "▶ Abhi chal rahi hai: ${movie.title}"
            movieNowPlayingBar.visibility = View.VISIBLE
        } else {
            movieNowPlayingBar.visibility = View.GONE
        }
        showMoviesList()
    }

    private fun closeMovies() {
        moviesOpen = false
        moviesView.visibility = View.GONE
        // A movie keeps playing underneath when the browser is closed.
        focusBrowse()
    }

    private fun showMoviesList() {
        moviesDetailsPane.visibility = View.GONE
        moviesListPane.visibility = View.VISIBLE
        // No search on screen — show (and refresh) the Movies home:
        // Continue Watching + Fav Movies. A search result stays as it is.
        if (!movieResultsAreSearch) showMoviesHome()
        // Focus landing: Back from a movie's details returns to the row
        // it came from; otherwise the first row (not the search box —
        // focusing it pops the keyboard and D-pad up/down can die on it).
        // With no rows at all, the Home chip is the landing.
        val returnRow = moviesReturnRow
        moviesReturnRow = -1
        val target = if (returnRow in 0 until movieResults.childCount) {
            movieResults.getChildAt(returnRow)?.takeIf { it.isFocusable }
        } else {
            null
        } ?: firstFocusableMovieRow() ?: movieHomeChip
        target.post { target.requestFocus() }
    }

    /** First focusable row in the Movies results (section labels are not
     * focusable, so child 0 is not always a row). */
    private fun firstFocusableMovieRow(): View? {
        for (i in 0 until movieResults.childCount) {
            val child = movieResults.getChildAt(i)
            if (child.isFocusable) return child
        }
        return null
    }

    private fun showMoviesDetails() {
        moviesListPane.visibility = View.GONE
        moviesDetailsPane.visibility = View.VISIBLE
        // The focused row just went GONE — without a landing, D-pad focus
        // is lost and the buttons become unreachable. Fav is always
        // enabled, so it is the safe landing; Play takes focus once the
        // streams load (see loadMovieStreams).
        movieFavButton.post { movieFavButton.requestFocus() }
    }

    /** One-tap quick search: fills the search box and runs the search. */
    private fun quickSearchMovie(term: String) {
        movieSearchInput.setText(term)
        searchMovies()
    }

    private fun searchMovies() {
        val query = movieSearchInput.text.toString().trim()
        if (query.isEmpty()) {
            toast("Movie ka naam likhein.")
            return
        }
        movieResultsAreSearch = true
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
        val rows = mutableListOf<View>()
        for (movie in movies) {
            val row = movieRow(movie)
            rows += row
            movieResults.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        linkMovieRowActions(rows)
        // UP from the first row reaches the quick chips (geometry alone
        // is unreliable across the scroller boundary).
        rows.firstOrNull()?.nextFocusUpId = movieHomeChip.id
        movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
        movieResults.getChildAt(0)?.requestFocus()
    }

    /** Links each movie row's action button (♥ fav / ✕ remove) up and down
     * to the neighbouring ROWS — without this, D-pad focus falls down the
     * action column and the rows become unreachable. LEFT/RIGHT between a
     * row and its own action is wired in movieRow/continueRow. */
    private fun linkMovieRowActions(rows: List<View>) {
        for (i in rows.indices) {
            val row = rows[i] as? LinearLayout ?: continue
            val action = (0 until row.childCount).mapNotNull { row.getChildAt(it) }
                .firstOrNull { it.tag == "rowAction" } ?: continue
            if (i > 0) action.nextFocusUpId = rows[i - 1].id
            if (i < rows.size - 1) action.nextFocusDownId = rows[i + 1].id
        }
    }

    /** Movies home: Continue Watching (top) + Fav Movies, shown when the
     * section opens and whenever no search results are on screen. */
    private fun showMoviesHome() {
        movieResultsAreSearch = false
        movieProgress.visibility = View.GONE
        movieResults.removeAllViews()
        val rows = mutableListOf<View>()
        val continuing = movieStore.continueWatching()
        val favs = movieStore.favoritesList()
        if (continuing.isEmpty() && favs.isEmpty()) {
            movieStatus.text = "Abhi koi Continue Watching ya Fav Movie nahi hai — neeche search karke movie dekhein."
        } else {
            movieStatus.text = "Continue Watching · Fav Movies"
        }
        if (continuing.isNotEmpty()) {
            movieResults.addView(sectionLabel("▶ Continue Watching"))
            for (entry in continuing) {
                val row = continueRow(entry)
                rows += row
                movieResults.addView(row,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    })
            }
        }
        if (favs.isNotEmpty()) {
            movieResults.addView(sectionLabel("♥ Fav Movies"))
            for (movie in favs) {
                val row = movieRow(movie)
                rows += row
                movieResults.addView(row,
                    LinearLayout.LayoutParams(-1, -2).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(4)
                    })
            }
        }
        linkMovieRowActions(rows)
        // UP from the first row reaches the quick chips.
        rows.firstOrNull()?.nextFocusUpId = movieHomeChip.id
        movieResultsScroll.post { movieResultsScroll.scrollTo(0, 0) }
    }

    private fun sectionLabel(text: String) =
        label(text, if (isTv) 17 else 16, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        }

    /** Continue Watching row: poster + title + remaining time + a cyan
     * progress bar, with an ✕ button to remove it by hand. Tapping the
     * row resumes the movie where it stopped. */
    private fun continueRow(entry: MovieStore.ContinueEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 96 else 88)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rowBackground(false)
            elevation = dp(3).toFloat()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { resumeMovie(entry) }
        }
        val posterWidth = dp(if (isTv) 60 else 56)
        val posterHeight = dp(if (isTv) 90 else 84)
        row.addView(
            posterTile(entry.posterUrl, entry.title),
            LinearLayout.LayoutParams(posterWidth, posterHeight),
        )
        val texts = column()
        texts.addView(TextView(this).apply {
            text = entry.title
            textSize = if (isTv) 17f else 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = "${(entry.progress * 100).toInt()}% dekhi · ${formatTime(entry.remainingMs)} baaki"
            textSize = 13f
            setTextColor(muted2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = (entry.progress * 100).toInt()
            progressTintList = ColorStateList.valueOf(cyan)
            progressBackgroundTintList = ColorStateList.valueOf(glassHi)
        }
        texts.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply {
            topMargin = dp(6)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        val removeButton = iconButton("✕") {
            movieStore.removeContinue(entry.subjectId)
            toast("Continue Watching se nikal di.")
            showMoviesHome()
        }
        // D-pad: RIGHT from the row reaches the remove button (it sits
        // INSIDE the row, so geometry alone never finds it); UP/DOWN
        // from the button go to the neighbouring rows (linkMovieRowActions).
        removeButton.id = View.generateViewId()
        removeButton.tag = "rowAction"
        row.nextFocusRightId = removeButton.id
        removeButton.nextFocusLeftId = row.id
        row.addView(removeButton, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
        return row
    }

    /** 2:3 poster tile with a letter tile behind it while the image loads. */
    private fun posterTile(posterUrl: String, title: String): View {
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = title.take(1).uppercase()
            textSize = if (isTv) 22f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (posterUrl.isNotEmpty()) {
            val posterView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tileView.addView(posterView, FrameLayout.LayoutParams(-1, -1))
            LogoLoader.load(this, posterUrl, posterView)
        }
        return tileView
    }

    /** Small square icon button (♥ favorite, ✕ remove) for movie rows. */
    private fun iconButton(value: String, action: (Button) -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        setTextColor(muted)
        textSize = if (isTv) 17f else 16f
        minimumWidth = 0
        minimumHeight = dp(44)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = glassStateList(12)
        setOnClickListener { action(this) }
    }

    /** Resume a Continue Watching movie: the signed stream URL is never
     * stored (it expires within hours), so details + streams are resolved
     * fresh from the subjectId, then the movie plays at the saved
     * position — the same quality as before when it is still available. */
    private fun resumeMovie(entry: MovieStore.ContinueEntry) {
        val request = ++movieResumeRequest
        movieProgress.visibility = View.VISIBLE
        movieStatus.text = "Movie load ho rahi hai…"
        imports.execute {
            try {
                val details = movieBox.details(entry.subjectId)
                val streams = movieBox.streams(details)
                runOnUiThread {
                    if (isDestroyed || request != movieResumeRequest) return@runOnUiThread
                    movieProgress.visibility = View.GONE
                    val stream = streams.firstOrNull { it.resolution == entry.quality }
                        ?: streams.first()
                    selectedQuality = stream.resolution
                    closeMovies()
                    playMovieStream(
                        details.movie.copy(title = entry.title),
                        stream.url, stream.headers, entry.positionMs,
                    )
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isDestroyed || request != movieResumeRequest) return@runOnUiThread
                    movieProgress.visibility = View.GONE
                    movieStatus.text = "Resume nahi ho saka: ${error.message}"
                }
            }
        }
    }

    /** Favorite toggle from the details pane (the movie itself, not the
     * selected dub — a dub is the same movie in another language). */
    private fun toggleFavoriteMovie() {
        val details = currentMovieDetails ?: return
        val nowFav = movieStore.toggleFavorite(details.movie)
        updateMovieFavButton()
        toast(if (nowFav) "Fav Movies mein add ho gayi." else "Fav Movies se nikal di.")
    }

    private fun updateMovieFavButton() {
        val movie = currentMovieDetails?.movie
        val fav = movie != null && movieStore.isFavorite(movie)
        movieFavButton.text = if (fav) "♥ Favorite" else "♡ Favorite"
    }

    /**
     * Saves the playing movie's position to Continue Watching. The
     * position is read here on the main thread (ExoPlayer's thread); the
     * store write runs on `imports` for the periodic tick (async) and
     * synchronously for lifecycle hooks that run right before the player
     * is released (sync) — either way the player is never blocked. A
     * movie past 95% is finished: the store drops it instead of saving.
     */
    private fun saveMovieProgress(async: Boolean) {
        val movie = playingMovie ?: return
        if (movie.subjectId.isEmpty()) return
        val controller = playerController ?: return
        val position = controller.position()
        val duration = controller.duration()
        if (duration <= 0 || position < MIN_SAVE_POSITION_MS) return
        val entry = MovieStore.ContinueEntry(
            subjectId = movie.subjectId,
            title = movie.title,
            posterUrl = movie.posterUrl,
            positionMs = position,
            durationMs = duration,
            quality = selectedQuality,
            updatedAt = System.currentTimeMillis(),
        )
        if (async) {
            imports.execute { movieStore.saveProgress(entry) }
        } else {
            movieStore.saveProgress(entry)
        }
    }

    /** Glass movie card: 2:3 poster (letter tile behind it while it loads),
     * bold title + year, and a cyan HINDI badge for Hindi-dubbed titles. */
    private fun movieRow(movie: MovieBox.Movie): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 96 else 88)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rowBackground(false)
            elevation = dp(3).toFloat()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            // Remember this row so Back from the details pane returns to it.
            setOnClickListener {
                moviesReturnRow = movieResults.indexOfChild(this)
                openMovieDetails(movie)
            }
        }
        val posterWidth = dp(if (isTv) 60 else 56)
        val posterHeight = dp(if (isTv) 90 else 84)
        row.addView(
            posterTile(movie.posterUrl, movie.title),
            LinearLayout.LayoutParams(posterWidth, posterHeight),
        )
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
                setTextColor(muted2)
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
        // Favorite heart: red when this movie is in Fav Movies. Tapping
        // it only toggles the favorite — the row itself still opens the
        // details pane.
        val heart = iconButton(if (movieStore.isFavorite(movie)) "♥" else "♡") { btn ->
            val nowFav = movieStore.toggleFavorite(movie)
            btn.text = if (nowFav) "♥" else "♡"
            btn.setTextColor(if (nowFav) liveRed else muted)
            toast(if (nowFav) "Fav Movies mein add ho gayi." else "Fav Movies se nikal di.")
            if (moviesOpen && !movieResultsAreSearch) showMoviesHome()
        }
        heart.setTextColor(if (movieStore.isFavorite(movie)) liveRed else muted)
        // D-pad: RIGHT from the row reaches the heart (it sits INSIDE the
        // row, so geometry alone never finds it); UP/DOWN from the heart
        // go to the neighbouring rows (linkMovieRowActions).
        heart.id = View.generateViewId()
        heart.tag = "rowAction"
        row.nextFocusRightId = heart.id
        heart.nextFocusLeftId = row.id
        row.addView(heart, LinearLayout.LayoutParams(-2, -2).apply {
            marginStart = dp(8)
        })
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
        updateMovieFavButton()
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
        updateMovieFavButton()
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
            // If the user hasn't moved off the Fav landing yet, put focus
            // on Play now that it works.
            if (movieFavButton.hasFocus()) moviePlayButton.requestFocus()
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
        // If the user hasn't moved off the Fav landing yet, put focus
        // on Play now that it works.
        if (movieFavButton.hasFocus()) moviePlayButton.requestFocus()
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
        // The played subject is the SELECTED DUB (a dub is a different
        // subject with its own files) — Continue Watching must resume the
        // same audio the user actually played, not the original.
        val played = details.movie.copy(
            subjectId = selectedDubId ?: details.movie.subjectId,
            title = currentPlayTitle,
        )
        playMovieStream(played, stream.url, stream.headers)
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
        // A new movie replaces the old one — save the old movie's place
        // in Continue Watching before the player switches streams.
        saveMovieProgress(async = false)
        movieProgressTick = 0
        stopWeb()
        selected = null
        playingMovie = movie
        playingMovieUrl = url
        playingMovieHeaders = headers
        playingDrama = null
        movieEnded = false
        setMovieMeta(movie)
        val showBars = !(fullScreen && !isTv)
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
        showPlayerControls()
        // A movie hides the browse pane on side-by-side screens — focus
        // moves to the play/pause button, since the list is gone.
        updateBrowseVisibility()
        if (isTv || isLandscape) playPauseButton.requestFocus()
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

    // ---- Geo Dramas (v17) ----
    //
    // A full-screen section beside Live TV (same overlay pattern as
    // Movies): Har Pal Geo drama list (thumbnail + episode count + latest
    // episode) → episode list (newest first) → Play in the main player.
    // The list shows immediately in playlists-tab order; a background pass
    // fetches each drama's latest-episode date and re-sorts, so the drama
    // with the newest episode sits on top. New episodes appear by
    // themselves: the section re-reads YouTube (6h disk cache) whenever it
    // is opened after the cache expires, and Refresh forces a fresh read.
    // All GeoDramas calls run off the main thread; stale results are
    // dropped with a per-request counter, the same pattern as playEmbed.

    private fun buildDramasView(): LinearLayout {
        val view = column().apply {
            // TV overscan: 32dp sides so nothing is cut (same as the
            // main layout's root padding).
            setPadding(dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12),
                dp(if (isTv) 32 else 16), dp(if (isTv) 20 else 12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(bg0, bg1, bg2),
            )
            // Clickable so touches don't fall through — but NOT focusable
            // (a focusable full-screen root traps D-pad focus; see Movies).
            isClickable = true
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(button("‹ Live TV") { closeDramas() },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(12) })
        // Har Pal Geo avatar (loaded from YouTube) over a "G" tile fallback.
        val avatarTile = FrameLayout(this)
        val avatarInitial = TextView(this).apply {
            text = "G"
            textSize = if (isTv) 20f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
        }
        avatarTile.addView(avatarInitial, FrameLayout.LayoutParams(-1, -1))
        dramaAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        avatarTile.addView(dramaAvatar, FrameLayout.LayoutParams(-1, -1))
        header.addView(avatarTile, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginEnd = dp(10)
        })
        header.addView(label("Geo Dramas", if (isTv) 26 else 22, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            letterSpacing = 0.02f
        })
        view.addView(header)
        // Now-playing bar: when an episode is already playing, the section
        // says so and offers a shortcut back to the player.
        dramaNowPlayingBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassShape(16)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        dramaNowPlayingLabel = TextView(this).apply {
            textSize = if (isTv) 15f else 14f
            setTextColor(textColor)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        dramaNowPlayingBar.addView(dramaNowPlayingLabel, LinearLayout.LayoutParams(0, -2, 1f))
        dramaNowPlayingBar.addView(button("▶ Player") { closeDramas() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        view.addView(dramaNowPlayingBar, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

        // ---- Drama list pane ----
        dramasListPane = column()
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dramaStatus = label("Geo ke latest dramas — Har Pal Geo (YouTube).", if (isTv) 15 else 13, muted)
        statusRow.addView(dramaStatus, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(button("↻ Refresh") { loadDramas(forceRefresh = true) },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        dramasListPane.addView(statusRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12)
            bottomMargin = dp(4)
        })
        dramaProgress = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(cyan)
        }
        dramasListPane.addView(dramaProgress, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        dramaResults = column()
        dramaResultsScroll = ScrollView(this).apply { addView(dramaResults) }
        dramasListPane.addView(dramaResultsScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(dramasListPane, LinearLayout.LayoutParams(-1, 0, 1f))

        // ---- Episodes pane ----
        dramasEpisodesPane = column().apply { visibility = View.GONE }
        dramasEpisodesPane.addView(button("‹ Dramas") { showDramasList() },
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            })
        dramaEpisodesTitle = TextView(this).apply {
            textSize = if (isTv) 24f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        dramasEpisodesPane.addView(dramaEpisodesTitle)
        dramaEpisodesMeta = label("", if (isTv) 15 else 13, muted)
        dramasEpisodesPane.addView(dramaEpisodesMeta)
        dramaEpisodesStatus = label("", if (isTv) 15 else 13, muted)
        dramasEpisodesPane.addView(dramaEpisodesStatus)
        dramaEpisodesList = column()
        dramaEpisodesScroll = ScrollView(this).apply { addView(dramaEpisodesList) }
        dramasEpisodesPane.addView(dramaEpisodesScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        view.addView(dramasEpisodesPane, LinearLayout.LayoutParams(-1, 0, 1f))
        return view
    }

    private fun openDramas() {
        ensureDramasView()
        dramasOpen = true
        dramasView.visibility = View.VISIBLE
        // Now-playing bar: show it only when an episode is actually playing.
        val drama = playingDrama
        if (drama != null) {
            dramaNowPlayingLabel.text = "▶ Abhi chal raha hai: $playingDramaTitle — ${drama.shortTitle}"
            dramaNowPlayingBar.visibility = View.VISIBLE
        } else {
            dramaNowPlayingBar.visibility = View.GONE
        }
        showDramasList()
        // Reload when the in-memory list is stale (6h, matching the disk
        // cache) so new episodes show up by themselves day after day.
        if (currentDramas.isEmpty() ||
            System.currentTimeMillis() - dramasLoadedAt > DRAMA_REFRESH_AFTER_MS) {
            loadDramas()
        } else {
            loadDramaAvatar()
        }
    }

    private fun closeDramas() {
        dramasOpen = false
        dramasView.visibility = View.GONE
        // An episode keeps playing underneath when the browser is closed.
        focusBrowse()
    }

    private fun showDramasList() {
        dramasEpisodesPane.visibility = View.GONE
        dramasListPane.visibility = View.VISIBLE
        dramaResults.getChildAt(0)?.requestFocus()
    }

    private fun showDramaEpisodesPane() {
        dramasListPane.visibility = View.GONE
        dramasEpisodesPane.visibility = View.VISIBLE
    }

    /** Loads the Har Pal Geo avatar into the header icon (parsed from the
     * playlists response — no extra request). No-op until dramas() ran once. */
    private fun loadDramaAvatar() {
        val url = geoDramas.channelAvatarUrl
        if (url.isNotEmpty()) LogoLoader.load(this, url, dramaAvatar)
    }

    private fun loadDramas(forceRefresh: Boolean = false) {
        val request = ++dramaListRequest
        dramaProgress.visibility = View.VISIBLE
        dramaStatus.text = "Dramas load ho rahe hain…"
        dramaResults.removeAllViews()
        imports.execute {
            try {
                if (forceRefresh) geoDramas.clearCache()
                val dramas = geoDramas.dramas()
                runOnUiThread {
                    if (isDestroyed || request != dramaListRequest) return@runOnUiThread
                    dramaProgress.visibility = View.GONE
                    loadDramaAvatar()
                    if (dramas.isEmpty()) {
                        dramaStatus.text = "Dramas nahi mile — internet check karke Refresh dabayein."
                    } else {
                        currentDramas = dramas
                        dramasLoadedAt = System.currentTimeMillis()
                        dramaStatus.text = "${dramas.size} dramas — sab se nayi episode wala upar aayega."
                        showDramaRows(dramas)
                        refreshDramaDates(request)
                    }
                }
            } catch (error: IOException) {
                runOnUiThread {
                    if (isDestroyed || request != dramaListRequest) return@runOnUiThread
                    dramaProgress.visibility = View.GONE
                    dramaStatus.text = "Dramas nahi mile: ${error.message}"
                }
            }
        }
    }

    /**
     * Background re-sort: fetches each drama's latest-episode date (one
     * playlist page per drama — each fetch also warms that drama's episode
     * cache, so opening it later is instant) and re-sorts the list once,
     * newest episode on top. Runs on the dedicated `dramaDates` executor
     * so it never blocks a user's episode tap. The list only rebuilds when
     * the order actually changed, so D-pad focus is not disturbed.
     */
    private fun refreshDramaDates(listRequest: Int) {
        val request = ++dramaDatesRequest
        dramaDates.execute {
            val dated = mutableListOf<GeoDramas.Drama>()
            for (drama in currentDramas) {
                if (isDestroyed || request != dramaDatesRequest || listRequest != dramaListRequest) return@execute
                val episodes = geoDramas.episodes(drama.playlistId)
                val latestMs = episodes.maxOfOrNull { it.publishedMs } ?: 0L
                val latest = episodes.firstOrNull()
                val label = when {
                    latest == null -> ""
                    latest.dateLabel.isNotEmpty() -> "${latest.shortTitle} · ${latest.dateLabel}"
                    else -> latest.shortTitle
                }
                dated += drama.copy(latestMs = latestMs, latestLabel = label)
            }
            runOnUiThread {
                if (isDestroyed || request != dramaDatesRequest || listRequest != dramaListRequest) return@runOnUiThread
                val before = currentDramas.map { it.playlistId }
                currentDramas = dated.sortedWith(
                    compareByDescending<GeoDramas.Drama> { it.latestMs > 0 }
                        .thenByDescending { it.latestMs },
                )
                if (dramasOpen && dramasListPane.visibility == View.VISIBLE &&
                    currentDramas.map { it.playlistId } != before) {
                    showDramaRows(currentDramas)
                }
            }
        }
    }

    private fun showDramaRows(dramas: List<GeoDramas.Drama>) {
        dramaResults.removeAllViews()
        for (drama in dramas) {
            dramaResults.addView(dramaRow(drama), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        dramaResultsScroll.post { dramaResultsScroll.scrollTo(0, 0) }
        dramaResults.getChildAt(0)?.requestFocus()
    }

    /** Glass drama card: 16:9 thumbnail (letter tile behind it while it
     * loads), bold title, episode count + latest-episode label. */
    private fun dramaRow(drama: GeoDramas.Drama): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 96 else 88)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rowBackground(false)
            elevation = dp(3).toFloat()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { openDramaEpisodes(drama) }
        }
        val thumbWidth = dp(if (isTv) 144 else 128)
        val thumbHeight = dp(if (isTv) 81 else 72)
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = drama.title.take(1).uppercase()
            textSize = if (isTv) 22f else 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        if (drama.thumbnailUrl.isNotEmpty()) {
            val thumbView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            tileView.addView(thumbView, FrameLayout.LayoutParams(-1, -1))
            LogoLoader.load(this, drama.thumbnailUrl, thumbView)
        }
        row.addView(tileView, LinearLayout.LayoutParams(thumbWidth, thumbHeight))
        val texts = column()
        texts.addView(TextView(this).apply {
            text = drama.title
            textSize = if (isTv) 17f else 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val metaParts = mutableListOf<String>()
        if (drama.episodeCount > 0) metaParts += "${drama.episodeCount} episodes"
        if (drama.latestLabel.isNotEmpty()) metaParts += drama.latestLabel
        if (metaParts.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = metaParts.joinToString(" · ")
                textSize = 13f
                setTextColor(muted2)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        return row
    }

    private fun openDramaEpisodes(drama: GeoDramas.Drama) {
        val request = ++dramaEpisodesRequest
        dramaEpisodesTitle.text = drama.title
        dramaEpisodesMeta.text =
            if (drama.episodeCount > 0) "${drama.episodeCount} episodes" else ""
        dramaEpisodesStatus.text = "Episodes load ho rahe hain…"
        dramaEpisodesList.removeAllViews()
        showDramaEpisodesPane()
        imports.execute {
            val episodes = geoDramas.episodes(drama.playlistId)
            runOnUiThread {
                if (isDestroyed || request != dramaEpisodesRequest) return@runOnUiThread
                if (episodes.isEmpty()) {
                    dramaEpisodesStatus.text = "Episodes nahi mile — internet check karke dobara kholein."
                } else {
                    dramaEpisodesStatus.text = "${episodes.size} episodes — sab se nayi upar hai."
                    showDramaEpisodes(drama, episodes)
                }
            }
        }
    }

    private fun showDramaEpisodes(drama: GeoDramas.Drama, episodes: List<GeoDramas.Episode>) {
        dramaEpisodesList.removeAllViews()
        for (episode in episodes) {
            dramaEpisodesList.addView(episodeRow(drama, episode), LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            })
        }
        dramaEpisodesScroll.post { dramaEpisodesScroll.scrollTo(0, 0) }
        dramaEpisodesList.getChildAt(0)?.requestFocus()
    }

    /** Glass episode card: small 16:9 thumbnail, "Episode N", air date +
     * views. Tap plays the episode in the main player. */
    private fun episodeRow(drama: GeoDramas.Drama, episode: GeoDramas.Episode): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            minimumHeight = dp(if (isTv) 80 else 72)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rowBackground(false)
            elevation = dp(3).toFloat()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { playDramaEpisode(drama, episode) }
        }
        val thumbWidth = dp(if (isTv) 112 else 104)
        val thumbHeight = dp(if (isTv) 63 else 59)
        val tileView = FrameLayout(this)
        val initial = TextView(this).apply {
            text = episode.shortTitle.take(1).uppercase()
            textSize = if (isTv) 20f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = shape(glassHi, glassHi, 12)
        }
        tileView.addView(initial, FrameLayout.LayoutParams(-1, -1))
        val thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        tileView.addView(thumbView, FrameLayout.LayoutParams(-1, -1))
        LogoLoader.load(this, "https://i.ytimg.com/vi/${episode.videoId}/hqdefault.jpg", thumbView)
        row.addView(tileView, LinearLayout.LayoutParams(thumbWidth, thumbHeight))
        val texts = column()
        texts.addView(TextView(this).apply {
            text = episode.shortTitle
            textSize = if (isTv) 16f else 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val metaParts = mutableListOf<String>()
        if (episode.dateLabel.isNotEmpty()) metaParts += episode.dateLabel
        if (episode.views.isNotEmpty()) metaParts += episode.views
        if (metaParts.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = metaParts.joinToString(" · ")
                textSize = 13f
                setTextColor(muted2)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(12)
        })
        return row
    }

    private fun playDramaEpisode(drama: GeoDramas.Drama, episode: GeoDramas.Episode) {
        closeDramas()
        startDramaPlayback(episode, drama.title)
    }

    /**
     * Plays a drama episode in the main player, with the same setup as
     * [play] but without touching the channel store: no recent entry, no
     * last-channel — an episode is not a channel. The episode plays as a
     * WebView embed (loop off, so it ends instead of replaying), and the
     * ExoPlayer controls stay hidden exactly as for web channels.
     */
    private fun startDramaPlayback(episode: GeoDramas.Episode, dramaTitle: String) {
        // An episode replaces a movie — save the movie's place first.
        saveMovieProgress(async = false)
        stopWeb()
        selected = null
        playingMovie = null
        playingMovieUrl = null
        playingMovieHeaders = emptyMap()
        playingDrama = episode
        playingDramaTitle = dramaTitle
        setDramaMeta(episode, dramaTitle)
        val showBars = !(fullScreen && !isTv)
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
        updateBrowseVisibility()
        playerController?.stop()
        playEmbed(episode.videoId, loop = false)
        bringPlayerOverlaysToFront()
        showPlayerControls()
    }

    private fun setDramaMeta(episode: GeoDramas.Episode, dramaTitle: String) {
        val name = "$dramaTitle — ${episode.shortTitle}"
        metaName.text = name
        infoName.text = name
        metaCategory.text = "Geo Dramas"
        metaCategory.visibility = View.VISIBLE
        metaLiveBadge.visibility = View.GONE
        infoLiveBadge.visibility = View.GONE
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

    /** Small round TopBar action button (TV): a glass circle with a
     * glyph, cyan ring on D-pad focus. */
    private fun topRoundButton(glyph: String, description: String, action: () -> Unit) =
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
        setOnFocusChangeListener { _, hasFocus ->
            refreshFilterText(this)
            animateFocusScale(this, hasFocus)
        }
        setOnClickListener { action() }
    }

    /** White text on the active (gradient) or focused filter, muted otherwise. */
    private fun refreshFilterText(view: Button) {
        view.setTextColor(if (view.isSelected || view.isFocused) Color.WHITE else muted)
    }

    /** Subtle lift on focus (TV remote + keyboard): the focused item scales
     * up slightly, so it visibly rises above its neighbours. */
    private fun animateFocusScale(view: View, focused: Boolean) {
        val scale = if (focused) 1.03f else 1f
        view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }

    /** Round seek thumb: white dot with a cyan ring (YouTube-style), sized
     * for touch on phones and smaller on TV. */
    private fun seekThumb() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
        setStroke(dp(2), cyan)
        setSize(dp(if (isTv) 16 else 20), dp(if (isTv) 16 else 20))
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
     * re-raise the border, meta chip, seek row, overlay controls, corner
     * buttons and number popup above them. Called at the end of [play] and
     * after adding a WebView. */
    private fun bringPlayerOverlaysToFront() {
        screenBorder.bringToFront()
        metaChip.bringToFront()
        seekRow.bringToFront()
        overlayBar.bringToFront()
        favButton.bringToFront()
        retryButton.bringToFront()
        qualityButton.bringToFront()
        numberOverlay.bringToFront()
        status.bringToFront()
        buffering.bringToFront()
    }

    /** Glass fill: 3-stop vertical gradient (light top edge → GlassHi →
     * Glass) + 1dp stroke. The light top edge is what makes the glass
     * read as a raised panel instead of a flat fill. */
    private fun glassShape(radiusDp: Int = 22, strokeColor: Int = glassStroke, strokeDp: Int = 1) =
        GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xa832468f.toInt(), glassHi, glass),
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

    /** TV category item: transparent at rest, gradient pill when selected
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
        letterSpacing = 0.08f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(4), dp(11), dp(4))
        background = shape(liveRed, liveRed, 8, 0)
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
        private const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
        private const val DRAMA_REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
        private const val CONTROLS_HIDE_DELAY_MS = 3000L
        private const val STATE_FULLSCREEN = "fullscreen"
        private const val STATE_MOVIE_URL = "movie_url"
        private const val STATE_MOVIE_TITLE = "movie_title"
        private const val STATE_MOVIE_POS = "movie_pos"
        private const val STATE_MOVIE_HEADERS = "movie_headers"
        private const val STATE_DRAMA_VIDEO = "drama_video"
        private const val STATE_DRAMA_TITLE = "drama_title"
        private const val STATE_DRAMA_EPISODE = "drama_episode"
        private const val STATE_DRAMA_EP_NUM = "drama_ep_num"
        // YouTube live re-resolve poll: while a yt-live channel plays,
        // re-check /live this often and rejoin when the video ID changes
        // (the broadcaster restarted the stream).
        private const val YT_LIVE_POLL_MS = 5 * 60 * 1000L
        // Continue Watching: ignore positions under 5s (a fresh start is
        // not "continue watching"), and save every 20 seek ticks (20 ×
        // 500ms ≈ 10s) while a movie plays.
        private const val MIN_SAVE_POSITION_MS = 5_000L
        private const val MOVIE_PROGRESS_SAVE_TICKS = 20
    }
}
