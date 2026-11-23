package com.sadique.iptv

import android.app.AlertDialog
import java.io.IOException

internal fun MovieBox.Details.audioDubs(): List<MovieBox.Dub> =
    (dubs + MovieBox.Dub(movie.subjectId, "Original", movie.title)).distinctBy { it.subjectId }

internal fun MainActivity.showMovieAudio() {
    val movie = playingMovie ?: return
    val tracks = playerController?.audioTracks().orEmpty().takeIf { it.size > 1 }.orEmpty()
    val dubs = playingDubs.filter { tracks.isEmpty() || it.subjectId != movie.subjectId }
    val labels = tracks.map { it.label } + dubs.map { it.language.ifBlank { it.label } }
    val checked = if (tracks.isEmpty()) dubs.indexOfFirst { it.subjectId == movie.subjectId }
        else tracks.indexOfFirst { it.selected }
    val generation = audioRequest
    AlertDialog.Builder(this).setTitle("Audio language")
        .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
            dialog.dismiss()
            if (generation != audioRequest || playingMovie !== movie) return@setSingleChoiceItems
            if (which < tracks.size) {
                audioRequest++
                playerController?.selectAudio(tracks[which])
            } else {
                val dub = dubs[which - tracks.size]
                if (dub.subjectId != movie.subjectId) switchMovieDub(movie, dub)
                else audioRequest++
            }
        }.show()
}

private fun MainActivity.switchMovieDub(movie: MovieBox.Movie, dub: MovieBox.Dub) {
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
            playMovieStream(movie.copy(subjectId = dub.subjectId), stream.url, stream.headers,
                position, season, episode, playingDubs, autoplay)
            currentMovieStreams = streams
            selectedQuality = stream.resolution
            applyControlVisibility()
        }
    }
}
