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
 * Playback: channels, web embeds, overlay controls, number keys.
 * Split out of MainActivity (v2.0.27) — bodies unchanged,
 * extension functions on MainActivity where the code touches activity state.
 */


internal fun MainActivity.play(channel: Channel) {
    movieResumeRequest++
    // A second OK expands a channel only while its player exists.
    if (channel.key == selected?.key &&
        ((channel.web && web != null) || playerController?.isPlaying() == true)) {
        focusChannel(channel)
        if (!fullScreen) applyFullScreen(true)
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
    // The list is one list now — the playing channel's row is always
    // on screen, so focus just goes to it.
    val matchIndex = currentMatches.indexOfFirst { it.key == channel.key }
    // In fullscreen the list is hidden — pulling focus onto a hidden
    // row leaves OK doing nothing; focus stays where it is instead.
    if (matchIndex >= 0 && !fullScreen) focusChannel(channel)
    store.addRecent(channel)
    store.lastChannel = channel
    setChannelMeta(channel)
    playPauseButton.setImageResource(R.drawable.ic_pause)
    // A fresh player starts at the stored volume, so a muted session
    // does not survive a channel change — reset the mute state here.
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
            playerController?.play(channel.url, live = true, backup = channelBackups[channel.key])
        }
    }
    // PlayerView (and the WebView) are added to the screen after the
    // overlays; re-raise the border, meta chip and overlay controls.
    bringPlayerOverlaysToFront()
    // Touch: show the on-video controls for the new channel, then let
    // them auto-hide (YouTube-style). On TV this is a no-op.
    showPlayerControls()
}


/** First on-video control that is currently visible — the D-pad
 * target for RIGHT from the channel list. Movies show the overlay
 * pill, so the first visible pill button is the landing.
 * Channels play on a clean screen (no pill) — RIGHT goes to the
 * on-video corner buttons: Quality first when it is showing
 * (multi-track stream), otherwise Favorite. */
internal fun MainActivity.firstOverlayControlId(): Int {
    if (overlayBar.visibility == View.VISIBLE) {
        return when {
            seekBackButton.visibility == View.VISIBLE -> seekBackButton.id
            playPauseButton.visibility == View.VISIBLE -> playPauseButton.id
            else -> fullscreenButton.id
        }
    }
    return when {
        qualityButton.visibility == View.VISIBLE -> qualityButton.id
        favButton.visibility == View.VISIBLE -> favButton.id
        // Nothing on screen (no channel playing yet) — the pill is
        // gone, so FocusFinder falls back to geometry; any id works.
        else -> fullscreenButton.id
    }
}


