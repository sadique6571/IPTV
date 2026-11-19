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
    playingDrama = null
    // The list is one list now — the playing channel's row is always
    // on screen, so focus just goes to it.
    val matchIndex = currentMatches.indexOfFirst { it.key == channel.key }
    // In fullscreen the list is hidden — pulling focus onto a hidden
    // row leaves OK doing nothing; focus stays where it is instead.
    if (matchIndex >= 0 && !fullScreen) focusChannel(channel)
    store.addRecent(channel)
    store.lastChannel = channel
    setChannelMeta(channel)
    // In fullscreen the bars stay hidden (Back exits first) — on TV
    // too: a channel change must not pop the name bar back over
    // the video.
    val showBars = !fullScreen
    infoBar.visibility = if (showBars) View.VISIBLE else View.GONE
    playPauseButton.text = "❚❚"
    // A fresh player starts at the stored volume, so a muted session
    // does not survive a channel change — reset the mute state here.
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


/** First on-video control that is currently visible — the D-pad
 * target for RIGHT from the channel list. Movies/dramas show the
 * overlay pill, so the first visible pill button is the landing.
 * Channels play on a clean screen (no pill) — RIGHT goes to the
 * on-video corner buttons: Quality first when it is showing
 * (multi-track stream), otherwise Favorite. */
internal fun MainActivity.firstOverlayControlId(): Int {
    if (overlayBar.visibility == View.VISIBLE) {
        return when {
            playPauseButton.visibility == View.VISIBLE -> playPauseButton.id
            speedButton.visibility == View.VISIBLE -> speedButton.id
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
    // A drama episode plays in the same WebView as a web channel, so
    // the ExoPlayer controls (play/pause, volume, speed, seek) stay
    // hidden for it too — the WebView consumes its own touches.
    val webChannel = selected?.web == true || playingDrama != null
    // A channel plays on a clean screen: no overlay pill, no meta
    // chip — the video is just the video. Movies keep their controls
    // (play/pause, seek); dramas keep the fullscreen button.
    val channelPlaying = selected != null && playingMovie == null && playingDrama == null
    overlayBar.visibility = if (channelPlaying || (!isTv && !controlsVisible && playingDrama == null)) View.GONE else View.VISIBLE
    metaChip.visibility = if (channelPlaying || isTv) View.GONE else View.VISIBLE
    playPauseButton.visibility = if (webChannel) View.GONE else View.VISIBLE
    val showQuality = channelPlaying && !fullScreen && !webChannel && videoOptions.size >= 2
    qualityButton.visibility = if (showQuality) View.VISIBLE else View.GONE
    favButton.visibility = if (channelPlaying && !fullScreen) View.VISIBLE else View.GONE
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
    if (playingMovie != null) {
        playPauseButton.nextFocusRightId = speedButton.id
        speedButton.nextFocusLeftId = playPauseButton.id
        speedButton.nextFocusRightId = fullscreenButton.id
        fullscreenButton.nextFocusLeftId = speedButton.id
        speedButton.nextFocusUpId = playPauseButton.nextFocusUpId
        fullscreenButton.nextFocusUpId = playPauseButton.nextFocusUpId
    }
    seekBar.nextFocusDownId = playPauseButton.id
    syncRowFocusTargets()
}


/** Shows the on-video controls (overlay pill + seek row) and schedules
 * their auto-hide on touch devices. On TV this is a no-op — the controls
 * always stay visible for D-pad navigation. Called on video tap, on any
 * control interaction, and when playback starts. */
internal fun MainActivity.showPlayerControls() {
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
internal fun MainActivity.hidePlayerControls() {
    if (isTv || scrubbing || selected?.web == true || playingDrama != null) return
    controlsVisible = false
    overlayBar.visibility = View.GONE
    seekRow.visibility = View.GONE
}


internal fun MainActivity.scheduleHideControls() {
    if (isTv || selected?.web == true || playingDrama != null) return
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


internal fun MainActivity.setSpeedText(label: String) {
    speedButton.text = label
}


internal fun MainActivity.showQualityDialog() {
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


internal fun MainActivity.showSpeedDialog() {
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
 * what the user asked to stop. Movies and dramas never zap — their
 * up/down belongs to the player controls. Consulted from
 * dispatchKeyEvent (see there for why onKeyDown can never see
 * these keys in fullscreen). */
internal fun MainActivity.channelSwitchKeysActive(): Boolean {
    if (playingMovie != null || playingDrama != null || selected == null) return false
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
 * video or a drama episode ending needs no action. */
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

/** Shows the playing channel in the on-video meta chip and the info bar. */
internal fun MainActivity.setChannelMeta(channel: Channel?) {
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
 * re-raise the border, meta chip, seek row, seek indicator, overlay
 * controls, corner buttons and number popup above them. Called at the
 * end of [play] and after adding a WebView. */
internal fun MainActivity.bringPlayerOverlaysToFront() {
    screenBorder.bringToFront()
    metaChip.bringToFront()
    seekRow.bringToFront()
    seekIndicator.bringToFront()
    overlayBar.bringToFront()
    favButton.bringToFront()
    qualityButton.bringToFront()
    numberOverlay.bringToFront()
    status.bringToFront()
    buffering.bringToFront()
}
