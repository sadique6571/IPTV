package com.sadique.iptv

import android.app.Activity
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
        ((channel.web && web != null) || playerController?.canKeepCurrentStream() == true)) {
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
            val preferred = getSharedPreferences("iptv_live_quality", Context.MODE_PRIVATE)
                .getString(channel.qualityPreferenceKey(), null)
            playerController?.setLiveCaptions(liveCaptions)
            playerController?.play(channel.url, live = true, backup = channelBackups[channel.key], liveQuality = preferred)
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
            playPauseButton.visibility == View.VISIBLE -> playPauseButton.id
            seekBackButton.visibility == View.VISIBLE -> seekBackButton.id
            else -> fullscreenButton.id
        }
    }
    return when {
        favButton.visibility == View.VISIBLE -> favButton.id
        // Nothing on screen (no channel playing yet) — the pill is
        // gone, so FocusFinder falls back to geometry; any id works.
        else -> fullscreenButton.id
    }
}


internal fun MainActivity.canAutoHidePlayerControls(): Boolean =
    fullScreen && selected?.web != true

internal fun MainActivity.applyControlVisibility() {
    val movie = playingMovie != null
    val channel = selected != null && !movie
    val live = channel && selected?.web == false
    val visible = controlsVisible || !canAutoHidePlayerControls()
    updatePlayerPanelVisibility()
    centerPlayButton.visibility = if ((movie || live) && playerController?.hasPlayer() == true &&
        playerController?.wantsPlayback == false
    ) View.VISIBLE else View.GONE
    overlayBar.visibility = if (visible && (movie || channel)) View.VISIBLE else View.GONE
    metaChip.visibility = View.GONE
    favButton.visibility = if (channel && !fullScreen) View.VISIBLE else View.GONE
    if (overlayBar.layoutParams.width != -1) overlayBar.layoutParams = overlayBar.layoutParams.apply { width = -1 }

    playPauseButton.visibility = if (movie || live) View.VISIBLE else View.GONE
    movieStopButton.visibility = if (movie && !fullScreen) View.VISIBLE else View.GONE
    val canSeek = (movie || live) && seekable
    seekRow.visibility = if (canSeek) View.VISIBLE else View.GONE
    seekBackButton.visibility = seekRow.visibility
    seekForwardButton.visibility = seekRow.visibility
    val episode = playingEpisodeEpisode
    val showPrev = movie && (adjacentEpisode(playingSeriesSeasons, playingEpisodeSeason, episode, -1) != null ||
        (playingEpisodeSeason > 0 && episode > 1 && playingSeriesSeasons.none { it.number == playingEpisodeSeason }))
    moviePrevButton.visibility = if (channel || showPrev) View.VISIBLE else View.GONE
    movieNextButton.visibility = if (channel || movie && nextMovieEpisode() != null) View.VISIBLE else View.GONE
    moviePrevButton.contentDescription = if (channel) "Previous channel" else "Previous episode"
    movieNextButton.contentDescription = if (channel) "Next channel" else "Next episode"
    movieAudioButton.visibility = if (movie) View.VISIBLE else View.GONE
    movieCCButton.visibility = if (movie && currentSubtitle != null ||
        live && playerController?.hasCaptionTracks() == true
    ) View.VISIBLE else View.GONE
    if (movie) updateCCButton() else {
        movieCCButton.isSelected = liveCaptions
        movieCCButton.contentDescription = if (liveCaptions) "Subtitles on" else "Subtitles off"
    }
    val movieQualities = movie && currentMovieStreams.map { it.resolution }.filter { it > 0 }.distinct().size >= 2
    movieQualityButton.visibility = if ((movie || live) && videoOptions.isNotEmpty() ||
        movieQualities || playerController?.isTimeShifting == true
    ) View.VISIBLE else View.GONE
    goLiveButton.visibility = if (live) View.VISIBLE else View.GONE
    goLiveButton.text = if (playerController?.isTimeShifting == true) "Go Live" else "Live"
    compactOptions.visibility = if ((0 until compactOptions.childCount).any {
        compactOptions.getChildAt(it).visibility == View.VISIBLE
    }) View.VISIBLE else View.GONE
    topBar.visibility = if ((movie || channel) && visible || moviesOpen) View.VISIBLE else View.GONE
    topBackButton.visibility = if (movie && fullScreen) View.VISIBLE else View.GONE
    topTitleLabel.text = if (movie) topBarTitle() else selected?.name ?: if (moviesOpen) {
        currentPlayTitle.ifBlank { "Movies & Series" }
    } else ""
    if (movie && topTitleLabel.text.isBlank()) fetchTopBarTitle(playingMovie?.subjectId.orEmpty())
    playerController?.setSubtitleControlsVisible(visible && (movie || live) && overlayBar.parent === screen)

    val buttons = listOf(
        playPauseButton, movieStopButton, seekBackButton, seekForwardButton,
        moviePrevButton, movieNextButton, movieAudioButton, movieCCButton,
        movieQualityButton, goLiveButton, fullscreenButton,
    ).filter { it.visibility == View.VISIBLE }
    for ((index, button) in buttons.withIndex()) {
        button.nextFocusLeftId = buttons.getOrNull(index - 1)?.id ?: View.NO_ID
        button.nextFocusRightId = buttons.getOrNull(index + 1)?.id ?: View.NO_ID
        button.nextFocusUpId = if (canSeek) seekBar.id else if (topBackButton.isShown) topBackButton.id else View.NO_ID
    }
    seekBar.nextFocusDownId = buttons.firstOrNull()?.id ?: View.NO_ID
    seekBar.nextFocusUpId = if (topBackButton.isShown) topBackButton.id else View.NO_ID
    topBackButton.nextFocusDownId = if (canSeek) seekBar.id else playPauseButton.id
    if (upNextCard.visibility == View.VISIBLE) {
        fullscreenButton.nextFocusUpId = upNextCard.id
        upNextCard.nextFocusDownId = fullscreenButton.id
    }
    if (!visible) overlayBar.visibility = View.GONE
    syncRowFocusTargets()
}