internal fun MainActivity.applyControlVisibility() {
    // A web channel plays in a WebView, so the ExoPlayer controls
    // (play/pause, volume, speed, seek) stay hidden for it — the
    // WebView consumes its own touches.
    val webChannel = selected?.web == true
    // A channel plays on a clean screen: no overlay pill — the video
    // is just the video. Movies keep their controls (play/pause,
    // seek). The name chip (top-left) shows whenever anything plays
    // windowed, and never in fullscreen.
    val channelPlaying = selected != null && playingMovie == null
    val hideForInactivity = !controlsVisible && (playingMovie != null || !isTv)
    overlayBar.visibility = if (channelPlaying || hideForInactivity) View.GONE else View.VISIBLE
    val somethingPlaying = selected != null || playingMovie != null
    metaChip.visibility = if (fullScreen || !somethingPlaying) View.GONE else View.VISIBLE
    playPauseButton.visibility = if (webChannel) View.GONE else View.VISIBLE
    val showQuality = channelPlaying && !fullScreen && !webChannel && videoOptions.size >= 2
    qualityButton.visibility = if (showQuality) View.VISIBLE else View.GONE
    favButton.visibility = if (channelPlaying && !fullScreen) View.VISIBLE else View.GONE
    val movie = playingMovie != null
    val width = if (movie) android.view.ViewGroup.LayoutParams.MATCH_PARENT else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
    val params = overlayBar.layoutParams
    if (params.width != width) {
        params.width = width
        overlayBar.layoutParams = params
    }
    seekRow.visibility = if (movie && seekable) View.VISIBLE else View.GONE
    val seekTarget = if (movie && seekable) seekBar.id else View.NO_ID
    // Movies: quality button in the controls row — adaptive tracks
    // (videoOptions) or the resolved stream list (one MP4 per quality).
    // Channels keep the corner button; movies play fullscreen, where
    // the corner button is hidden, so the row is the only place it
    // can live.
    val streamResolutions = if (movie) {
        currentMovieStreams.map { it.resolution }.filter { it > 0 }.distinct()
    } else {
        emptyList()
    }
    val showMovieQuality = movie && (videoOptions.size >= 2 || streamResolutions.size >= 2)
    movieQualityButton.visibility = if (showMovieQuality) View.VISIBLE else View.GONE
    if (showMovieQuality) {
        movieQualityButton.text = when {
            videoOptions.size >= 2 && selectedVideoIndex in videoOptions.indices ->
                videoOptions[selectedVideoIndex].label
            selectedQuality > 0 -> "${selectedQuality}p"
            else -> "Quality"
        }
    }
    // Audio is always offered while a movie/series plays (like Quality):
    // the dialog lists the audio tracks + dubs that exist.
    val audio = movie
    movieAudioButton.visibility = if (audio) View.VISIBLE else View.GONE
    // Series episode buttons: Prev unless on the first episode, Next
    // unless on the last (episode count from the loaded seasons).
    val episode = playingEpisodeEpisode
    val episodeCount = currentSeriesSeasons.firstOrNull { it.number == playingEpisodeSeason }?.episodeCount ?: 0
    val showPrev = movie && playingEpisodeSeason > 0 && episode > 1
    val showNext = movie && playingEpisodeSeason > 0 && episodeCount > 0 && episode < episodeCount
    moviePrevButton.visibility = if (showPrev) View.VISIBLE else View.GONE
    movieNextButton.visibility = if (showNext) View.VISIBLE else View.GONE
    // CC (English subtitles only): shown when this title has one.
    val showCC = movie && currentSubtitle != null
    movieCCButton.visibility = if (showCC) View.VISIBLE else View.GONE
    if (showCC) updateCCButton()
    // Demo UI: top title bar (movies only, same show/hide as the
    // bottom controls) and the -10s/+10s seek buttons (seekable
    // movies only).
    topTitleLabel.text = topBarTitle()
    topBar.visibility = if (movie && !hideForInactivity) View.VISIBLE else View.GONE
    val seekCluster = movie && seekable
    seekBackButton.visibility = if (seekCluster) View.VISIBLE else View.GONE
    seekForwardButton.visibility = if (seekCluster) View.VISIBLE else View.GONE
    // D-pad chain across the visible row buttons, in visual order.
    val rowButtons = listOf(
        moviePrevButton, movieNextButton, movieQualityButton,
        movieAudioButton, movieCCButton, fullscreenButton,
    ).filter { it.visibility == View.VISIBLE }
    for (i in rowButtons.indices) {
        val button = rowButtons[i]
        button.nextFocusLeftId = if (i == 0) {
            if (movie) {
                if (seekCluster) seekForwardButton.id else playPauseButton.id
            } else {
                View.NO_ID
            }
        } else {
            rowButtons[i - 1].id
        }
        button.nextFocusRightId = if (i == rowButtons.lastIndex) View.NO_ID else rowButtons[i + 1].id
        button.nextFocusUpId = seekTarget
    }
    // Demo UI: the -10s/+10s cluster sits between play/pause and the
    // row buttons; the top bar sits above the seek bar.
    seekBackButton.nextFocusRightId = playPauseButton.id
    seekBackButton.nextFocusLeftId = View.NO_ID
    seekBackButton.nextFocusUpId = seekTarget
    seekForwardButton.nextFocusLeftId = playPauseButton.id
    seekForwardButton.nextFocusRightId =
        if (rowButtons.isNotEmpty()) rowButtons.first().id else View.NO_ID
    seekForwardButton.nextFocusUpId = seekTarget
    playPauseButton.nextFocusLeftId = if (seekCluster) seekBackButton.id else View.NO_ID
    playPauseButton.nextFocusRightId =
        if (seekCluster) {
            seekForwardButton.id
        } else if (movie && rowButtons.isNotEmpty()) {
            rowButtons.first().id
        } else {
            View.NO_ID
        }
    playPauseButton.nextFocusUpId = seekTarget
    seekBar.nextFocusDownId = playPauseButton.id
    seekBar.nextFocusUpId = if (movie) topBackButton.id else View.NO_ID
    topBackButton.nextFocusDownId =
        if (movie && seekable) seekBar.id else if (movie) playPauseButton.id else View.NO_ID
    topBackButton.nextFocusLeftId = View.NO_ID
    topBackButton.nextFocusRightId = View.NO_ID
    topBackButton.nextFocusUpId = View.NO_ID
    // Up Next card (when visible) sits above the row's right end.
    if (upNextCard.visibility == View.VISIBLE) {
        fullscreenButton.nextFocusUpId = upNextCard.id
        upNextCard.nextFocusDownId = fullscreenButton.id
        upNextCard.nextFocusLeftId = View.NO_ID
        upNextCard.nextFocusRightId = View.NO_ID
        upNextCard.nextFocusUpId = View.NO_ID
    } else {
        fullscreenButton.nextFocusUpId = seekTarget
    }
    syncRowFocusTargets()
}


