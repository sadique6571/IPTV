package com.sadique.iptv

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView

/**
 * AndroidX Media3 (ExoPlayer) wrapper. Owns one PlayerView inside [screen] and
 * one ExoPlayer instance, reused across channel switches. Handles adaptive
 * HLS/DASH playback, quality (track) selection, speed, volume, seek, and
 * error reporting with automatic retries.
 *
 * YouTube/Dailymotion web links are NOT played here — MainActivity keeps its
 * WebView fallback for those.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlayerController(
    private val context: Context,
    private val screen: FrameLayout,
    private val initialVolume: Float,
    private val events: Listener,
) {
    interface Listener {
        fun onBuffering(buffering: Boolean)
        fun onError(message: String)
        fun onEnded()
        fun onPlayingChanged(isPlaying: Boolean)
        fun onTracksChanged(options: List<VideoOption>, selectedIndex: Int)
        fun onSeekableChanged(seekable: Boolean, durationMs: Long)
    }

    data class VideoOption(val label: String, val groupIndex: Int, val trackIndex: Int)

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var currentTracks: Tracks? = null
    private var currentUrl: String? = null
    // Request headers for the current stream (MovieBox files need a
    // User-Agent, DASH manifests also Referer + Cookie). They live on the
    // player's HTTP data source factory, so a header change rebuilds the
    // player; plain channel switches (no headers) reuse the same player.
    private var currentHeaders: Map<String, String> = emptyMap()
    private var liveChannel = false
    private var backupUrl: String? = null
    private val bufferTimeout = Runnable {
        if (player?.playbackState == Player.STATE_BUFFERING && player?.playWhenReady == true) useBackup()
    }
    private var bufferEvents = 0
    private val stablePlayback = Runnable {
        retryCount = 0
        bufferEvents = 0
    }
    private var retryCount = 0
    private var rateLimitRetries = 0
    private var triedHlsFallback = false
    private val retryHandler = Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            retryHandler.removeCallbacks(bufferTimeout)
            when (state) {
                Player.STATE_BUFFERING -> {
                    events.onBuffering(true)
                    if (backupUrl != null) {
                        bufferEvents++
                        // Fail over after 15s of buffering (was 20s).
                        // 10s would race the v34 10s buffer-for-playback
                        // and switch healthy slow-starting channels to
                        // backup; 15s is safely past a healthy start.
                        // Immediate after 4 buffer events, as before.
                        retryHandler.postDelayed(bufferTimeout, if (bufferEvents >= 4) 0L else BUFFER_FAILOVER_MS)
                    }
                }
                Player.STATE_READY -> {
                    events.onBuffering(false)
                    reportSeekable()
                }
                Player.STATE_ENDED -> {
                    events.onBuffering(false)
                    if (liveChannel && player?.playWhenReady == true) {
                        if (!useBackup()) reconnectLive()
                    } else events.onEnded()
                }
                Player.STATE_IDLE -> events.onBuffering(false)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(error)
        }

        override fun onTracksChanged(tracks: Tracks) {
            reportTracks(tracks)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            retryHandler.removeCallbacks(stablePlayback)
            if (isPlaying) retryHandler.postDelayed(stablePlayback, 30_000L)
            events.onPlayingChanged(isPlaying)
        }
    }

    /** Subtitle (CC) for the current movie/episode, if any — kept across
     * retries so a retry does not drop the captions. */
    private var currentSubtitleConfig: MediaItem.SubtitleConfiguration? = null

    /** CC on/off (player CC button). Applied whenever a new player is
     * created, so a quality switch does not turn captions back on. */
    var subtitlesEnabled = true

    /** Enables or disables the text (subtitle) track. Named apply-*
     * because the var above already owns the setSubtitlesEnabled
     * setter name on the JVM. */
    fun applySubtitlesEnabled(enabled: Boolean) {
        subtitlesEnabled = enabled
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled).build(),
        )
    }

    /** Subtitle (CC) size in sp, set from MainActivity (the persisted
     * choice from the CC menu). Default 20sp — the previous ExoPlayer
     * default scaled with the view height and was far too big on TV. */
    var subtitleSizeSp: Float = 20f

    /** Subtitle (CC) background: the black box behind the text, like
     * the demo mockup (default on). Off restores the PR #13 text-only
     * look (transparent background + drop shadow). */
    var subtitleBackground: Boolean = true

    /** Whether the player controls are currently visible. While they
     * are, the bottom bar covers the lower part of the picture, so
     * subtitles lift above it (like Netflix); when the controls hide,
     * they drop back to 3% from the bottom edge. */
    private var subtitleControlsVisible = false

    fun setSubtitleControlsVisible(visible: Boolean) {
        if (subtitleControlsVisible == visible) return
        subtitleControlsVisible = visible
        applySubtitleStyle()
    }

    /** Subtitles like the demo mockup: white text on a
     * semi-transparent black box, close to the bottom of the picture.
     * With the box off, the old text-only look (transparent
     * background + drop shadow) keeps the text readable. Applied
     * whenever a new PlayerView is created, and whenever the size or
     * background changes. */
    fun applySubtitleStyle() {
        val view = playerView?.subtitleView ?: return
        val backgroundOn = subtitleBackground
        view.setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                if (backgroundOn) 0x99000000.toInt() else Color.TRANSPARENT,
                Color.TRANSPARENT,
                if (backgroundOn) CaptionStyleCompat.EDGE_TYPE_NONE
                else CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
                Color.BLACK, null,
            ),
        )
        view.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp)
        // 20% clears the ~60dp bottom bar on phones and TVs alike
        // (the bar is ~17% of a phone-landscape height, ~11% of a
        // TV's); 3% is the resting position with controls hidden.
        view.setBottomPaddingFraction(if (subtitleControlsVisible) 0.20f else 0.03f)
    }

    /** Change the subtitle size live (from the CC menu) and re-apply
     * the style — the playing subtitles resize at once. */
    fun setSubtitleSize(sizeSp: Float) {
        subtitleSizeSp = sizeSp
        applySubtitleStyle()
    }

    /** Change the subtitle background live (from the CC menu) and
     * re-apply the style — the black box appears/disappears at once. */
    fun setSubtitleBackground(enabled: Boolean) {
        subtitleBackground = enabled
        applySubtitleStyle()
    }

    fun hasPlayer(): Boolean = player != null

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun play(url: String, headers: Map<String, String> = emptyMap(), live: Boolean = false, backup: String? = null, subtitle: MediaItem.SubtitleConfiguration? = null) {
        retryHandler.removeCallbacksAndMessages(null)
        if (headers != currentHeaders) {
            currentHeaders = headers
            stop()
        }
        bufferEvents = 0
        liveChannel = live
        backupUrl = backup
        currentUrl = url
        retryCount = 0
        triedHlsFallback = false
        currentSubtitleConfig = subtitle
        rateLimitRetries = 0
        ensurePlayer(live)
        val active = player ?: return
        selectTrack(null)
        applySubtitlesEnabled(subtitlesEnabled)
        active.setMediaItem(mediaItemFor(url))
        active.prepare()
        active.playWhenReady = true
    }

    /** Manual retry: resets the retry counter and replays the current stream. */
    fun retry() {
        val url = currentUrl ?: return
        retryHandler.removeCallbacksAndMessages(null)
        retryCount = 0
        triedHlsFallback = false
        val active = player ?: return
        active.setMediaItem(mediaItemFor(url))
        active.prepare()
        active.playWhenReady = true
    }

    /** Returns true when playback is set to play after the toggle
     * (playWhenReady — immediate, unlike isPlaying, which lags on
     * resume until the first frame renders). */
    fun togglePlayPause(): Boolean {
        val active = player ?: return false
        if (active.playWhenReady) active.pause() else active.play()
        return active.playWhenReady
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }


    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    val wantsPlayback get() = player?.playWhenReady == true

    data class AudioChoice(val label: String, val selected: Boolean, val override: TrackSelectionOverride)

    fun audioTracks(): List<AudioChoice> = buildList {
        for (group in player?.currentTracks?.groups.orEmpty()) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = format.language?.takeUnless { it == "und" }
                    ?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
                val label = format.label ?: language ?: "Audio ${size + 1}"
                add(AudioChoice(label, group.isTrackSelected(index), TrackSelectionOverride(group.mediaTrackGroup, index)))
            }
        }
    }

    fun selectAudio(choice: AudioChoice) {
        val selector = trackSelector ?: return
        if (audioTracks().none { it.override == choice.override }) return
        selector.setParameters(selector.buildUponParameters().setOverrideForType(choice.override))
    }

    fun position(): Long = player?.currentPosition ?: 0L

    fun duration(): Long {
        val value = player?.duration ?: C.TIME_UNSET
        return if (value == C.TIME_UNSET) 0L else value
    }

    /** Null option means automatic (adaptive) quality. */
    fun selectTrack(option: VideoOption?) {
        val selector = trackSelector ?: return
        val params = selector.buildUponParameters()
        if (option == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val tracks = currentTracks ?: return
            val group = tracks.groups.getOrNull(option.groupIndex) ?: return
            params.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex)),
            )
        }
        selector.setParameters(params.build())
    }

    /** Releases the player and removes its view. Safe to call repeatedly. */
    fun stop() {
        retryHandler.removeCallbacksAndMessages(null)
        liveChannel = false
        backupUrl = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        trackSelector = null
        currentTracks = null
        playerView?.let { view ->
            view.player = null
            screen.removeView(view)
        }
        playerView = null
        currentUrl = null
        currentSubtitleConfig = null
        rateLimitRetries = 0
    }

    private fun ensurePlayer(live: Boolean = false) {
        if (player != null) return
        val selector = DefaultTrackSelector(context)
        trackSelector = selector
        // The user has a steady 4 Mbps connection: never let adaptive
        // selection pick a rendition above it (a 1080p track stalls
        // instead of playing), and start the bandwidth estimate at
        // 4 Mbps so the first track choice is right instead of ramping
        // up from the 1 Mbps default. A manual quality pick (track
        // override) still wins over this cap.
        selector.setParameters(
            selector.buildUponParameters().setMaxVideoBitrate(MAX_VIDEO_BITRATE).build(),
        )
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE).build()
        // Buffering (v2.0.34): the player used to start after ~2.5s of
        // buffer, so a slow free server stalled it at once. Now it
        // waits for 5s before starting (and 5s after a stall before
        // resuming) and keeps a 30-60s cushion while playing — short
        // network hiccups play through the buffer instead of freezing.
        // Cost: a channel takes ~5s to start, and live runs ~5s
        // behind real time (live data only arrives in real time).
        // Movies start faster than live channels (v2.0.4): a file plays
        // from a fast server, so 3s of buffer is enough to start, while
        // live keeps the 5s cushion from v2.0.34 (a slow free server
        // stalled at once with a shorter start buffer).
        val bufferForPlaybackMs =
            if (live) BUFFER_FOR_PLAYBACK_MS else MOVIE_BUFFER_FOR_PLAYBACK_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS, MAX_BUFFER_MS,
                bufferForPlaybackMs, BUFFER_AFTER_REBUFFER_MS,
            )
            .build()
        // Timeouts (v2.0.36): ExoPlayer's defaults are 8s/8s — a dead
        // server held the player for 8s before anything failed. A 5s
        // connect timeout fails fast on a dead host; the 8s read
        // timeout is kept explicit (a slow free server may legitimately
        // pause between chunk reads). Cross-protocol redirects are
        // allowed: many free IPTV links redirect http -> https (or
        // across hosts), and ExoPlayer refuses those by default.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .apply {
                if (currentHeaders.isNotEmpty()) setDefaultRequestProperties(currentHeaders)
            }
        // DefaultDataSource routes http(s) through the header-carrying
        // HTTP factory and file:// through a FileDataSource, so
        // downloaded movies (MoviesDownloads.kt) play in this same
        // player — a bare HttpDataSource cannot open local files.
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        val active = ExoPlayer.Builder(context).setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(loadControl).build()
        player = active
        active.volume = initialVolume.coerceIn(0f, 1f)
        active.addListener(playerListener)
        playerView = PlayerView(context).apply {
            player = active
            useController = false
            // FIT keeps the channel's own aspect ratio: 16:9 channels fill
            // a 16:9 TV screen edge to edge; 4:3 SD channels get black bars
            // on the sides — that is the channel's own picture shape, and
            // filling the screen would mean stretching or cropping it.
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            // Never focusable: this is a pure video surface, and a
            // focusable full-screen surface swallows D-pad focus on TV
            // (nothing inside it is reachable) — the on-video overlay
            // controls are the navigation path, not the surface.
            isFocusable = false
        }
        screen.addView(playerView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        // Netflix-style subtitles (no black box, smaller text, close to
        // the bottom) — applied at every player creation, so a player
        // rebuild after stop() does not lose the style.
        applySubtitleStyle()
    }

    private fun mediaItemFor(url: String, forceHls: Boolean = false): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val lower = url.lowercase()
        when {
            forceHls || ".m3u8" in lower -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            ".mpd" in lower -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        currentSubtitleConfig?.let { builder.setSubtitleConfigurations(listOf(it)) }
        return builder.build()
    }

    private fun reportTracks(tracks: Tracks) {
        currentTracks = tracks
        val options = mutableListOf<VideoOption>()
        var selected = -1
        for (groupIndex in tracks.groups.indices) {
            val group = tracks.groups[groupIndex]
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (trackIndex in 0 until group.length) {
                if (!group.isTrackSupported(trackIndex)) continue
                val format = group.getTrackFormat(trackIndex)
                val label = when {
                    format.height > 0 -> "${format.height}p"
                    format.bitrate > 0 -> "${format.bitrate / 1000} kbps"
                    else -> "Video ${options.size + 1}"
                }
                if (group.isTrackSelected(trackIndex)) selected = options.size
                options += VideoOption(label, groupIndex, trackIndex)
            }
        }
        events.onTracksChanged(options, selected)
    }

    private fun reportSeekable() {
        val active = player ?: return
        val duration = active.duration
        val seekable = active.isCurrentMediaItemSeekable && duration != C.TIME_UNSET
        events.onSeekableChanged(seekable, if (duration == C.TIME_UNSET) 0L else duration)
    }

    private fun useBackup(): Boolean {
        val url = backupUrl ?: return false
        val active = player ?: return false
        retryHandler.removeCallbacksAndMessages(null)
        backupUrl = null
        currentUrl = url
        triedHlsFallback = false
        // The fallback file gets its own full retry budget.
        retryCount = 0
        rateLimitRetries = 0
        events.onError("Switching to backup feed…")
        selectTrack(null)
        active.setMediaItem(mediaItemFor(url))
        active.prepare()
        return true
    }

    private fun reconnectLive() {
        val active = player ?: return
        if (retryCount >= MAX_RETRIES) {
            events.onError("Live feed interrupted. Select the channel to reconnect.")
            return
        }
        val delay = minOf(RETRY_BASE_MS shl retryCount, 16_000L)
        retryCount++
        events.onError("Reconnecting to live TV…")
        retryHandler.postDelayed({
            if (active.isCurrentMediaItemLive && active.playbackState != Player.STATE_ENDED) active.seekToDefaultPosition()
            else currentUrl?.let { active.setMediaItem(mediaItemFor(it, triedHlsFallback)) }
            active.prepare()
        }, delay)
    }

    private fun handleError(error: PlaybackException) {
        events.onBuffering(false)
        val active = player
        val url = currentUrl
        if (active == null || url == null) {
            events.onError(friendlyMessage(error))
            return
        }
        // Many IPTV feeds are HLS playlists served from extensionless URLs, which
        // ExoPlayer first reads as progressive streams. On a parse failure, retry
        // once with an explicit HLS type before giving up.
        val parseFailure = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
        if (parseFailure && !triedHlsFallback && ".m3u8" !in url.lowercase()) {
            triedHlsFallback = true
            events.onError("Trying another stream format…")
            active.setMediaItem(mediaItemFor(url, forceHls = true))
            active.prepare()
            active.playWhenReady = true
            return
        }
        // Permanent HTTP errors (404/403 — the stream is gone from
        // this server) never heal on retry, so do not burn all 5
        // attempts on them: a live channel still tries its backup
        // feed once (a different URL), everything else fails at once
        // with the friendly message. Timeouts and network errors
        // keep the full retry loop below.
        if (isPermanentHttpError(error)) {
            if (liveChannel && useBackup()) return
            events.onError(friendlyMessage(error) + " Select the stream again to reconnect.")
            return
        }
        if (liveChannel) {
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && retryCount == 0) {
                reconnectLive()
            } else if (!useBackup()) {
                reconnectLive()
            }
            return
        }
        // Rate-limited by the file server (HTTP 429 — the H5 CDN
        // throttles hard, and the app's own quick retries only extend
        // the ban): wait it out (60s, then 2 min) and then try the
        // other quality's file before giving up.
        if (isRateLimited(error)) {
            if (rateLimitRetries < MAX_RATE_LIMIT_RETRIES) {
                rateLimitRetries++
                val delay = if (rateLimitRetries == 1) RATE_LIMIT_RETRY_1_MS else RATE_LIMIT_RETRY_2_MS
                events.onError("Server busy — retrying in ${delay / 1000}s…")
                retryHandler.postDelayed({
                    if (player === active && currentUrl == url) {
                        active.prepare()
                        active.playWhenReady = true
                    }
                }, delay)
                return
            }
            if (useBackup()) return
            events.onError(friendlyMessage(error) + " Select the stream again to reconnect.")
            return
        }
        if (retryCount < MAX_RETRIES) {
            retryCount++
            // Backoff 2s → 4s → 8s → 16s → 16s (capped): the user never has
            // to press Retry for a hiccup, but the app does not hammer a
            // dead server with ever-growing waits either.
            val delay = minOf(RETRY_BASE_MS shl (retryCount - 1), 16_000L)
            events.onError("Connection lost. Retrying in ${delay / 1000}s… " +
                "(attempt $retryCount of $MAX_RETRIES)")
            retryHandler.postDelayed({
                if (currentUrl == url) {
                    active.prepare()
                    active.playWhenReady = true
                }
            }, delay)
            return
        }
        // Last resort for a dead first file: the other quality's URL.
        if (useBackup()) return
        events.onError(friendlyMessage(error) + " Select the stream again to reconnect.")
    }

    /** True for HTTP 4xx responses (except 408/429) — the stream is
     * permanently gone from this server, so retrying is pointless.
     * Walks the cause chain: ExoPlayer wraps the data-source error.
     * 5xx stays retryable (a server hiccup can heal), 408/429 are
     * transient by definition. */
    /** HTTP 429 (rate limit) anywhere in the error's cause chain. */
    private fun isRateLimited(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            val httpError = cause as? HttpDataSource.InvalidResponseCodeException
            if (httpError != null) return httpError.responseCode == 429
            cause = cause.cause
        }
        return false
    }

    private fun isPermanentHttpError(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        while (cause != null) {
            val httpError = cause as? HttpDataSource.InvalidResponseCodeException
            if (httpError != null) {
                val code = httpError.responseCode
                return code in 400..499 && code != 408 && code != 429
            }
            cause = cause.cause
        }
        return false
    }

    private fun friendlyMessage(error: PlaybackException): String {
        // The file server's rate limit (HTTP 429): the file itself is
        // fine — the server is refusing this network for now.
        if (isRateLimited(error)) return "Server busy (too many requests) — try again in a few minutes."
        return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "No network connection to the stream."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Stream server refused the request."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "Stream not found on the server."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "Stream format is not supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ->
            "This device cannot decode the stream."
        PlaybackException.ERROR_CODE_TIMEOUT -> "Stream timed out."
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Live stream moved on."
        else -> "Stream unavailable (${error.errorCodeName})."
        }
    }

    companion object {
        // Auto-retry: 5 attempts (was 3) — a flaky mobile connection gets
        // more chances before the user ever sees the manual Retry button.
        private const val MAX_RETRIES = 5
        // Rate-limit retries (HTTP 429): the file server throttles hard,
        // so wait it out instead of hammering — 60s, then 2 min.
        private const val MAX_RATE_LIMIT_RETRIES = 2
        private const val RATE_LIMIT_RETRY_1_MS = 60_000L
        private const val RATE_LIMIT_RETRY_2_MS = 120_000L
        private const val RETRY_BASE_MS = 2000L
        // Streaming tuning for a steady 4 Mbps connection (v2.0.34).
        private const val MAX_VIDEO_BITRATE = 3_500_000
        private const val INITIAL_BITRATE_ESTIMATE = 4_000_000L
        private const val MIN_BUFFER_MS = 30_000
        private const val MAX_BUFFER_MS = 60_000
        private const val BUFFER_FOR_PLAYBACK_MS = 5_000
        private const val MOVIE_BUFFER_FOR_PLAYBACK_MS = 3_000
        private const val BUFFER_AFTER_REBUFFER_MS = 5_000
        // Fail-fast (v2.0.36): HTTP timeouts + backup failover delay.
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 8000
        private const val BUFFER_FAILOVER_MS = 15_000L
    }
}

/** Subtitle configuration for one English subtitle (CC): the MIME type
 * comes from the file extension, so an unknown format returns null
 * instead of a track ExoPlayer cannot render. */
internal fun subtitleConfigFor(subtitle: MovieBox.Subtitle): MediaItem.SubtitleConfiguration? {
    val lower = subtitle.url.substringBefore("?").lowercase()
    val mime = when {
        lower.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
        lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
        lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        lower.endsWith(".ttml") -> MimeTypes.APPLICATION_TTML
        else -> return null
    }
    return MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
        .setMimeType(mime)
        .setLanguage("en")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}