internal fun MainActivity.togglePlayerPlayback() {
    val controller = playerController ?: return
    val resume = controller.togglePlayPause()
    if (playingMovie == null && selected?.web == false) {
        status.text = when {
            resume -> ""
            controller.isTimeShifting -> "Paused · saving live TV"
            else -> "Paused · live window only"
        }
    }
    showPlayerControls()
}

internal fun MainActivity.showPlayerControls() {
    mainHandler.removeCallbacks(hideControlsRunnable)
    controlsVisible = true
    applyControlVisibility()
    scheduleHideControls()
}

internal fun MainActivity.hidePlayerControls() {
    if (!canAutoHidePlayerControls() || scrubbing || playerController?.isPlaying() != true) return
    controlsVisible = false
    applyControlVisibility()
}

internal fun MainActivity.scheduleHideControls() {
    mainHandler.removeCallbacks(hideControlsRunnable)
    if (!canAutoHidePlayerControls() || !controlsVisible) return
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


internal fun MainActivity.showQualityPopup(anchor: View) {
    val channel = selected?.takeIf { playingMovie == null }
    val controller = playerController
    if (channel != null && controller?.isTimeShifting == true) {
        val popup = PlayerPopupMenu(this, anchor)
        popup.row(controller.bufferedQualityLabel?.takeIf { it.isNotBlank() } ?: "Buffered quality", { true }) {}
        popup.row("Go Live to change quality", { false }) { controller.goLive() }
        popup.show()
        return
    }
    // Movies with single-file streams (one MP4 per quality) report a
    // single track, so track switching has nothing to pick — switch
    // the stream URL instead, resuming at the current position.
    if (playingMovie != null && videoOptions.size < 2) {
        val resolutions = currentMovieStreams.map { it.resolution }
            .filter { it > 0 }.distinct().sortedDescending()
        if (resolutions.size >= 2) {
            val popup = PlayerPopupMenu(this, anchor)
            for (resolution in resolutions) {
                popup.row("${resolution}p", { selectedQuality == resolution }) {
                    switchMovieQuality(resolution)
                }
            }
            popup.show()
            return
        }
    }
    val popup = PlayerPopupMenu(this, anchor)
    val options = videoOptions.toList()
    if (options.size > 1) popup.row("Auto", {
        if (channel != null) controller?.liveQualityKey == null else selectedVideoIndex !in options.indices
    }) {
        if (channel != null) {
            controller?.setLiveQuality(null)
            getSharedPreferences("iptv_live_quality", Context.MODE_PRIVATE).edit()
                .remove(channel.qualityPreferenceKey()).apply()
        } else controller?.selectTrack(null)
        selectedVideoIndex = -1
    }
    for ((index, option) in options.withIndex().sortedWith(compareBy({ it.value.height }, { it.value.bitrate }))) {
        popup.row(option.label, { options.size == 1 || selectedVideoIndex == index }) {
            if (channel != null) {
                controller?.setLiveQuality(option)
                getSharedPreferences("iptv_live_quality", Context.MODE_PRIVATE).edit()
                    .putString(channel.qualityPreferenceKey(), option.qualityKey).apply()
            } else controller?.selectTrack(option)
            selectedVideoIndex = index
        }
    }
    popup.show()
}

private fun Channel.qualityPreferenceKey(): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }


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
    playingChannelLabel.text = if (channel != null) "Playing: $name" else ""
    playingChannelLabel.visibility = if (channel != null) View.VISIBLE else View.GONE
    metaName.text = name
    val category = channel?.group.orEmpty()
    metaCategory.text = category
    metaCategory.visibility = if (category.isEmpty()) View.GONE else View.VISIBLE
    metaLiveBadge.visibility = View.VISIBLE
}


/** PlayerView/WebView are added to [screen] after the overlays were built;
 * re-raise the border, meta chip, seek row, seek indicator, overlay
 * controls, corner buttons and number popup above them. Called at the
 * end of [play] and after adding a WebView. v2.0.17: topBar and
 * upNextCard were missing from this list, so the PlayerView (added on
 * top at play time) covered them — the top bar's title showed only
 * "Th" (the bit sticking out in the pillarbox margin) and its scrim
 * was invisible over the video, while the bottom bar (raised here)
 * showed fine. */
internal fun MainActivity.bringPlayerOverlaysToFront() {
    centerPlayButton.bringToFront()
    screenBorder.bringToFront()
    metaChip.bringToFront()
    seekIndicator.bringToFront()
    overlayBar.bringToFront()
    topBar.bringToFront()
    upNextCard.bringToFront()
    favButton.bringToFront()
    numberOverlay.bringToFront()
    status.bringToFront()
    buffering.bringToFront()
}