/** Reveal movie controls and restart the inactivity timer. */
internal fun MainActivity.showPlayerControls() {
    if (isTv && playingMovie == null) return
    mainHandler.removeCallbacks(hideControlsRunnable)
    controlsVisible = true
    applyControlVisibility()
    scheduleHideControls()
}


/** Hides the on-video controls. Never hides while the user
 * is scrubbing, and never hides for web channels — a WebView consumes
 * its own touches, so a tap could not bring the controls back. */
internal fun MainActivity.hidePlayerControls() {
    if ((isTv && playingMovie == null) || scrubbing || selected?.web == true) return
    controlsVisible = false
    overlayBar.visibility = View.GONE
    topBar.visibility = View.GONE
}


internal fun MainActivity.scheduleHideControls() {
    if ((isTv && playingMovie == null) || selected?.web == true) return
    mainHandler.removeCallbacks(hideControlsRunnable)
    mainHandler.postDelayed(hideControlsRunnable, CONTROLS_HIDE_DELAY_MS)
}


internal fun MainActivity.togglePlayerControls() {
    if (controlsVisible) {
        mainHandler.removeCallbacks(hideControlsRunnable)
        hidePlayerControls()
    } else {
        showPlayerControls()
    }
}


/** Keeps the on-video quality button's label in sync with the
 * selected track ("720p", or "Quality" for Auto). */
internal fun MainActivity.setQualityText(label: String) {
    qualityButton.text = label
}


