package com.sadique.iptv

import android.view.View
import java.io.IOException

internal fun MovieBox.Details.audioDubs(): List<MovieBox.Dub> =
    (dubs + MovieBox.Dub(movie.subjectId, "Original", movie.title)).distinctBy { it.subjectId }

internal fun MainActivity.showMovieAudio(anchor: View) {
    val movie = playingMovie ?: return
    val tracks = playerController?.audioTracks().orEmpty().takeIf { it.size > 1 }.orEmpty()
    val dubs = playingDubs.filter { tracks.isEmpty() || it.subjectId != movie.subjectId }
    val labels = tracks.map { it.label } + dubs.map { it.language.ifBlank { it.label } }
    if (labels.isEmpty()) {
        toast("Is video mein aur audio option nahi hai.")
        return
    }
    val generation = audioRequest
    val popup = PlayerPopupMenu(this, anchor)
    for (track in tracks) {
        popup.row(track.label, {
            playerController?.audioTracks()
                ?.firstOrNull { it.override == track.override }?.selected == true
        }) {
            if (generation != audioRequest || playingMovie !== movie) return@row
            audioRequest++
            playerController?.selectAudio(track)
        }
    }
    for (dub in dubs) {
        popup.row(dub.language.ifBlank { dub.label }, {
            tracks.isEmpty() && playingMovie?.subjectId == dub.subjectId
        }) {
            if (generation != audioRequest || playingMovie !== movie) return@row
            if (dub.subjectId != movie.subjectId) switchMovieDub(movie, dub) { popup.refresh() }
            else audioRequest++
        }
    }
    popup.show()
}

private fun MainActivity.switchMovieDub(movie: MovieBox.Movie, dub: MovieBox.Dub, onSwitched: (() -> Unit)? = null) {
    val request = ++audioRequest
    val season = playingEpisodeSeason
    val episode = playingEpisodeEpisode
    toast("Audio load ho rahi hai…")
    imports.execute {
        val streams = try {
            val details = movieBox.details(dub.subjectId)
            if (season > 0 && episode > 0) movieBox.episodeStreams(details, season, episode)
            else movieBox.streams(details)
        } catch (_: IOException) {
            emptyList()
        }
        runOnUiThread {
            if (request != audioRequest || playingMovie !== movie || isDestroyed || isFinishing) return@runOnUiThread
            val controller = playerController ?: return@runOnUiThread
            val stream = streams.firstOrNull { it.resolution == selectedQuality } ?: streams.firstOrNull()
            if (stream == null) {
                toast("Is zubaan ka link dastiyab nahi hai.")
                return@runOnUiThread
            }
            val position = controller.position()
            val autoplay = controller.wantsPlayback
            // Dub switch: the new subject's own caption — its streams
            // were resolved with captions attached, like Play. (No
            // details fallback here: details stays inside the try
            // block above.)
            currentSubtitle = subtitleForStream(stream, null)
            playMovieStream(movie.copy(subjectId = dub.subjectId), stream.url, stream.headers,
                position, season, episode, playingDubs, autoplay)
            currentMovieStreams = streams
            selectedQuality = stream.resolution
            applyControlVisibility()
            onSwitched?.invoke()
        }
    }
}
