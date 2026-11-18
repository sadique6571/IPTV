package com.sadique.iptv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
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
    private var retryCount = 0
    private var triedHlsFallback = false
    private val retryHandler = Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> events.onBuffering(true)
                Player.STATE_READY -> {
                    events.onBuffering(false)
                    retryCount = 0
                    reportSeekable()
                }
                Player.STATE_ENDED -> {
                    events.onBuffering(false)
                    events.onEnded()
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
            events.onPlayingChanged(isPlaying)
        }
    }

    fun hasPlayer(): Boolean = player != null

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun play(url: String, headers: Map<String, String> = emptyMap()) {
        retryHandler.removeCallbacksAndMessages(null)
        if (headers != currentHeaders) {
            currentHeaders = headers
            stop()
        }
        currentUrl = url
        retryCount = 0
        triedHlsFallback = false
        ensurePlayer()
        val active = player ?: return
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

    /** Returns true when playback is running after the toggle. */
    fun togglePlayPause(): Boolean {
        val active = player ?: return false
        if (active.isPlaying) active.pause() else active.play()
        return active.isPlaying
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
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
    }

    private fun ensurePlayer() {
        if (player != null) return
        val selector = DefaultTrackSelector(context)
        trackSelector = selector
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            if (currentHeaders.isNotEmpty()) setDefaultRequestProperties(currentHeaders)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
        val active = ExoPlayer.Builder(context).setTrackSelector(selector)
            .setMediaSourceFactory(mediaSourceFactory).build()
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
    }

    private fun mediaItemFor(url: String, forceHls: Boolean = false): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        val lower = url.lowercase()
        when {
            forceHls || ".m3u8" in lower -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            ".mpd" in lower -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
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
                    active.setMediaItem(mediaItemFor(url, forceHls = triedHlsFallback))
                    active.prepare()
                    active.playWhenReady = true
                }
            }, delay)
            return
        }
        events.onError(friendlyMessage(error) + " Press Retry to try again.")
    }

    private fun friendlyMessage(error: PlaybackException): String = when (error.errorCode) {
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

    companion object {
        // Auto-retry: 5 attempts (was 3) — a flaky mobile connection gets
        // more chances before the user ever sees the manual Retry button.
        private const val MAX_RETRIES = 5
        private const val RETRY_BASE_MS = 2000L
    }
}