internal fun MainActivity.showQualityDialog() {
    // Movies with single-file streams (one MP4 per quality) report a
    // single track, so track switching has nothing to pick — switch
    // the stream URL instead, resuming at the current position.
    if (playingMovie != null && videoOptions.size < 2) {
        val resolutions = currentMovieStreams.map { it.resolution }
            .filter { it > 0 }.distinct().sortedDescending()
        if (resolutions.size >= 2) {
            val checked = resolutions.indexOf(selectedQuality).coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle("Quality")
                .setSingleChoiceItems(resolutions.map { "${it}p" }.toTypedArray(), checked) { dialog, which ->
                    switchMovieQuality(resolutions[which])
                    dialog.dismiss()
                }.show()
            return
        }
    }
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


internal fun MainActivity.startSeekUpdates() {
    mainHandler.removeCallbacks(seekUpdater)
    if (seekable) mainHandler.post(seekUpdater)
}


internal fun MainActivity.stopSeekUpdates() {
    mainHandler.removeCallbacks(seekUpdater)
}


/** YouTube-style +-10s seek indicator: shows "-10s" / "+10s" on the
 * side of the seek (left = back, right = forward) for ~900ms. Called
 * from [seekMovieBy], so TV D-pad seeks and mobile double-taps both
 * show it. */
internal fun MainActivity.showSeekIndicator(deltaMs: Long) {
    mainHandler.removeCallbacks(seekIndicatorHideRunnable)
    seekIndicator.text = if (deltaMs < 0) "-10s" else "+10s"
    (seekIndicator.layoutParams as FrameLayout.LayoutParams).gravity =
        Gravity.CENTER_VERTICAL or if (deltaMs < 0) Gravity.START else Gravity.END
    seekIndicator.visibility = View.VISIBLE
    mainHandler.postDelayed(seekIndicatorHideRunnable, 900L)
}


internal fun MainActivity.showNumberBuffer() {
    numberOverlay.text = "Channel $numberBuffer"
    numberOverlay.visibility = View.VISIBLE
}


internal fun MainActivity.scheduleNumberJump() {
    numberTimeout?.let { mainHandler.removeCallbacks(it) }
    val jump = Runnable { jumpToNumber() }
    numberTimeout = jump
    mainHandler.postDelayed(jump, 2500)
}


internal fun MainActivity.jumpToNumber() {
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


/** Up/down switch channels ONLY in fullscreen (the user's order
 * 2026-11-19): in the list, up/down just moves the selection and OK
 * plays — a channel must never start without OK. Before this, the
 * list zapped on every press (the v22 remote channel +/-), which is
 * what the user asked to stop. Movies never zap — their up/down
 * belongs to the player controls. Consulted from
 * dispatchKeyEvent (see there for why onKeyDown can never see
 * these keys in fullscreen). */
internal fun MainActivity.channelSwitchKeysActive(): Boolean {
    if (playingMovie != null || selected == null) return false
    return fullScreen
}


internal fun MainActivity.stepChannel(delta: Int) {
    // One (or zero) matches: there is nowhere to zap to — and
    // zapping to the same channel would hit play()'s second-OK
    // branch and flip the app into fullscreen, making the whole
    // browse pane vanish after a single up/down press (a 1-result
    // search or a 1-channel filter did exactly that).
    if (currentMatches.size <= 1) return
    val current = currentMatches.indexOfFirst { it.key == selected?.key }
    val next = if (current < 0) 0
        else (current + delta + currentMatches.size) % currentMatches.size
    play(currentMatches[next])
}


internal fun MainActivity.playEmbed(videoId: String, loop: Boolean = true) {
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
    // channel never goes silent when the video ends. Live streams
    // play with loop=false (see the yt-live branch above) so a
    // restart is detected instead of replaying the finished stream.
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
    // Captured for the client below: an anonymous object inside an
    // extension function cannot see the extension receiver's members.
    val mainFrameHosts = allowMainFrameHosts
    player.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host.orEmpty()
            return (request.isForMainFrame && host !in mainFrameHosts) ||
                request.url.scheme != "https" ||
                (host !in setOf("www.youtube.com", "www.youtube-nocookie.com", "www.dailymotion.com",
                    "playsza.st", "challenges.cloudflare.com"))
        }
    }
    if (isYouTube) player.addJavascriptInterface(YtEndedBridge(this), "AndroidBridge")
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
 * video ending needs no action. */
private class YtEndedBridge(private val activity: MainActivity) {
    @JavascriptInterface
    fun onYouTubeEnded() {
        activity.runOnUiThread { activity.onEmbedEnded() }
    }
}


internal fun MainActivity.onEmbedEnded() {
    // A YouTube live stream ended (the broadcaster restarted it) —
    // re-resolve so the embed rejoins the live stream instead of
    // sitting on the related-videos end screen.
    val token = playingYtLive ?: return
    playEmbed(token)
}


internal fun MainActivity.scheduleYtLivePoll() {
    mainHandler.removeCallbacks(ytLivePollRunnable)
    mainHandler.postDelayed(ytLivePollRunnable, YT_LIVE_POLL_MS)
}


/** While a yt-live channel plays, re-check /live every 5 minutes and
 * rejoin when the video ID changes (the broadcaster restarted the
 * stream). This catches restarts the player never reports as ENDED
 * (e.g. a stall). One small fetch per check; a failed check just
 * waits for the next one. */
internal fun MainActivity.pollYtLive() {
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


internal fun MainActivity.stopPlayback() {
    stopWeb()
    playerController?.stop()
    buffering.visibility = View.GONE
    hideUpNextCard()
    stopSeekUpdates()
}


internal fun MainActivity.stopWeb() {
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


// ---- Qwen HarmonyOS look helpers ----

/** Shows the playing channel in the on-video meta chip (top-left). */
internal fun MainActivity.setChannelMeta(channel: Channel?) {
    val name = channel?.name ?: "Select a channel"
    metaName.text = name
    val category = channel?.group.orEmpty()
    metaCategory.text = category
    metaCategory.visibility = if (category.isEmpty()) View.GONE else View.VISIBLE
    metaLiveBadge.visibility = View.VISIBLE
}


/** PlayerView/WebView are added to [screen] after the overlays were built;
 * re-raise the border, meta chip, seek row, seek indicator, overlay
 * controls, corner buttons and number popup above them. Called at the
 * end of [play] and after adding a WebView. */
internal fun MainActivity.bringPlayerOverlaysToFront() {
    screenBorder.bringToFront()
    metaChip.bringToFront()
    seekIndicator.bringToFront()
    overlayBar.bringToFront()
    favButton.bringToFront()
    qualityButton.bringToFront()
    numberOverlay.bringToFront()
    status.bringToFront()
    buffering.bringToFront()
}
