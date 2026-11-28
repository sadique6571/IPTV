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
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.WindowInsets
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
import java.util.concurrent.atomic.AtomicBoolean

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
    // The LIVE badge (meta chip) is hidden while a movie plays — a
    // movie is not live — and shown again for channels.
    internal lateinit var metaLiveBadge: TextView
    internal lateinit var overlayBar: LinearLayout
    // Mini-player two-row bar (v2.0.24): the chips row the bar splits
    // into in TV mini-player mode (seek row on top, chips below).
    // Created with the bar; attached only in that mode.
    private lateinit var chipsRow: LinearLayout
    private var miniBarTwoRow: Boolean? = null
    private var barStyledForFullscreen = false
    internal lateinit var status: TextView
    internal lateinit var buffering: ProgressBar
    internal lateinit var numberOverlay: TextView
    internal lateinit var listScroll: ScrollView
    internal lateinit var libraryPane: LinearLayout
    internal lateinit var channelLibrary: LinearLayout
    internal var browseHidden = false
    internal lateinit var rootView: LinearLayout
    internal lateinit var headerView: LinearLayout
    // "Back to TV" in the status bar (v2.0.23): the way back from the
    // Movies section — visible only while movies are open.
    internal lateinit var backToTvButton: Button
    // Live TV header action buttons (Playlists / Import M3U / Xtream /
    // Update, TV only): hidden while movies are open (v2.0.24) — with
    // "Back to TV" added, the header's children overflow the 960dp TV
    // width and the weighted network label collapses to 0 width.
    private lateinit var moviesHeaderButton: Button
    private lateinit var toggleListButton: Button
    internal lateinit var goLiveButton: Button
    private lateinit var compactControls: LinearLayout
    private lateinit var compactTransport: LinearLayout
    internal lateinit var compactOptions: LinearLayout
    internal lateinit var centerPlayButton: ImageButton
    internal var liveCaptions = false
    // Network speed + today's usage label (v2.0.44, header right end):
    // "500 Kbps/30 MB", refreshed once a second while visible.
    internal lateinit var networkLabel: TextView
    private val networkTickRunnable = object : Runnable {
        override fun run() {
            if (::networkLabel.isInitialized) {
                networkLabel.text = NetworkUsage.tick(this@MainActivity)
            }
            mainHandler.postDelayed(this, 1000)
        }
    }
    private lateinit var playerPane: LinearLayout
    // The split container (browse pane + player pane). Kept as a field
    // so onConfigurationChanged can re-lay it out on rotation without
    // recreating the activity (see applyOrientationLayout).
    private lateinit var splitPane: LinearLayout
    private var rootPaddingPx = 0
    private var paneGapPx = 0
    // On-video corner buttons (the tiles under the player are gone — the
    // user asked for the buttons ON the screen itself): Favorite
    // top-right and Quality bottom-right. Speed lives in
    // the overlay pill (movies only — channels never need it).
    internal lateinit var favButton: Button
    // Movie quality switch inside the controls row (movies play
    // fullscreen, where the corner quality button is hidden).
    internal lateinit var movieAudioButton: Button
    internal var playingDubs: List<MovieBox.Dub> = emptyList()
    internal var audioRequest = 0
    internal lateinit var movieQualityButton: Button
    internal lateinit var movieCCButton: Button
    internal lateinit var moviePrevButton: Button
    internal lateinit var movieNextButton: Button
    internal lateinit var movieStopButton: Button
    internal var currentSubtitle: MovieBox.Subtitle? = null
    internal var subtitlesEnabled = true
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
    internal lateinit var playPauseButton: ImageButton
    internal lateinit var fullscreenButton: ImageButton
    // Demo player UI (v2.0.11): top title bar, -10s/+10s seek buttons,
    // and the Up Next auto-play card for series episodes.
    internal lateinit var topBar: LinearLayout
    internal lateinit var topBackButton: ImageButton
    internal lateinit var topTitleLabel: TextView
    internal lateinit var seekBackButton: Button
    internal lateinit var seekForwardButton: Button
    internal lateinit var upNextCard: LinearLayout
    internal lateinit var upNextThumb: ImageView
    internal lateinit var upNextTitle: TextView
    internal lateinit var upNextCountdown: TextView
    internal var upNextShownFor: String? = null
    internal var upNextCountdownValue: Int = 0
    internal val upNextTickRunnable = object : Runnable {
        override fun run() {
            upNextCountdownValue--
            if (upNextCountdownValue <= 0) {
                hideUpNextCard()
                playAdjacentEpisode(1)
            } else {
                upNextCountdown.text = "Auto-plays in ${upNextCountdownValue}s"
                mainHandler.postDelayed(this, 1000)
            }
        }
    }
    private lateinit var fullscreenIcon: FullscreenIconDrawable
    internal lateinit var catalogTitle: TextView
    // Shown under the catalog title while a search query is active: the
    // query text + a button that clears it and restores the full list.
    internal lateinit var searchClearRow: LinearLayout
    internal lateinit var searchClearLabel: TextView
    // Inline channel search (v2.0.37): a real EditText above the
    // channel list — typing filters the list live (ChannelSearch.kt).
    // The guard stops its TextWatcher re-firing when populate() syncs
    // the field after an outside query change.
    internal lateinit var channelSearchInput: EditText
    // ::channelSearchInput.isInitialized only compiles INSIDE this class —
    // from an extension function in another file the backing field is not
    // accessible (v37 run #91). Extension code asks through here.
    internal fun isChannelSearchInputReady(): Boolean = ::channelSearchInput.isInitialized
    internal var suppressChannelSearchWatch = false
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
    // The user's own order for the Pakistani entertainment head of
    // the list (2026-11-25): Green 1, Hum 2, ARY 3, Aan 4, MUN 5,
    // Geo 6, Express 7, then the rest. This is an ORDER inside the
    // PK-entertainment tier, not a pin — no channel is pinned, and
    // nothing jumps out of its own tier. Paigham is NOT here: it is
    // Islamic, so it sits in the Islamic block.
    internal val pkEntTopOrder = listOf(
        "Green Entertainment", "Hum TV", "ARY Digital", "Aan TV", "MUN TV",
        "Geo Entertainment", "Express Entertainment", "Hum Sitaray", "A Plus",
        "TV One", "PTV Home", "Play Entertainment", "Bol Entertainment HD",
        "Apna Channel", "8XM", "aurLife HD", "LTN Family", "M Entertainment",
        "PTV Global", "InPlus Pakistan", "Falak TV", "JooMusic",
        "Discover Pakistan", "ARUJ TV", "ARY Musik", "Jalwa TV",
    )
    internal val pkEntTopRank = pkEntTopOrder.withIndex().associate { it.value to it.index }
    internal var videoOptions = emptyList<PlayerController.VideoOption>()
    internal var selectedVideoIndex = -1
    internal var seekable = false
    internal var scrubbing = false
    internal var numberBuffer = ""
    internal var numberTimeout: Runnable? = null
    private var wakeKey = KeyEvent.KEYCODE_UNKNOWN
    internal var stoppedForBackground = false
    internal var updateDownloadDialog: AlertDialog? = null

    // MovieBox movies (v1): a full-screen overlay over the Live TV layout.
    // Movies play in the SAME ExoPlayer and never touch the channel store —
    // a signed movie URL must never land in Recent or last-channel, or the
    // app would auto-play an expired link on the next launch.
    internal lateinit var movieBox: MovieBox
    internal lateinit var movieStore: MovieStore
    // The Movies overlay builds on first open, not at startup —
    // they are big view trees and the app opened on a black screen while
    // they inflated. Nothing touches them before the first open.
    internal lateinit var contentFrame: FrameLayout
    internal var moviesViewBuilt = false
    internal lateinit var moviesView: LinearLayout
    // TV Movies sidebar (search + nav), stored so its width can narrow
    // in mini-player mode (v2.0.24). Only built on TV.
    internal lateinit var playingChannelLabel: TextView
    internal lateinit var moviesListPane: LinearLayout
    internal lateinit var moviesDetailsPane: LinearLayout
    internal lateinit var movieSearchInput: EditText
    internal lateinit var movieStatus: TextView
    internal lateinit var movieProgress: ProgressBar
    internal lateinit var movieResults: LinearLayout
    internal lateinit var movieResultsScroll: ScrollView
    internal lateinit var movieDetailsTitle: TextView
    internal lateinit var movieDetailsMeta: TextView
    internal lateinit var movieDetailsDesc: TextView
    internal lateinit var movieDetailsScroll: ScrollView
    internal lateinit var movieDetailsPoster: FrameLayout
    internal lateinit var movieDetailsMoreButton: Button
    internal lateinit var movieResumeButton: Button
    // Series details (v2.0.27): season chips + episode list in the same
    // details pane. Hidden for movies.
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
    internal lateinit var movieSearchFilters: LinearLayout
    internal var movieSearchFilter = MovieSearchFilter.ALL
    internal var movieSearchResults = emptyList<MovieBox.Movie>()
    internal var moviesReturnPosition: MovieBrowsePosition? = null
    internal var movieDetailsRequest = 0
    internal var movieStreamsRequest = 0
    internal var currentMovieDetails: MovieBox.Details? = null
    // True once the details pane has been rendered for
    // currentMovieDetails (showMovieDetails/showSeriesDetails) — Back
    // from playback returns to it; false after the reset in
    // openMovieDetails and for Continue Watching resumes, which never
    // render the pane.
    internal var movieDetailsRendered = false
    internal var currentMovieStreams: List<MovieBox.MovieStream> = emptyList()
    internal var currentPlayTitle = ""
    // v2.0.16: which subject the top-bar title fetch already ran for.
    // The fetch is one-shot per subject, so a controls toggle never
    // starts a duplicate — see fetchTopBarTitle().
    internal var titleFetchSubject = ""
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
    internal var playingSeriesSeasons: List<MovieBox.Season> = emptyList()
    // Movies home feed (v2.0.27): New / Recommended / Series sections,
    // loaded once per app run in the background.
    internal var movieFeedNew = emptyList<MovieBox.Movie>()
    internal var movieFeedRecommended = emptyList<MovieBox.Movie>()
    internal var movieFeedSeries = emptyList<MovieBox.Movie>()
    internal var movieFeedTrending = emptyList<MovieBox.Movie>()
    internal var movieFeedPicks = emptyList<MovieBox.Movie>()
    internal var movieFeedLoaded = false
    internal var movieFeedLoading = false
    internal var movieFeedRequest = 0
    // Movies home v2 (v2.0.35): Netflix-style tabs + rails. (The hero
    // banner was removed — the user watches via search.)
    internal var moviesTab = MoviesTab.HOME
    internal lateinit var moviesTabRow: LinearLayout
    internal var moviesSeeAll: MoviesSeeAll? = null
    // Genres tab: the picked genre + a request counter that drops stale
    // loads + the genre chips / results column (rebuilt per render).
    internal var moviesGenre = "Action"
    internal var moviesGenreRequest = 0
    internal lateinit var movieGenreRow: LinearLayout
    internal lateinit var movieGenreResults: LinearLayout
    // Genres tab quick-search chips (Marvel, DC, … — moved here from
    // the Movies home in v2.0.37): rebuilt per render with the genre row.
    internal lateinit var movieGenreQuickRow: LinearLayout
    // Downloads (mobile only, MoviesDownloads.kt): one active download
    // on its OWN executor — the shared `imports` executor is
    // single-thread, and a 35-minute download would block every
    // search/details call behind it.
    internal val downloadExecutor = Executors.newSingleThreadExecutor()
    internal lateinit var movieDownloadButton: Button
    internal var downloadActive = false
    internal var downloadActiveKey = ""
    internal val downloadCancelFlag = AtomicBoolean(false)
    internal var downloadProgressBar: ProgressBar? = null
    internal var downloadProgressLabel: TextView? = null

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
                    // Up Next: the last 20s of a series episode offer
                    // the next episode with a 10s auto-play countdown.
                    if (playingMovie != null) checkUpNext(position, duration)
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
        val catalog = imports.submit<Pair<List<Channel>, List<Channel>>> {
            val builtIn = assets.open("verified-channels.m3u").use { Playlist.parse(Playlist.read(it)) }
            val extra = try {
                playlistStore.migrateLegacy(File(filesDir, "channels.m3u"))
                playlistStore.active()?.let { playlistStore.channelsOf(it) }.orEmpty()
            } catch (_: IOException) { emptyList() }
            builtIn to extra
        }

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
        // v2.0.24: TV browse pane 320dp -> 260dp — the Live TV side
        // panel was a third of the screen and the player got squeezed.
        val libraryWidthDp = if (isTv) 260 else 300
        val paneGapDp = if (isTv) 14 else 12
        paneGapPx = dp(paneGapDp)
        rootView = column().apply {
            setPadding(rootPadding, dp(if (isTv) 10 else 8), rootPadding, dp(if (isTv) 12 else 8))
            background = demoBackground()
        }
        headerView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = brightGlassShape()
            setPadding(dp(10), dp(5), dp(8), dp(5))
        }
        val narrowPhoneHeader = !isTv && resources.configuration.screenWidthDp in 1..389
        val headerBar = if (narrowPhoneHeader) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            headerView.orientation = LinearLayout.VERTICAL
            headerView.addView(this, LinearLayout.LayoutParams(-1, -2))
        } else headerView
        headerBar.addView(ImageView(this).apply {
            setImageResource(R.drawable.iptv_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "IPTV"
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        headerBar.addView(label("IPTV", if (isTv) 20 else 16, textColor).apply {
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), 0, dp(4), 0)
        })
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        backToTvButton = topPillButton(R.drawable.ic_tv, "Live TV") {
            if (moviesOpen) closeMovies(stopMovie = true)
            else {
                browseHidden = false
                updateMiniPlayerLayout()
                focusBrowse()
            }
        }.apply { id = View.generateViewId() }
        moviesHeaderButton = topPillButton(R.drawable.ic_movie, "Movies & Series") {
            browseHidden = false
            if (!moviesOpen) openMovies()
            else {
                updateMiniPlayerLayout()
                showMoviesList()
            }
        }
        toggleListButton = topPillButton(R.drawable.ic_list, "Hide List") {
            browseHidden = !browseHidden
            updateMiniPlayerLayout()
        }
        if (isTv) {
            val actions = listOf(
                backToTvButton, moviesHeaderButton, toggleListButton,
                topPillButton(R.drawable.ic_download, "Open M3U") { choosePlaylist() },
                topPillButton(R.drawable.ic_list, "Playlists") { showPlaylists() },
                topPillButton(R.drawable.ic_tv, "Xtream") { askXtream() },
                topPillButton(R.drawable.ic_upload, "Update") { checkForUpdateManually() },
            )
            actions.forEach { action ->
                action.background = navItemBackground()
                navigation.addView(action, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(5) })
            }
            headerBar.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(navigation)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            for (tab in listOf(backToTvButton, moviesHeaderButton)) {
                tab.contentDescription = tab.text
                tab.text = ""
                tab.minWidth = 0
                tab.minimumWidth = 0
                tab.compoundDrawablePadding = 0
                tab.setPadding(dp(12), 0, dp(12), 0)
                headerBar.addView(tab, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(7) })
            }
        }
        backToTvButton.background = navItemBackground()
        moviesHeaderButton.background = navItemBackground()
        networkLabel = label("0.0 Mbps / 0 B", if (isTv) 12 else 11, muted2).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            maxLines = if (isTv) 1 else 2
        }
        if (narrowPhoneHeader) {
            headerBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        } else {
            headerBar.addView(networkLabel, LinearLayout.LayoutParams(if (isTv) -2 else 0, -2, if (isTv) 0f else 1f).apply {
                marginStart = dp(6)
            })
        }
        if (!isTv) headerBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(textColor)
            background = overlayButtonBackground()
            contentDescription = "More options"
            setOnClickListener { showMobileHeaderMenu(this) }
        }, LinearLayout.LayoutParams(dp(44), dp(48)).apply { marginStart = dp(4) })
        headerBar.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(textColor)
            background = overlayButtonBackground()
            contentDescription = "Close App"
            setOnClickListener { closeApp() }
        }, LinearLayout.LayoutParams(dp(44), dp(if (isTv) 44 else 48)).apply { marginStart = dp(4) })
        if (narrowPhoneHeader) headerView.addView(networkLabel, LinearLayout.LayoutParams(-1, -2))
        rootView.addView(headerView, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        splitPane = LinearLayout(this).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        rootView.addView(splitPane, LinearLayout.LayoutParams(-1, 0, 1f))
        libraryPane = column().apply { background = brightGlassShape() }
        val left = column().also { channelLibrary = it }.apply {
            setPadding(dp(8), dp(8), dp(8), dp(6))
        }
        libraryPane.addView(left, LinearLayout.LayoutParams(-1, -1))
        playerPane = column()

        channelSearchInput = buildChannelSearchInput()
        val channelSearchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        channelSearchRow.addView(channelSearchInput, LinearLayout.LayoutParams(0, -2, 1f))
        channelSearchRow.addView(button("Arrange") { showChannelOrder() })
        left.addView(channelSearchRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val filters = listOf("All", "Favs", "General", "Islamic", "Kids", "News", "Sports", "Recent", "M3U")
        for (names in filters.chunked(if (sideBySide) 4 else filters.size)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in names) {
                val control = chip(if (name == "Favs") "Favorites" else name) {
                    filter = name
                    query = ""
                    populate()
                    setBrowseLevel(BrowseLevel.CHANNELS)
                }
                control.id = View.generateViewId()
                filterChips[name] = control
                row.addView(control, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            }
            left.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        if (sideBySide) {
            splitPane.addView(libraryPane, LinearLayout.LayoutParams(dp(libraryWidthDp), -1))
            splitPane.addView(playerPane, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            splitPane.addView(playerPane, LinearLayout.LayoutParams(-1, -2))
            splitPane.addView(libraryPane, LinearLayout.LayoutParams(-1, 0, 1f))
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
        searchClearRow.addView(button("Saaf karein", R.drawable.ic_close) { clearSearch() },
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
        playingChannelLabel = label("", 12, muted2).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(dp(6), dp(6), dp(6), dp(4))
        }
        left.addView(playingChannelLabel, LinearLayout.LayoutParams(-1, -2))
        // Player card (Qwen PlayerCard): 16:9, 24dp rounded corners, 1dp
        // stroke (square corners in fullscreen — see updateFullscreenUi).
        // The meta chip (LIVE + channel name) sits ON the video top-left
        // on every device. The YouTube-style overlay controls (play/pause,
        // next, volume slider, mute, fullscreen toggle) sit bottom-right
        // ON the video.
        screen = FrameLayout(this).apply {
            setBackgroundColor(WINDOWED_PLAYER_BACKGROUND)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(componentRadiusDp).toFloat())
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
                cornerRadius = dp(componentRadiusDp).toFloat()
            }
        }
        screen.addView(screenBorder, FrameLayout.LayoutParams(-1, -1))
        // The channel/movie name chip (LIVE + name + category), top-left
        // ON the video on every device — it replaced the info bar under
        // the player (v2.0.34). Hidden until something plays, and hidden
        // in fullscreen; applyControlVisibility owns the visibility.
        metaChip = column().apply {
            background = shape(overlayDark, 0x1affffff.toInt(), strokeDp = 1)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
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
            -2, -2, Gravity.TOP or Gravity.START,
        ).apply {
            marginStart = dp(if (isTv) 20 else 12)
            topMargin = dp(12)
        })
        numberOverlay = TextView(this).apply {
            textSize = if (isTv) 22f else 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = shape(overlayDark, cyan, strokeDp = 1)
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
            // Glassy (v2.0.17): neutral black glass pill — 60% black
            // + white rim — so the video shows through the bar like
            // it does through the subtitle box. The demo's glass is
            // neutral, not the app's blue glassShape or the old flat
            // navy fill, which both read as solid cards over video.
            background = shape(0xe6121216.toInt(), 0x44ffffff.toInt(), strokeDp = 1)
            // Demo match (v2.0.15): slimmer bar — the demo's bar is
            // compact, so less padding leaves more room for the seek
            // bar. TV gets the same slim bar now (was phone-only).
            // v2.0.16: TV vertical padding 8dp — the demo's pill is
            // ~120px tall on a 1920 frame (60dp), and the 40dp disc
            // needs the extra breathing room to match it.
            // v2.0.17: roomier bar — the demo's chips sit with clear
            // space above and below inside the pill (chips ~40% of
            // the bar height), so TV 10dp / phone 6dp vertical.
            // v2.0.24: more side room — the play disc and the fullscreen
            // chip sat flush against the bar's edges; 14dp TV / 10dp
            // phone leaves a gap, and the seek bar (weight 1f) shortens
            // a little to make it.
            setPadding(dp(if (isTv) 14 else 10), dp(if (isTv) 10 else 9), dp(if (isTv) 14 else 10), dp(if (isTv) 10 else 9))
            elevation = dp(6).toFloat()
        }
        // Mini-player two-row bar (v2.0.24): the chips row the bar
        // splits into in TV mini-player mode. Created here, attached
        // only by setMiniPlayerBar.
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        compactControls = column()
        compactTransport = chipsRow
        compactOptions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        compactControls.addView(compactTransport, LinearLayout.LayoutParams(-1, -2))
        compactControls.addView(compactOptions, LinearLayout.LayoutParams(-1, -2))
        playPauseButton = overlayButton(R.drawable.ic_play) {
            togglePlayerPlayback()
        }
        playPauseButton.id = View.generateViewId()
        playPauseButton.contentDescription = "Play / pause"
        playPauseButton.background = playPauseButtonBackground()
        playPauseButton.imageTintList = ColorStateList.valueOf(0xff111111.toInt())
        centerPlayButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_play)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            background = shape(0x99000000.toInt(), 0x40ffffff.toInt(), componentRadiusDp, 1)
            contentDescription = "Resume playback"
            isFocusable = false
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { togglePlayerPlayback() }
        }
        screen.addView(centerPlayButton, FrameLayout.LayoutParams(dp(66), dp(66), Gravity.CENTER))
        fullscreenIcon = FullscreenIconDrawable(expanded = false)
        fullscreenButton = ImageButton(this).apply {
            setImageDrawable(fullscreenIcon)
            id = View.generateViewId()
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Fullscreen"
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.17): the demo's fullscreen chip is the
            // same height as the text chips — the old 40dp box (the
            // disc's overlayParams) stood taller than the 28dp chips.
            // 4dp padding leaves the icon 20dp in a 28dp box.
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rowChipBackground()
            setOnClickListener {
                applyFullScreen(!fullScreen)
                showPlayerControls()
            }
        }
        // Stop (v2.0.25): the player had no way to stop a movie at
        // all — Back only stopped it from the Movies list top level.
        // Stopping re-renders the list, so Continue Watching comes
        // back right away. Styled like Prev/Next (frosted chip).
        movieStopButton = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            contentDescription = "Stop movie"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            minWidth = 0
            includeFontPadding = false
            setPadding(dp(10), dp(if (isTv) 6 else 2), dp(10), dp(if (isTv) 6 else 2))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 10f else 10f
            gravity = Gravity.CENTER
            background = rowChipBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener {
                stopCurrentPlayback()
                if (moviesOpen) showMoviesList()
            }
        }
        // Demo UI: plain "-10" / "+10" text (no box, like the demo),
        // right after the play/pause disc.
        seekBackButton = Button(this).apply {
            text = "-10"
            isAllCaps = false
            contentDescription = "Back 10 seconds"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.16, 1920x1080 TV): the demo's -10/+10
            // sit ~55dp apart centre-to-centre; the old 6dp sides
            // left them ~92dp apart. 3dp sides + 12sp + minWidth = 0
            // (the Button theme's 64dp min-width was inflating them
            // past their text + padding — same cause as the wide
            // chips, see Prev/Next below).
            minWidth = 0
            setPadding(dp(6), dp(if (isTv) 6 else 4), dp(6), dp(if (isTv) 6 else 4))
            setTextColor(Color.WHITE)
            textSize = if (isTv) 12f else 12f
            gravity = Gravity.CENTER
            background = overlayButtonBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { seekMovieBy(-10_000) }
        }
        seekForwardButton = Button(this).apply {
            text = "+10"
            isAllCaps = false
            contentDescription = "Forward 10 seconds"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.16): tight plain text, like "-10" (3dp sides now).
            minWidth = 0
            setPadding(dp(6), dp(if (isTv) 6 else 4), dp(6), dp(if (isTv) 6 else 4))
            setTextColor(Color.WHITE)
            textSize = if (isTv) 12f else 12f
            gravity = Gravity.CENTER
            background = overlayButtonBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { seekMovieBy(10_000) }
        }
        overlayBar.addView(playPauseButton, LinearLayout.LayoutParams(dp(if (isTv) 40 else 34), dp(if (isTv) 40 else 34)))
        overlayBar.addView(movieStopButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 34 else 42),
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        overlayBar.addView(seekBackButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 28 else 28),
        ).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
        })
        overlayBar.addView(seekForwardButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 28 else 28),
        ).apply {
            marginStart = dp(1)
            marginEnd = dp(1)
        })
        // Previous / Next episode (series only — hidden otherwise, see
        // updatePlayerControls). Demo UI: plain "Prev" / "Next" chips
        // after the seek bar, not before it.
        moviePrevButton = Button(this).apply {
            text = "Prev"
            isAllCaps = false
            contentDescription = "Previous episode"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.16, 1920x1080 TV): the demo's Prev chip
            // is ~79px wide (~40dp) on a 1920 frame; the app's was
            // ~182px. The cause was the Button theme's 64dp min-width:
            // minimumWidth = 0 alone does not clear TextView's
            // minWidth — Audio/CC got minWidth = 0 in v2.0.15 and
            // their widths match the demo, Prev/Next/Quality never
            // did. 6dp sides + 11sp + minWidth = 0 on TV now.
            minWidth = 0
            setPadding(dp(10), dp(if (isTv) 6 else 2), dp(10), dp(if (isTv) 6 else 2))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 10f else 10f
            gravity = Gravity.CENTER
            // v2.0.24: the text sat ~3.5px low in the 24dp chip (measured
            // on the TV screenshot) — the Button's default font padding
            // makes the line box taller than the glyphs. Off, the text
            // centers on its real height.
            includeFontPadding = false
            background = rowChipBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { if (playingMovie != null) playAdjacentEpisode(-1) else stepChannel(-1) }
        }
        movieNextButton = Button(this).apply {
            text = "Next"
            isAllCaps = false
            contentDescription = "Next episode"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.16): tighter chip, like Prev (TV: 6dp / 11sp / minWidth = 0 now).
            minWidth = 0
            setPadding(dp(10), dp(if (isTv) 6 else 2), dp(10), dp(if (isTv) 6 else 2))
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 10f else 10f
            gravity = Gravity.CENTER
            // v2.0.24: same off-center text as Prev (see above).
            includeFontPadding = false
            background = rowChipBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { if (playingMovie != null) playAdjacentEpisode(1) else stepChannel(1) }
        }
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
                progressTintList = ColorStateList.valueOf(if (hasFocus) Color.WHITE else netflixRed)
            }
            // A taller seek bar is easier to drag by touch.
            if (!isTv) minimumHeight = dp(36)
            progressTintList = ColorStateList.valueOf(netflixRed)
            // Custom round thumb (solid white dot, like the demo); the
            // tint is cleared so it does not wash out the white.
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
        // Demo UI: one combined time readout right of the bar
        // ("24:31 / 1:58:00"); the old remaining-time label is hidden.
        // Demo match (v2.0.15): the time readout was 13sp with 4dp of
        // vertical padding from label() — bigger than the demo's small
        // readout, and it ate seek-bar room. 12sp, no extra padding.
        seekLabel = label("0:00 / 0:00", 12, 0xffd1d5de.toInt()).apply {
            setPadding(dp(4), 0, 0, 0)
            maxLines = 1
            includeFontPadding = false
        }
        remainingLabel = label("−0:00", 13, Color.WHITE).apply { visibility = View.GONE }
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(if (isTv) 40 else 36), 1f))
        seekRow.addView(seekLabel)
        seekRow.addView(remainingLabel)
        overlayBar.addView(seekRow, LinearLayout.LayoutParams(0, -2, 1f))
        overlayBar.addView(moviePrevButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 34 else 42),
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        overlayBar.addView(movieNextButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 34 else 42),
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        movieQualityButton = Button(this).apply {
            text = "Quality"
            isAllCaps = false
            contentDescription = "Quality"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            textSize = if (isTv) 10f else 10f
            gravity = Gravity.CENTER
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            // Demo match (v2.0.16): minWidth = 0 like Audio/CC (see
            // Prev — the theme's 64dp min-width was inflating it),
            // TV 6dp sides / 11sp now.
            minWidth = 0
            setPadding(dp(10), dp(if (isTv) 6 else 2), dp(10), dp(if (isTv) 6 else 2))
            background = rowChipBackground()
            // v2.0.24: same off-center text as Prev (see above).
            includeFontPadding = false
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { showQualityPopup(it) }
        }
        movieAudioButton = Button(this).apply {
            text = "Audio"
            isAllCaps = false
            contentDescription = "Audio language"
            id = View.generateViewId()
            minimumWidth = 0
            // Demo match (v2.0.15): minimumHeight was never cleared here
            // (Prev/Next/Quality set it to 0) — the theme's default 48dp
            // button min-height could keep this chip tall on phones.
            minimumHeight = 0
            minWidth = 0
            // Demo match (v2.0.16): TV 4dp sides / 11sp now.
            setPadding(dp(10), 0, dp(10), 0)
            textSize = if (isTv) 10f else 10f
            setTextColor(Color.WHITE)
            // v2.0.24: same off-center text as Prev (see above) — the
            // screenshot shows the same ~3.5px drop on this chip too.
            includeFontPadding = false
            background = rowChipBackground()
            visibility = View.GONE
            setOnClickListener { showMovieAudio(it) }
        }
        overlayBar.addView(movieAudioButton, LinearLayout.LayoutParams(-2, dp(if (isTv) 34 else 42)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        movieCCButton = Button(this).apply {
            text = "CC"
            isAllCaps = false
            contentDescription = "Subtitles (CC)"
            id = View.generateViewId()
            minimumWidth = 0
            // Demo match (v2.0.15): minimumHeight was never cleared here
            // either — same 48dp default risk as Audio (see above).
            minimumHeight = 0
            minWidth = 0
            // Demo match (v2.0.16): TV 6dp sides / 11sp now.
            setPadding(dp(10), 0, dp(10), 0)
            textSize = if (isTv) 10f else 10f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            // v2.0.24: same off-center text as Prev (see above).
            includeFontPadding = false
            background = rowChipBackground()
            visibility = View.GONE
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener {
                if (playingMovie != null) showSubtitlePopup(it) else {
                    liveCaptions = !liveCaptions
                    playerController?.setLiveCaptions(liveCaptions)
                    applyControlVisibility()
                }
            }
        }
        overlayBar.addView(movieCCButton, LinearLayout.LayoutParams(-2, dp(if (isTv) 34 else 42)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        // Demo order: Prev, Next, Audio, CC, Quality, fullscreen —
        // Quality sits last (before fullscreen), like the demo.
        overlayBar.addView(movieQualityButton, LinearLayout.LayoutParams(
            -2, dp(if (isTv) 34 else 42),
        ).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        goLiveButton = button("Go Live") { playerController?.goLive(); showPlayerControls() }.apply {
            id = View.generateViewId()
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(6), 0, dp(6), 0)
            background = rowChipBackground()
        }
        overlayBar.addView(goLiveButton, LinearLayout.LayoutParams(-2, dp(36)))
        overlayBar.addView(fullscreenButton, LinearLayout.LayoutParams(-2, dp(if (isTv) 34 else 42)).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
        screen.addView(overlayBar, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
            bottomMargin = dp(8)
        })
        // Demo UI: top title bar — Back + playing title
        // ("The Body • S1:E1") on a full-width top scrim, like the
        // demo (no pill). Shows/hides with the bottom controls.
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Glassy (v2.0.17): neutral black glass fading out, like
            // the demo's scrim shape — the old pure-black 67%
            // gradient and the blue glass tint both read as a solid
            // shadow, not glass. Same top opacity, so the title
            // stays as readable.
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0x00000000),
            )
            setPadding(dp(16), dp(16), dp(16), dp(28))
            visibility = View.GONE
        }
        topBackButton = ImageButton(this).apply {
            setImageDrawable(BackArrowDrawable())
            contentDescription = "Back"
            id = View.generateViewId()
            minimumWidth = 0
            minimumHeight = 0
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = overlayButtonBackground()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener { onBackPressed() }
        }
        topTitleLabel = label("", if (isTv) 18 else 16, Color.WHITE).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        topBar.addView(topBackButton)
        topBar.addView(topTitleLabel)
        screen.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // Demo UI: Up Next card — appears in the last 20s of a series
        // episode; auto-plays the next episode after a 10s countdown.
        // Tapping it plays the next episode at once.
        upNextCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Neutral black glass like the bottom bar (v2.0.17) —
            // the old navy fill read as a solid card over video.
            background = shape(0x99000000.toInt(), 0x40ffffff.toInt(), strokeDp = 1)
            setPadding(dp(10), dp(10), dp(14), dp(10))
            elevation = dp(6).toFloat()
            visibility = View.GONE
            isFocusable = true
            id = View.generateViewId()
            setOnFocusChangeListener { _, hasFocus -> animateFocusScale(this, hasFocus) }
            setOnClickListener {
                hideUpNextCard()
                playAdjacentEpisode(1)
            }
        }
        upNextThumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xff1a1a1a.toInt())
        }
        upNextCard.addView(upNextThumb, LinearLayout.LayoutParams(dp(56), dp(84)))
        val upNextText = column().apply { setPadding(dp(10), 0, 0, 0) }
        upNextTitle = label("", if (isTv) 16 else 15, Color.WHITE).apply {
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        upNextCountdown = label("", if (isTv) 14 else 13, 0xffcccccc.toInt())
        upNextText.addView(upNextTitle)
        upNextText.addView(upNextCountdown)
        upNextCard.addView(upNextText)
        screen.addView(upNextCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(8)
            bottomMargin = dp(72)
        })
        // YouTube-style +-10s seek indicator: a pill on the video showing
        // "-10s" / "+10s" on the side that was double-tapped (or the
        // direction of the D-pad seek), fading after ~900ms. Side margins
        // keep it off the screen edge on either side.
        seekIndicator = label("", 16, Color.WHITE).apply {
            visibility = View.GONE
            background = shape(overlayDark, 0x1affffff.toInt(), strokeDp = 1)
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
                addState(intArrayOf(android.R.attr.state_focused), glassShape(componentRadiusDp, cyan, 2))
                addState(intArrayOf(android.R.attr.state_pressed), shape(glassHi, cyan, componentRadiusDp))
                addState(intArrayOf(), glassShape(componentRadiusDp))
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
                        if (!seekable) return false
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
        // Status line (buffering/errors): an overlay ON the video, never
        // a row under it. It used to sit in playerPane below the player,
        // and its TextWatcher flipped it VISIBLE on every "Connecting…"
        // / "Buffering…" — on TV/landscape the player has weight 1, so
        // the video shrank by the status height and grew back when the
        // text cleared, even in fullscreen (the watcher overrode the
        // fullscreen GONE). As an overlay it can never resize the video.
        status = label("", if (isTv) 15 else 13, accent).apply {
            background = shape(overlayDark, 0x1affffff.toInt(), strokeDp = 1)
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
            topMargin = dp(if (isTv) 12 else 54)
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
                    playingMovie?.let {
                        movieStore.markEpisodeWatched(it.subjectId, playingEpisodeSeason, playingEpisodeEpisode)
                    }
                    // Up Next was counting down and the episode ended
                    // before it fired (e.g. a seek into the last
                    // seconds): go to the next episode instead of the
                    // ended screen.
                    if (upNextShownFor != null) {
                        hideUpNextCard()
                        playAdjacentEpisode(1)
                        return
                    }
                    status.text = "Playback ended."
                    // A finished movie leaves Continue Watching by itself.
                    val movie = playingMovie
                    if (movie != null && movie.subjectId.isNotEmpty()) {
                        movieStore.removeContinue(movie.subjectId)
                        if (moviesOpen && !movieResultsAreSearch) renderCurrentMoviesTab()
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
                        playPauseButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                        if (isPlaying && playerController?.isTimeShifting != true) status.text = ""
                        applyControlVisibility()
                        if (isPlaying) scheduleHideControls()
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
                }
                override fun onSeekableChanged(isSeekable: Boolean, durationMs: Long) {
                    if (isDestroyed) return
                    val changed = seekable != isSeekable
                    seekable = isSeekable
                    if (changed) {
                        applyControlVisibility()
                        if (seekable) startSeekUpdates() else stopSeekUpdates()
                    }
                }
                override fun onLiveBufferChanged(message: String) {
                    if (isDestroyed || isFinishing) return
                    status.text = message
                    applyControlVisibility()
                }
            })
        // The CC menu's remembered subtitle size (default Medium 20sp) and
        // background (default on, the demo's black box) — the player
        // applies both whenever a PlayerView is created.
        playerController?.subtitleSizeSp = subtitleSizeSp
        playerController?.subtitleBackground = subtitleBackground
        // The Movies overlay sits on top of the Live TV layout in a frame,
        // so opening it never detaches the player view underneath. It
        // builds on first open (ensureMoviesView), not here — building it
        // at startup kept the app on a black screen while it inflated.
        movieBox = MovieBox(applicationContext)
        movieStore = MovieStore(applicationContext)
        contentFrame = FrameLayout(this)
        contentFrame.addView(rootView, FrameLayout.LayoutParams(-1, -1))
        contentFrame.setOnApplyWindowInsetsListener { _, insets ->
            updateFullscreenBarMargins(insets)
            insets
        }
        setContentView(contentFrame)
        updateMiniPlayerLayout()
        catalogTitle.text = "Loading channels…"
        imports.execute {
            val loaded = try { catalog.get() } catch (_: java.util.concurrent.ExecutionException) {
                emptyList<Channel>() to emptyList<Channel>()
            } catch (_: InterruptedException) { return@execute }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                verifiedChannels = loaded.first
                imported = loaded.second
                populate()
                if (!moviesOpen && currentFocus == null) list.getChildAt(0)?.requestFocus()
                if (!stoppedForBackground && selected == null && playingMovie == null && !moviesOpen) {
                    if (savedInstanceState?.containsKey(STATE_MOVIE_URL) == true) restoreMoviePlayback(savedInstanceState)
                    else store.lastChannel?.let { last ->
                        play((verifiedChannels + imported).firstOrNull { it.key == last.key } ?: last)
                    }
                }
            }
        }
        // Restored fullscreen (after a process death): hide the
        // library, header and controls again and re-hide the system UI.
        if (fullScreen) updateFullscreenUi()
        // Playlist refresh + update check run after the first frame is up
        // (3s) — they are background network jobs and must not compete
        // with the app opening on a 1.5 GB TV.
        mainHandler.postDelayed({
            imports.execute {
                try { playlistStore.protectLegacyFiles(File(filesDir, "channels.m3u")) }
                catch (_: IOException) {
                    runOnUiThread { if (!isDestroyed) toast("Saved playlists could not be secured. Check device storage.") }
                }
            }
            refreshStalePlaylists()
        }, 3000)
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
        updateMiniPlayerLayout()
        showPlayerControls()
        if (enabled) {
            // Going fullscreen closes the Movies browser (the movie
            // keeps playing).
            if (moviesOpen) closeMovies()
            if (playingMovie != null || selected?.web == false) playPauseButton.requestFocus()
            else if (fullscreenButton.isShown) fullscreenButton.requestFocus()
        } else if (playingMovie != null) {
            // Back from a fullscreen movie: the mini player — the small
            // player stays on top and the Movies browser opens below it
            // (the playing title's details when rendered, else the list).
            if (currentMovieDetails != null && movieDetailsRendered) openMoviesToDetails()
            else openMovies()
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
        // A rotation in mini-player mode re-applies the mini-player
        // layout with the new display metrics.
        updateMiniPlayerLayout()
    }

    /**
     * Applies the current [fullScreen] state to the UI. Called from
     * [applyFullScreen] and from onCreate when restoring after a process death.
     * Touch devices get real fullscreen: immersive system UI, and only the
     * player on screen (the landscape layout already lets it fill the pane).
     * The fullscreen toggle is available in the on-video overlay.
     * Back also exits fullscreen.
     * On TV there is no rotation or system UI to hide, but fullscreen is
     * still real fullscreen: the browse pane, header and name chip all
     * hide and the paddings go to zero, so the player pane (weight 1)
     * fills the whole 1920x1080 screen. Only the video + on-video controls
     * stay; Back also exits.
     */
    private fun updateFullscreenUi() {
        fullscreenIcon.expanded = fullScreen
        fullscreenIcon.invalidateSelf()
        fullscreenButton.contentDescription = if (fullScreen) "Exit fullscreen" else "Fullscreen"
        screenBorder.visibility = if (fullScreen) View.GONE else View.VISIBLE
        screen.setBackgroundColor(if (fullScreen) Color.BLACK else WINDOWED_PLAYER_BACKGROUND)
        screen.clipToOutline = !fullScreen
        if (isTv) {
            if (fullScreen) {
                headerView.visibility = View.GONE
                libraryPane.visibility = View.GONE
                rootView.setPadding(0, 0, 0, 0)
                playerPane.setPadding(0, 0, 0, 0)
            } else {
                headerView.visibility = View.VISIBLE
                // The browse pane stays hidden while a movie plays.
                updateBrowseVisibility()
                rootView.setPadding(rootPaddingPx, dp(10), rootPaddingPx, dp(12))
                playerPane.setPadding(paneGapPx, 0, 0, 0)
            }
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (fullScreen)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            }
        }
        if (fullScreen) {
            hideSystemUi()
            headerView.visibility = View.GONE
            libraryPane.visibility = View.GONE
            rootView.setPadding(0, 0, 0, 0)
            playerPane.setPadding(0, 0, 0, 0)
        } else {
            showSystemUi()
            headerView.visibility = if (!isTv && isLandscape) View.GONE else View.VISIBLE
            // The browse pane stays hidden while a movie plays.
            updateBrowseVisibility()
            rootView.setPadding(rootPaddingPx, dp(if (isTv) 10 else 8), rootPaddingPx, dp(if (isTv) 12 else 8))
            playerPane.setPadding(if (isTv || isLandscape) paneGapPx else 0, 0, 0, 0)
        }
    }

    /** Mini player (v2.0.21): a movie playing windowed with the Movies
     * browser open. v2.0.23 docks it like the Live TV player instead
     * of floating it as a rounded card on top:
     * - TV: side-by-side like Live TV — the Movies browser is the left
     *   pane (55% of the width), the player the right pane (45%), both
     *   fixed, square-cornered, flush against each other.
     * - Phone: the player stays on top (16:9, at most 40% of the
     *   display height) with the browser filling the rest below it,
     *   but docked — square corners, flush, no floating gap.
     * - Both: the status bar stays visible with the "Back to TV"
     *   button, and the player controls stay pinned on the player
     *   (no auto-hide), so fullscreen is always one tap away.
     * When the mini player is off, the same function restores the
     * normal layout for the current orientation. It must run AFTER
     * updateFullscreenUi (which also sets the header), so it is called
     * at the end of applyFullScreen / applyOrientationLayout /
     * openMovies / openMoviesToDetails / closeMovies. Internal so the
     * Movies browser functions (extensions in MoviesSection.kt) can
     * call it. */
    /** Mini-player bar (v2.0.24, TV side-by-side only): the mini player
     * is 45% of the screen, too narrow for the full single-row bar —
     * the chips alone fill it, so the seek row had no room (no seek
     * bar, no time) and the subtitle overlapped the chips. In mini
     * mode the bar becomes two rows: seek + time on top, chips below,
     * slimmer (4dp vertical padding, 32dp disc/seek) so it fits the
     * short player. Phone keeps the single row (full width fits). */
    private fun setMiniPlayerBar(compact: Boolean) {
        if (compact == miniBarTwoRow && fullScreen == barStyledForFullscreen) return
        miniBarTwoRow = compact
        barStyledForFullscreen = fullScreen
        val chipRadius = if (fullScreen) 6 else componentRadiusDp
        val chipInset = dp(if (fullScreen) 8 else 6)
        overlayBar.background = shape(0xe6121216.toInt(), 0x44ffffff.toInt(),
            if (fullScreen) 30 else componentRadiusDp, 1)
        playPauseButton.background = playPauseButtonBackground(circle = fullScreen)
        val transport = listOf(playPauseButton, seekBackButton, seekForwardButton)
        val options = listOf(
            movieStopButton, moviePrevButton, movieNextButton, movieAudioButton,
            movieCCButton, movieQualityButton, goLiveButton, fullscreenButton,
        )
        (transport + options + seekRow + compactControls).forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }

        for (control in listOf(seekBackButton, seekForwardButton) + options.filterIsInstance<Button>()) {
            val seekStep = control === seekBackButton || control === seekForwardButton
            control.gravity = Gravity.CENTER
            control.includeFontPadding = false
            control.isSingleLine = true
            control.minWidth = 0
            control.minimumWidth = 0
            control.letterSpacing = 0f
            control.background = if (seekStep) overlayButtonBackground() else
                android.graphics.drawable.InsetDrawable(rowChipBackground(chipRadius), 0, chipInset, 0, chipInset)
            val padding = dp(if (compact) 3 else 9)
            control.setPadding(padding, 0, padding, 0)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                if (compact && !seekStep) {
                    control.setAutoSizeTextTypeUniformWithConfiguration(9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                } else {
                    control.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                }
            }
            control.textSize = if (compact && !seekStep) 12f else 13f
        }
        playPauseButton.setPadding(dp(11), dp(11), dp(11), dp(11))
        fullscreenButton.background = android.graphics.drawable.InsetDrawable(
            rowChipBackground(chipRadius), 0, chipInset, 0, chipInset,
        )
        fullscreenButton.setPadding(dp(9), dp(9), dp(9), dp(9))
        seekBar.layoutParams = LinearLayout.LayoutParams(0, dp(if (compact) 32 else 40), 1f)

        if (compact) {
            overlayBar.orientation = LinearLayout.VERTICAL
            overlayBar.setPadding(dp(9), dp(4), dp(9), dp(4))
            overlayBar.addView(compactControls, LinearLayout.LayoutParams(-1, -2))
            for (control in transport) {
                compactTransport.addView(control,
                    LinearLayout.LayoutParams(dp(if (control === playPauseButton) 46 else 40), dp(46)))
            }
            compactTransport.addView(seekRow, LinearLayout.LayoutParams(0, -2, 1f))
            for (control in options) {
                compactOptions.addView(control, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                })
            }
        } else {
            overlayBar.orientation = LinearLayout.HORIZONTAL
            val verticalPadding = dp(if (fullScreen) 7 else 5)
            overlayBar.setPadding(dp(12), verticalPadding, dp(12), verticalPadding)
            val route = listOf(playPauseButton, movieStopButton, seekBackButton, seekForwardButton, seekRow,
                moviePrevButton, movieNextButton, movieAudioButton, movieCCButton, movieQualityButton,
                goLiveButton, fullscreenButton)
            for (control in route) {
                val params = when (control) {
                    seekRow -> LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }
                    playPauseButton -> LinearLayout.LayoutParams(dp(46), dp(46))
                    fullscreenButton -> LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginStart = dp(4) }
                    else -> LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(4) }
                }
                overlayBar.addView(control, params)
            }
        }
    }

    private fun placePlayerControls(outsideVideo: Boolean) {
        val parent = if (outsideVideo) playerPane else screen
        if (overlayBar.parent !== parent) {
            (overlayBar.parent as? ViewGroup)?.removeView(overlayBar)
            if (outsideVideo) {
                playerPane.addView(overlayBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            } else {
                screen.addView(overlayBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            }
        }
        updateFullscreenBarMargins(screen.rootWindowInsets)
    }

    private fun updateFullscreenBarMargins(insets: WindowInsets?) {
        val params = overlayBar.layoutParams as? FrameLayout.LayoutParams ?: return
        var sides = dp(if (fullScreen) 14 else 8)
        var bottom = sides
        if (!isTv && fullScreen && android.os.Build.VERSION.SDK_INT >= 28) {
            insets?.displayCutout?.let { cutout ->
                sides = maxOf(sides, cutout.safeInsetLeft, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }
        if (params.marginStart == sides && params.marginEnd == sides && params.bottomMargin == bottom) return
        params.marginStart = sides
        params.marginEnd = sides
        params.bottomMargin = bottom
        overlayBar.layoutParams = params
    }

    internal fun updatePlayerPanelVisibility() {
        val idleMovieOnPortrait = !isTv && !isLandscape && !fullScreen &&
            moviesOpen && playingMovie == null && !browseHidden
        playerPane.visibility = if (idleMovieOnPortrait) View.GONE else View.VISIBLE
    }

    private fun showMobileHeaderMenu(anchor: View) {
        val actions = listOf(
            Triple(R.drawable.ic_list, if (browseHidden) "Show List" else "Hide List") {
                browseHidden = !browseHidden
                updateMiniPlayerLayout()
            },
            Triple(R.drawable.ic_download, "Open M3U") { choosePlaylist() },
            Triple(R.drawable.ic_playlist, "Playlists") { showPlaylists() },
            Triple(R.drawable.ic_tv, "Xtream") { askXtream() },
            Triple(R.drawable.ic_upload, "Check for updates") { checkForUpdateManually() },
        )
        val panel = column().apply {
            setBackgroundResource(R.drawable.dialog_surface)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val width = minOf(dp(260), resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(1))
        val popup = android.widget.PopupWindow(panel, width, -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
        }
        for ((icon, title, action) in actions) {
            val row = navItem(title, icon) {
                popup.dismiss()
                action()
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(48)).apply {
                if (panel.childCount > 0) topMargin = dp(4)
            })
        }
        popup.showAsDropDown(anchor, 0, dp(5), Gravity.END)
    }

    internal fun updateMiniPlayerLayout() {
        val wide = isTv || isLandscape
        splitPane.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val index = splitPane.indexOfChild(libraryPane)
        if ((wide && index != 0) || (!wide && index == 0)) {
            splitPane.removeView(libraryPane)
            if (wide) splitPane.addView(libraryPane, 0) else splitPane.addView(libraryPane)
        }
        if (wide) {
            val available = resources.displayMetrics.widthPixels - 2 * rootPaddingPx
            val width = (available * if (moviesOpen) 0.32f else 0.28f).toInt()
                .coerceIn(dp(if (isTv) 250 else 205), dp(if (isTv) 420 else 335))
            libraryPane.layoutParams = LinearLayout.LayoutParams(width, -1)
            playerPane.layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
            screen.layoutParams = LinearLayout.LayoutParams(-1, -1)
        } else {
            playerPane.layoutParams = LinearLayout.LayoutParams(-1, -2)
            libraryPane.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            screen.layoutParams = LinearLayout.LayoutParams(-1, miniPlayerHeight())
        }
        val gap = if (wide && !fullScreen && !browseHidden) paneGapPx else 0
        playerPane.setPadding(gap, 0, 0, 0)
        headerView.visibility = if (fullScreen) View.GONE else View.VISIBLE
        backToTvButton.isSelected = !moviesOpen
        moviesHeaderButton.isSelected = moviesOpen
        toggleListButton.text = if (browseHidden) "Show List" else "Hide List"
        if (!canAutoHidePlayerControls()) controlsVisible = true
        setMiniPlayerBar(!wide || (!fullScreen && !isTv))
        placePlayerControls(!wide && !fullScreen)
        updateBrowseVisibility()
        applyControlVisibility()
    }
    /** Mini-player screen height: 16:9 of the available width, at most
     * 40% of the display height (on the 1920x1080 TV that is 432px — a
     * small player on top with room for the movies section below). */
    private fun miniPlayerHeight(): Int {
        val widthPx = resources.displayMetrics.widthPixels - 2 * rootPaddingPx
        val byWidth = widthPx * 9 / 16
        val byHeight = (resources.displayMetrics.heightPixels * 0.4).toInt()
        return minOf(byWidth, byHeight)
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
        // While the Movies browser is open, channel number jumps and
        // up/down channel switching stay off — D-pad navigation belongs
        // to the open section.
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
            val movie = playingMovie
            val url = playingMovieUrl
            if (movie != null && url != null) {
                resumeSavedPlayback(
                    movie, url, playingMovieHeaders, savedMoviePosition,
                    episodeSeason = playingEpisodeSeason,
                    episodeNumber = playingEpisodeEpisode,
                )
            } else {
                selected?.let(::play)
            }
        }
        startNetworkTicker()
    }

    // Network speed + daily usage label (v2.0.44): ticks once a
    // second while the activity is visible; stopped in onStop.
    private fun startNetworkTicker() {
        mainHandler.removeCallbacks(networkTickRunnable)
        mainHandler.post(networkTickRunnable)
    }

    override fun onStop() {
        audioRequest++
        movieResumeRequest++
        mainHandler.removeCallbacks(networkTickRunnable)
        NetworkUsage.reset()
        // Capture the movie position BEFORE stopPlayback releases the player,
        // so coming back from background resumes the movie where it left off.
        if (playingMovie != null) savedMoviePosition = playerController?.position() ?: savedMoviePosition
        saveMovieProgress(async = false)
        stopPlayback()
        stoppedForBackground = true
        super.onStop()
    }

    private fun closeApp() {
        if (isFinishing) return
        stopCurrentPlayback()
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        // Never leak the update download dialog across a rotation/destroy.
        updateDownloadDialog?.dismiss()
        updateDownloadDialog = null
        imports.shutdownNow()
        downloadExecutor.shutdownNow()
        // LogoLoader's pool is shared across activity instances: killing it
        // on a rotation would crash the recreated activity the first time it
        // loads a channel logo — and fullscreen rotates on every toggle.
        if (!isChangingConfigurations) LogoLoader.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Use the system back gesture callback on newer Android versions")
    /** Back while something plays: stop it and return to the movie's
     * details screen when one was rendered, else to Live TV. */
    private fun backFromMoviePlayback() {
        val stoppedChannel = selected
        // A movie/episode played from its details screen returns
        // there (episode list for series) instead of Live TV: Play
        // closed the Movies browser, but the rendered details are
        // still in memory. Anything else (channel, resume without
        // rendered details) keeps the old behaviour.
        val wasMovie = playingMovie != null
        val details = currentMovieDetails
        stopCurrentPlayback()
        if (wasMovie && details != null && movieDetailsRendered) {
            openMoviesToDetails()
        } else {
            focusBrowse(stoppedChannel)
        }
    }

    /** Stops whatever is playing (movie or channel) and clears the movie
     * state — no navigation (no return to details, no focus change).
     * Used by backFromMoviePlayback and by closeMovies(stopMovie = true)
     * when the user leaves the Movies section while a movie plays in
     * the mini player. */
    internal fun stopCurrentPlayback() {
        saveMovieProgress(async = false)
        stopPlayback()
        selected = null
        playingMovie = null
        playingMovieUrl = null
        playingMovieHeaders = emptyMap()
        movieEnded = false
        playPauseButton.setImageResource(R.drawable.ic_play)
        setChannelMeta(null)
        status.text = ""
        applyControlVisibility()
        markPlayingRow()
        updateBrowseVisibility()
    }

    override fun onBackPressed() {
        if (!fullScreen && browseHidden) {
            browseHidden = false
            updateMiniPlayerLayout()
            if (moviesOpen) movieSearchInput.requestFocus() else focusBrowse()
        } else if (moviesOpen) {
            if (moviesDetailsPane.visibility == View.VISIBLE) {
                showMoviesList()
            } else if (moviesSeeAll != null) {
                // Back from a See All list returns to its tab.
                moviesSeeAll = null
                renderCurrentMoviesTab()
            } else {
                // Backing out of the Movies section stops a movie playing
                // in the mini player — otherwise it would keep playing in
                // the channels screen's small player.
                closeMovies(stopMovie = true)
            }
        } else if (fullScreen) {
            // Back from fullscreen: a movie opens the mini player (small
            // player on top, Movies section below, movie keeps playing);
            // a channel just exits fullscreen. (applyFullScreen(false)
            // opens the browser for a playing movie — same on phone and TV.)
            applyFullScreen(false)
        } else if (query.isNotEmpty()) {
            // Back from a search restores the full list (task 2) — the user
            // is never stuck on a filtered (or empty) result.
            clearSearch()
        } else if (isTv && browseLevel == BrowseLevel.CHANNELS) {
            // Back from the channels list returns to the categories —
            // playback keeps going; another Back stops it.
            setBrowseLevel(BrowseLevel.CATEGORIES)
        } else if (selected == null && playingMovie == null) {
            super.onBackPressed()
        } else {
            // Back while something plays stops it — save the movie's place
            // in Continue Watching before the player is released.
            backFromMoviePlayback()
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
        // (playing row, category, or movie controls). Fullscreen is
        // excluded — there is legitimately
        // nothing focusable while a channel plays fullscreen, and the
        // zap branch below owns those keys.
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            !moviesOpen && !fullScreen &&
            currentFocus == null
        ) {
            if (event.repeatCount == 0) focusBrowse()
            return true
        }
        if (isTv && !fullScreen && libraryPane.isShown &&
            event.action == KeyEvent.ACTION_DOWN
        ) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (!moviesOpen && moveChannelRowFocus(-1)) return true
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!moviesOpen && moveChannelRowFocus(1)) return true
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val focused = currentFocus
                    if (focused === favButton || focused === playPauseButton) {
                        if (moviesOpen) focusMoviePanel() else focusBrowse()
                        return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) &&
            !moviesOpen && currentFocus !is EditText &&
            channelSwitchKeysActive() && !controlsVisible
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
        if ((playingMovie != null && !movieEnded || selected?.web == false) && fullScreen && !moviesOpen &&
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
            if (currentFocus === seekBar && seekable &&
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
            // Demo UI: thin corner brackets with a wide clear gap in
            // the middle (like the demo's expand icon). The old thick
            // stroke (3dp) + long arms (0.32) left only a hairline
            // gap, so at real size the round caps merged and the icon
            // read as a plus/hash instead of four corners.
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            if (size <= 0f) return
            val arm = size * 0.20f
            val gap = size * 0.14f
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

    /** Demo-style back arrow, drawn in code (no vector asset needed):
     * a long thin shaft with a chevron head, like the demo's top-bar
     * back arrow. The old text "←" Button read as a small thin mark
     * next to the demo's proper arrow (measured ~29x19px on a 1920
     * TV frame vs the demo's ~48x44px). */
    private inner class BackArrowDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val size = minOf(b.width(), b.height()).toFloat()
            if (size <= 0f) return
            val cy = b.centerY().toFloat()
            val tipX = b.left + size * 0.10f
            val tailX = b.right - size * 0.05f
            val head = size * 0.30f
            canvas.drawLine(tipX, cy, tailX, cy, paint)
            canvas.drawLine(tipX, cy, tipX + head, cy - head, paint)
            canvas.drawLine(tipX, cy, tipX + head, cy + head, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = dp(30)

        override fun getIntrinsicHeight(): Int = dp(30)
    }

    companion object {
        private const val STATE_FULLSCREEN = "fullscreen"
        private val WINDOWED_PLAYER_BACKGROUND = 0xff060b28.toInt()
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
// ChannelBrowser, MovieDetails, PlaylistDialogs) use these
// bare. Companion-object members are NOT visible bare from extension
// functions in other files — that is why CI failed with 26 unresolved
// references. The private consts stay in the companion object.
internal enum class BrowseLevel { CATEGORIES, CHANNELS }

internal const val AUTO_REFRESH_AFTER_MS = 24 * 60 * 60 * 1000L
internal const val CONTROLS_HIDE_DELAY_MS = 3000L
internal const val STATE_MOVIE_URL = "movie_url"
internal const val STATE_MOVIE_TITLE = "movie_title"
internal const val STATE_MOVIE_POS = "movie_pos"
internal const val STATE_MOVIE_HEADERS = "movie_headers"
internal const val YT_LIVE_POLL_MS = 5 * 60 * 1000L
internal const val MIN_SAVE_POSITION_MS = 5_000L
