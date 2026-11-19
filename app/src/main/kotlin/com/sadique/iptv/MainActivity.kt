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
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
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
    // Group-titles that have their own category. A channel whose
    // group matches none of these falls under General (the catch-all), so
    // no channel is reachable only through "All". "cricket" and
    // "religious" stay as legacy matches for imported playlists.
    internal val categoryGroups = setOf(
        "news", "sports", "cricket", "islamic", "religious",
        "kids", "animation",
    )
    internal val imports = Executors.newSingleThreadExecutor()
    internal val mainHandler = Handler(Looper.getMainLooper())
    internal lateinit var store: ChannelStore
    internal lateinit var playlistStore: PlaylistStore
    internal lateinit var list: LinearLayout
    internal lateinit var screen: FrameLayout
    internal lateinit var screenBorder: View
    internal lateinit var metaChip: LinearLayout
    internal lateinit var metaName: TextView
    internal lateinit var metaCategory: TextView
    // The LIVE badges (meta chip + info bar) are hidden while a movie plays
    // — a movie is not live — and shown again for channels.
    internal lateinit var metaLiveBadge: TextView
    internal lateinit var infoLiveBadge: TextView
    internal lateinit var overlayBar: LinearLayout
    internal lateinit var status: TextView
    internal lateinit var buffering: ProgressBar
    internal lateinit var numberOverlay: TextView
    internal lateinit var listScroll: ScrollView
    internal lateinit var libraryPane: LinearLayout
    internal lateinit var rootView: LinearLayout
    internal lateinit var headerView: LinearLayout
    private lateinit var playerPane: LinearLayout
    // The split container (browse pane + player pane). Kept as a field
    // so onConfigurationChanged can re-lay it out on rotation without
    // recreating the activity (see applyOrientationLayout).
    private lateinit var splitPane: LinearLayout
    private var rootPaddingPx = 0
    private var paneGapPx = 0
    internal lateinit var infoBar: LinearLayout
    internal lateinit var infoName: TextView
    // On-video corner buttons (the tiles under the player are gone — the
    // user asked for the buttons ON the screen itself): Favorite
    // top-right and Quality bottom-right. Speed lives in
    // the overlay pill (movies only — channels never need it).
    internal lateinit var favButton: Button
    internal lateinit var qualityButton: Button
    internal lateinit var remainingLabel: TextView
    internal lateinit var seekRow: LinearLayout
    internal lateinit var seekBar: SeekBar
    internal lateinit var seekLabel: TextView
    internal lateinit var seekIndicator: TextView
    // YouTube-style on-video controls (touch only): tapping the video shows
    // the overlay pill + seek bar, and they auto-hide after 3 seconds. On TV
    // the controls always stay visible (D-pad navigation needs them).
    internal var controlsVisible = true
    internal val hideControlsRunnable = Runnable { hidePlayerControls() }
    internal val seekIndicatorHideRunnable = Runnable { seekIndicator.visibility = View.GONE }
    internal lateinit var playPauseButton: Button
    internal lateinit var fullscreenButton: ImageButton
    private lateinit var fullscreenIcon: FullscreenIconDrawable
    internal lateinit var catalogTitle: TextView
    // Shown under the catalog title while a search query is active: the
    // query text + a button that clears it and restores the full list.
    internal lateinit var searchClearRow: LinearLayout
    internal lateinit var searchClearLabel: TextView
    // Search bar at the top of the browse pane, as wide as the list —
    // the channel search lives where the channels are (the TopBar pill
    // stays as the second way in).
    private lateinit var paneSearchBar: LinearLayout
    internal lateinit var paneSearchLabel: TextView
    internal var playerController: PlayerController? = null
    internal var web: WebView? = null
    // Transparent tap catcher above an iframe embed (below the app's own
    // overlay controls): taps otherwise reach YouTube, which pops its
    // "more videos" overlay over the channel. Direct web players have no
    // catcher — their taps must reach the page (Cloudflare check).
    internal var webTapCatcher: View? = null
    internal var selected: Channel? = null
    internal var imported = emptyList<Channel>()
    internal var filter = "All"
        set(value) {
            field = value
            // Keep the categories pane / chip strip in sync when the filter
            // changes from a dialog or a playlist action instead of a tap.
            markActiveFilter()
        }
    internal var query = ""
    // TV browse levels: the left pane shows either the categories or the
    // channels of the picked category — never both (the old sidebar is
    // gone). Touch devices have no levels; they always stay on CHANNELS.
    internal var browseLevel = BrowseLevel.CHANNELS
    internal lateinit var categoryListScroll: ScrollView // TV only
    // True once a playing movie has ended (it still counts as the
    // current movie for Retry, but the browse pane comes back).
    internal var movieEnded = false
    internal val channelBackups by lazy {
        assets.open("channel-backups.tsv").bufferedReader().useLines { lines ->
            lines.associate { line ->
                val (name, primary, alternate) = line.split('\t')
                (name + "\u0000" + primary) to alternate
            }
        }
    }
    internal var verifiedChannels = emptyList<Channel>()
    internal var playbackRequest = 0
    // YouTube live (yt-live:) tracking: the token to re-resolve when the
    // stream ends or restarts, and the video ID currently in the embed
    // (so a re-resolve that returns the same ID does not reload).
    internal var playingYtLive: String? = null
    internal var playingEmbedId: String? = null
    // Main-frame navigation is blocked for embeds — except these hosts,
    // and only while a direct web: player is showing (playsza + its
    // Cloudflare check need real top-level navigation).
    internal var allowMainFrameHosts: Set<String> = emptySet()
    internal val ytLivePollRunnable = Runnable { pollYtLive() }
    internal var fullScreen = false
    internal var highlightedRow: View? = null
    internal var currentMatches = emptyList<Channel>()
    // Pakistani entertainment channels pinned to the top of the All /
    // General lists in the user's own order — 1-5 are his picks, the
    // rest by popularity. Names must match the M3U exactly.
    private val pinnedPkOrder = listOf(
        "Green Entertainment", "ARY Digital", "Hum TV", "Geo Entertainment", "Aan TV", "MUN TV",
        "Express Entertainment", "Hum Sitaray", "A Plus", "TV One", "PTV Home",
        "Play Entertainment", "Bol Entertainment HD", "Apna Channel", "8XM",
        "aurLife HD", "LTN Family", "M Entertainment", "PTV Global",
        "InPlus Pakistan", "Falak TV", "JooMusic", "Discover Pakistan",
        "Paigham Television",
    )
    internal val pinnedPkRank = pinnedPkOrder.withIndex().associate { it.value to it.index }
    internal var videoOptions = emptyList<PlayerController.VideoOption>()
    internal var selectedVideoIndex = -1
    internal var seekable = false
    internal var scrubbing = false
    internal var numberBuffer = ""
    internal var numberTimeout: Runnable? = null
    private var wakeKey = KeyEvent.KEYCODE_UNKNOWN
    private var stoppedForBackground = false
    internal var updateDownloadDialog: AlertDialog? = null

    // MovieBox movies (v1): a full-screen overlay over the Live TV layout.
    // Movies play in the SAME ExoPlayer and never touch the channel store —
    // a signed movie URL must never land in Recent or last-channel, or the
    // app would auto-play an expired link on the next launch.
    internal lateinit var movieBox: MovieBox
    internal lateinit var movieStore: MovieStore
    // The Movies/Dramas overlays build on first open, not at startup —
    // they are big view trees and the app opened on a black screen while
    // they inflated. Nothing touches them before the first open.
    internal lateinit var contentFrame: FrameLayout
    internal var moviesViewBuilt = false
    internal var dramasViewBuilt = false
    internal lateinit var moviesView: LinearLayout
    internal lateinit var moviesListPane: LinearLayout
    internal lateinit var moviesDetailsPane: LinearLayout
    internal lateinit var movieSearchInput: EditText
    // Home chip in the Movies quick row — the focus fallback when the
    // section opens with no rows (the search box is NOT the landing:
    // focusing it pops the keyboard and D-pad up/down can die on it).
    internal lateinit var movieHomeChip: Button
    // The Movies quick-chip row (Home + search shortcuts) — its chips'
    // DOWN targets are re-pointed at the first movie row on every
    // render (see syncMovieChipFocusTargets).
    internal lateinit var movieQuickRow: LinearLayout
    internal lateinit var movieStatus: TextView
    // Now-playing bar at the top of the Movies section: shown when a movie
    // is already playing, with a shortcut back to the player.
    internal lateinit var movieNowPlayingBar: LinearLayout
    internal lateinit var movieNowPlayingLabel: TextView
    internal lateinit var movieProgress: ProgressBar
    internal lateinit var movieResults: LinearLayout
    internal lateinit var movieResultsScroll: ScrollView
    internal lateinit var movieDetailsTitle: TextView
    internal lateinit var movieDetailsMeta: TextView
    internal lateinit var movieDetailsDesc: TextView
    internal lateinit var movieDubRow: LinearLayout
    internal lateinit var movieQualityRow: LinearLayout
    // Series details (v2.0.27): season chips + episode list in the same
    // details pane. Hidden for movies; the quality row is per-episode.
    internal lateinit var movieSeasonLabel: TextView
    internal lateinit var movieSeasonScroll: HorizontalScrollView
    internal lateinit var movieSeasonRow: LinearLayout
    internal lateinit var movieEpisodeLabel: TextView
    internal lateinit var movieEpisodeColumn: LinearLayout
    internal lateinit var moviePlayButton: Button
    internal lateinit var movieFavButton: Button
    internal lateinit var movieDetailsStatus: TextView
    internal var moviesOpen = false
    // True while search results are on screen; false while the Movies home
    // (Continue Watching + Fav Movies) is showing.
    internal var movieResultsAreSearch = false
    internal var movieResumeRequest = 0
    // seekUpdater ticks every 500ms; every 20th tick (~10s) the playing
    // movie's position is saved to Continue Watching.
    internal var movieProgressTick = 0
    internal var playingMovie: MovieBox.Movie? = null
    internal var playingMovieUrl: String? = null
    internal var playingMovieHeaders: Map<String, String> = emptyMap()
    private var savedMoviePosition = 0L
    internal var movieSearchRequest = 0
    // Index of the movie row the details pane opened from — Back from
    // details returns focus to it instead of the search box (-1: none).
    internal var moviesReturnRow = -1
    internal var movieDetailsRequest = 0
    internal var movieStreamsRequest = 0
    internal var currentMovieDetails: MovieBox.Details? = null
    internal var currentMovieStreams: List<MovieBox.MovieStream> = emptyList()
    internal var currentPlayTitle = ""
    internal var selectedDubId: String? = null
    internal var selectedQuality = 0
    // Series state (v2.0.27): the season/episode picked in details, the
    // episode actually playing (for Continue Watching), and the seasons
    // of the open series.
    internal var selectedSeason = 0
    internal var selectedEpisode = 0
    internal var playingEpisodeSeason = 0
    internal var playingEpisodeEpisode = 0
    internal var movieSeasonsRequest = 0
    internal var currentSeriesSeasons: List<MovieBox.Season> = emptyList()
    // Movies home feed (v2.0.27): New / Recommended / Series sections,
    // loaded once per app run in the background.
    internal var movieFeedNew = emptyList<MovieBox.Movie>()
    internal var movieFeedRecommended = emptyList<MovieBox.Movie>()
    internal var movieFeedSeries = emptyList<MovieBox.Movie>()
    internal var movieFeedLoaded = false
    internal var movieFeedLoading = false
    internal var movieFeedRequest = 0

    // Geo Dramas (v17): Har Pal Geo dramas from YouTube (GeoDramas client).
    // Same full-screen overlay pattern as Movies: drama list → episode
    // list → Play in the main player (WebView embed, loop off). Episodes
    // never touch the channel store — no recent entry, no last-channel.
    internal lateinit var geoDramas: GeoDramas
    internal lateinit var dramasView: LinearLayout
    internal lateinit var dramasListPane: LinearLayout
    internal lateinit var dramasEpisodesPane: LinearLayout
    internal lateinit var dramaAvatar: ImageView
    internal lateinit var dramaStatus: TextView
    internal lateinit var dramaProgress: ProgressBar
    internal lateinit var dramaResults: LinearLayout
    internal lateinit var dramaResultsScroll: ScrollView
    internal lateinit var dramaNowPlayingBar: LinearLayout
    internal lateinit var dramaNowPlayingLabel: TextView
    internal lateinit var dramaEpisodesTitle: TextView
    internal lateinit var dramaEpisodesMeta: TextView
    internal lateinit var dramaEpisodesStatus: TextView
    internal lateinit var dramaEpisodesList: LinearLayout
    internal lateinit var dramaEpisodesScroll: ScrollView
    internal var dramasOpen = false
    internal var playingDrama: GeoDramas.Episode? = null
    internal var playingDramaTitle = ""
    internal var dramaListRequest = 0
    internal var dramaEpisodesRequest = 0
    internal var dramaDatesRequest = 0
    internal var currentDramas: List<GeoDramas.Drama> = emptyList()
    internal var dramasLoadedAt = 0L
    // The latest-episode date refresh runs here — NOT on `imports` — so a
    // long background re-sort never queues behind (or blocks) a user's
    // episode tap or movie search.
    internal val dramaDates = Executors.newSingleThreadExecutor()

    // Adaptive UI: TV (D-pad, 10-foot) vs touch devices, and orientation.
    // isLandscape is refreshed in onConfigurationChanged; rotation no
    // longer recreates the activity (configChanges in the manifest), so
    // the player keeps playing across a rotation (v2.0.27).
    internal var isTv = false
    internal var isLandscape = false
    internal val filterChips = mutableMapOf<String, Button>()

    internal val seekUpdater = object : Runnable {
        override fun run() {
            val controller = playerController
            if (seekable && controller != null && !scrubbing) {
                val position = controller.position()
                val duration = controller.duration()
                if (duration > 0) {
                    seekBar.max = (duration / 1000).toInt()
                    seekBar.progress = (position / 1000).toInt()
                    updateMovieTimes(position, duration)
                }
                // Save the playing movie's position every ~10 seconds so
                // Continue Watching stays fresh without touching the player
                // thread — the store write itself runs on `imports`.
                if (playingMovie != null) {
                    movieProgressTick++
                    if (movieProgressTick >= MOVIE_PROGRESS_SAVE_TICKS) {
                        movieProgressTick = 0
                        this@MainActivity.saveMovieProgress(async = true)
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
        isTv = isTvDevice()

        // The fullscreen state is saved and restored here for a process
        // death (rotation itself no longer recreates the activity — see
        // onConfigurationChanged). Playback resumes from the last channel
        // below (live TV, no position to save); the screen never goes blank.
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
        splitPane = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        rootView.addView(splitPane, LinearLayout.LayoutParams(-1, 0, 1f))
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
            splitPane.addView(left, LinearLayout.LayoutParams(dp(libraryWidthDp), -1))
            playerPane.setPadding(dp(paneGapDp), 0, 0, 0)
            splitPane.addView(playerPane, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            // Portrait phone/tablet: player pane on top (wrap content, the
            // screen itself is fixed 16:9 below), library filling the rest.
            splitPane.addView(playerPane, LinearLayout.LayoutParams(-1, -2))
            splitPane.addView(left, LinearLayout.LayoutParams(-1, 0, 1f))
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
        listScroll = ScrollView(this).apply {
            addView(list)
            setOnScrollChangeListener { _, _, y, _, oldY ->
                if (y > oldY && list.height - y < height * 2) {
                    appendChannelRows(list.childCount + 12)
                }
            }
        }
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
        playPauseButton.contentDescription = "Play / pause"
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
                applyFullScreen(!fullScreen)
                showPlayerControls()
            }
        }
        overlayBar.addView(playPauseButton, overlayParams())
        seekRow = LinearLayout(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER_VERTICAL
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
                        // Preview only while dragging: seeking on every
                        // pixel re-seeks the player dozens of times per
                        // drag and jumps far past the target on a long
                        // movie. The real seek happens once, on release
                        // (onStopTrackingTouch) — a tap on the bar also
                        // ends there, so tap-to-jump still works.
                        updateMovieTimes(progress * 1000L, playerController?.duration() ?: 0L)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    scrubbing = true
                    mainHandler.removeCallbacks(hideControlsRunnable)
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    scrubbing = false
                    playerController?.seekTo(seekBar.progress * 1000L)
                    scheduleHideControls()
                }
            })
        }
        seekLabel = label("0:00", 13, Color.WHITE)
        remainingLabel = label("−0:00", 13, Color.WHITE)
        seekRow.addView(seekLabel)
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(48), 1f))
        seekRow.addView(remainingLabel)
        overlayBar.addView(seekRow, LinearLayout.LayoutParams(0, -2, 1f))
        overlayBar.addView(fullscreenButton, overlayParams())
        screen.addView(overlayBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            bottomMargin = dp(8)
        })
        // YouTube-style +-10s seek indicator: a pill on the video showing
        // "-10s" / "+10s" on the side that was double-tapped (or the
        // direction of the D-pad seek), fading after ~900ms. Side margins
        // keep it off the screen edge on either side.
        seekIndicator = label("", 16, Color.WHITE).apply {
            visibility = View.GONE
            background = shape(overlayDark, 0x1affffff.toInt(), 24, 1)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            elevation = dp(6).toFloat()
        }
        screen.addView(seekIndicator, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER).apply {
            marginStart = dp(48)
            marginEnd = dp(48)
        })
        // Channel preview actions. Fullscreen has no corner buttons.
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
        // Move between the two preview controls.
        favButton.nextFocusDownId = qualityButton.id
        qualityButton.nextFocusUpId = favButton.id
        // Tap the video to show/hide the controls (touch only), double-tap
        // left/right to seek -10s/+10s (YouTube-style). The PlayerView has
        // no click handler of its own (useController=false), so taps on it
        // reach this listener; taps on the overlay buttons are consumed by
        // the buttons themselves. WebView embeds (YouTube) consume their
        // own touches, so this does not fire for web channels — their
        // controls never auto-hide either (see hidePlayerControls).
        // SingleTapConfirmed only fires when the tap is NOT the first half
        // of a double-tap, so the two gestures never fight.
        if (!isTv) {
            val gestureDetector = GestureDetector(this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        togglePlayerControls()
                        return true
                    }
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        if (playingMovie == null || !seekable) return false
                        val third = screen.width / 3f
                        when {
                            e.x < third -> seekMovieBy(-10_000L)
                            e.x > third * 2 -> seekMovieBy(10_000L)
                            else -> return false
                        }
                        return true
                    }
                })
            screen.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
        }
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

        playerController = PlayerController(this, screen, 1f,
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
                    if (!isDestroyed) {
                        playPauseButton.text = if (isPlaying) "❚❚" else "▶"
                        if (isPlaying) status.text = ""
                    }
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
        // Restored fullscreen (after a process death): hide the
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
        // A process death must not lose the movie's place in Continue Watching.
        saveMovieProgress(async = false)
        // Save a playing movie (URL + headers + position) so a process
        // death resumes it. (Rotation no longer recreates the activity —
        // see onConfigurationChanged — but a killed process still needs
        // this.) The signed URL stays valid for hours, so this is safe.
        val movie = playingMovie
        val url = playingMovieUrl
        if (movie != null && url != null) {
            outState.putString(STATE_MOVIE_URL, url)
            outState.putString(STATE_MOVIE_TITLE, movie.title)
            outState.putString("movie_subject_id", movie.subjectId)
            outState.putInt("movie_quality", selectedQuality)
            outState.putBoolean("movie_is_series", movie.isSeries)
            outState.putInt("movie_season", playingEpisodeSeason)
            outState.putInt("movie_episode", playingEpisodeEpisode)
            outState.putLong(STATE_MOVIE_POS, if (stoppedForBackground) savedMoviePosition else playerController?.position() ?: 0L)
            outState.putStringArrayList(
                STATE_MOVIE_HEADERS,
                ArrayList(playingMovieHeaders.map { (k, v) -> "$k\u0001$v" }),
            )
        }
        // Save a playing drama episode the same way, so a process
        // death resumes the episode instead of the last channel.
        val drama = playingDrama
        if (drama != null) {
            outState.putString(STATE_DRAMA_VIDEO, drama.videoId)
            outState.putString(STATE_DRAMA_TITLE, playingDramaTitle)
            outState.putString(STATE_DRAMA_EPISODE, drama.title)
            outState.putInt(STATE_DRAMA_EP_NUM, drama.episodeNumber)
        }
    }

    internal fun applyFullScreen(enabled: Boolean) {
        if (fullScreen == enabled) return
        fullScreen = enabled
        if (!isTv) {
            // Rotation is handled in onConfigurationChanged (no
            // recreation, no reload), so there is no toggle loop.
            requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        updateFullscreenUi()
        applyControlVisibility()
        if (enabled) {
            if (playingMovie != null) playPauseButton.requestFocus()
            else if (fullscreenButton.isShown) fullscreenButton.requestFocus()
        } else {
            focusBrowse()
        }
    }

    /**
     * Rotation without recreation (v2.0.27 — the fullscreen-exit reload
     * fix). The manifest declares configChanges for orientation, so a
     * rotation — including the one the fullscreen toggle triggers —
     * lands here instead of destroying the activity: the player,
     * the WebView and the playback position all survive, and the
     * stream never reloads. Only the layout is re-applied.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isTv) return
        isLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyOrientationLayout()
    }

    /** Re-lays out the split pane for the current orientation. The
     * player pane NEVER leaves the window: only the browse pane is
     * re-ordered (left in landscape, below in portrait), so the video
     * surface is never destroyed and playback continues without even a
     * blink. Layout params + the screen height follow the same rules
     * as onCreate, then the fullscreen/browse visibility re-applies. */
    private fun applyOrientationLayout() {
        if (isTv || !::splitPane.isInitialized) return
        val sideBySide = isLandscape
        splitPane.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (sideBySide) {
            libraryPane.layoutParams = LinearLayout.LayoutParams(dp(300), -1)
            playerPane.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        } else {
            playerPane.layoutParams = LinearLayout.LayoutParams(-1, -2)
            libraryPane.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        // Re-order by moving ONLY the browse pane: landscape = library
        // first (left), portrait = library last (below the player).
        splitPane.removeView(libraryPane)
        if (sideBySide) splitPane.addView(libraryPane, 0) else splitPane.addView(libraryPane)
        // Screen height: weight-1 in landscape, fixed 16:9 in portrait.
        screen.layoutParams = if (sideBySide) {
            LinearLayout.LayoutParams(-1, 0, 1f)
        } else {
            val playerWidthPx = resources.displayMetrics.widthPixels - 2 * rootPaddingPx
            val playerHeight = (playerWidthPx * 9 / 16).coerceAtMost(dp(320))
            LinearLayout.LayoutParams(-1, playerHeight)
        }
        metaName.maxWidth = (resources.displayMetrics.widthPixels * 0.5).toInt()
        updateFullscreenUi()
        updateBrowseVisibility()
    }

    /**
     * Applies the current [fullScreen] state to the UI. Called from
     * [applyFullScreen] and from onCreate when restoring after a process death.
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
        // DPAD_UP/DOWN zapping (fullscreen only) lives in
        // dispatchKeyEvent — in the list the keys navigate normally.
        return super.onKeyDown(keyCode, event)
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
                playMovieStream(
                    movie, url, playingMovieHeaders, savedMoviePosition,
                    episodeSeason = playingEpisodeSeason,
                    episodeNumber = playingEpisodeEpisode,
                )
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
            applyFullScreen(false)
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

    /** D-pad intercepts that must run before any view sees the key.
     * UP/DOWN zap channels — but ONLY in fullscreen (the user's order
     * 2026-11-19): in the list, up/down just moves the selection and
     * OK plays, so a channel never starts without OK. The intercept
     * lives here, not in onKeyDown, because in fullscreen the hidden
     * list ScrollView swallowed the keys whenever it could still
     * scroll, which is why zapping worked only sometimes.
     * Movie buttons navigate horizontally. Only the focused timeline seeks. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Focus safety net (v2.0.27): if D-pad focus was ever lost
        // (currentFocus null — e.g. the focused row went away while
        // the list rebuilt), up/down did nothing at all and
        // navigation looked dead, with no way back. The first press
        // now puts focus back where the browse state needs it
        // (playing row, category, or movie controls); the next press
        // zaps again. Fullscreen is excluded — there is legitimately
        // nothing focusable while a channel plays fullscreen, and the
        // zap branch below owns those keys.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            !moviesOpen && !dramasOpen && !fullScreen &&
            currentFocus == null && selected != null
        ) {
            if (event.repeatCount == 0) focusBrowse()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            !moviesOpen && !dramasOpen && currentFocus !is EditText &&
            channelSwitchKeysActive()
        ) {
            // Held keys stay consumed (no focus jump) but zap once.
            if (event.repeatCount == 0) {
                stepChannel(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1)
            }
            return true
        }
        val code = event.keyCode
        if (code == wakeKey) {
            if (event.action == KeyEvent.ACTION_UP) wakeKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (playingMovie != null && !movieEnded && !moviesOpen && !dramasOpen &&
            event.action == KeyEvent.ACTION_DOWN &&
            (code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER ||
                code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            if (!controlsVisible) {
                if (event.repeatCount == 0) {
                    showPlayerControls()
                    playPauseButton.requestFocus()
                    wakeKey = code
                }
                return true
            }
            scheduleHideControls()
            if (currentFocus === seekBar &&
                (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekMovieBy(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
        private const val STATE_FULLSCREEN = "fullscreen"
        private const val STATE_DRAMA_VIDEO = "drama_video"
        private const val STATE_DRAMA_TITLE = "drama_title"
        private const val STATE_DRAMA_EPISODE = "drama_episode"
        private const val STATE_DRAMA_EP_NUM = "drama_ep_num"
        // YouTube live re-resolve poll: while a yt-live channel plays,
        // re-check /live this often and rejoin when the video ID changes
        // (the broadcaster restarted the stream).
        // Continue Watching: ignore positions under 5s (a fresh start is
        // not "continue watching"), and save every 20 seek ticks (20 ×
        // 500ms ≈ 10s) while a movie plays.
        private const val MOVIE_PROGRESS_SAVE_TICKS = 20
    }
}

// Top-level (package) scope: the split extension files (Playback,
// ChannelBrowser, MovieDetails, DramasSection, PlaylistDialogs) use these
// bare. Companion-object members are NOT visible bare from extension
// functions in other files — that is why CI failed with 26 unresolved
// references. The private consts stay in the companion object.
internal enum class BrowseLevel { CATEGORIES, CHANNELS }

internal const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
internal const val DRAMA_REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L
internal const val CONTROLS_HIDE_DELAY_MS = 3000L
internal const val STATE_MOVIE_URL = "movie_url"
internal const val STATE_MOVIE_TITLE = "movie_title"
internal const val STATE_MOVIE_POS = "movie_pos"
internal const val STATE_MOVIE_HEADERS = "movie_headers"
internal const val YT_LIVE_POLL_MS = 5 * 60 * 1000L
internal const val MIN_SAVE_POSITION_MS = 5_000L
