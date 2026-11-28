package com.sadique.iptv

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox movies: Continue Watching positions + favorite movies.
 *
 * Stored as small JSON arrays in SharedPreferences ("movies") — the same
 * best-effort, no-new-dependency pattern as ChannelStore. Stream URLs are
 * NEVER stored here: MovieBox links are signed and expire within hours,
 * so a resume re-resolves the movie from its subjectId at tap time.
 *
 * Changes are visible in memory immediately and written to disk with apply().
 */
internal class MovieStore(context: Context) {
    data class ContinueEntry(
        val subjectId: String,
        val title: String,
        val posterUrl: String,
        val positionMs: Long,
        val durationMs: Long,
        val quality: Int,
        val updatedAt: Long,
        // Series episodes: one entry per series (the latest episode watched).
        val season: Int = 0,
        val episode: Int = 0,
        val isSeries: Boolean = false,
    ) {
        /** "S1 · E3" for episodes, "" for movies. */
        val episodeLabel: String
            get() = if (isSeries && season > 0 && episode > 0) "S$season · E$episode" else ""
        val progress: Float
            get() = if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("movies", Context.MODE_PRIVATE)
    private var continueList: MutableList<ContinueEntry> = loadContinue()
    private var favorites: MutableList<MovieBox.Movie> = loadFavorites()

    @Synchronized
    fun continueWatching(): List<ContinueEntry> = continueList.toList()

    /** One card per title. Completing an episode moves the card to its successor. */
    @Synchronized
    fun saveProgress(entry: ContinueEntry, completed: Boolean, seasons: List<MovieBox.Season>) {
        if (entry.subjectId.isEmpty()) return
        if (completed) {
            markEpisodeWatched(entry.subjectId, entry.season, entry.episode)
        } else if (entry.positionMs < MIN_SAVE_MS) {
            return
        }
        val saved = if (completed) entry.afterCompletion(seasons) else entry
        if (saved == null) {
            removeContinue(entry.subjectId)
            return
        }
        continueList.removeAll { it.subjectId == entry.subjectId }
        continueList.add(0, saved)
        continueList.sortByDescending { it.updatedAt }
        while (continueList.size > MAX_CONTINUE) continueList.removeAt(continueList.lastIndex)
        persistContinue()
    }

    @Synchronized
    fun removeContinue(subjectId: String) {
        if (continueList.removeAll { it.subjectId == subjectId }) persistContinue()
    }

    @Synchronized
    fun watchedEpisodes(subjectId: String, season: Int): Set<String> =
        prefs.getStringSet("watched:$subjectId:$season", emptySet()).orEmpty().toSet()

    @Synchronized
    fun markEpisodeWatched(subjectId: String, season: Int, episode: Int) {
        if (subjectId.isBlank() || season <= 0 || episode <= 0) return
        val key = "watched:$subjectId:$season"
        val watched = prefs.getStringSet(key, emptySet()).orEmpty()
        if (episode.toString() !in watched) {
            prefs.edit().putStringSet(key, watched + episode.toString()).apply()
        }
    }

    @Synchronized
    fun favoritesList(): List<MovieBox.Movie> = favorites.toList()

    @Synchronized
    fun isFavorite(movie: MovieBox.Movie): Boolean =
        movie.subjectId.isNotEmpty() && favorites.any { it.subjectId == movie.subjectId }

    /** Returns true when the movie is a favorite after the toggle. */
    @Synchronized
    fun toggleFavorite(movie: MovieBox.Movie): Boolean {
        if (movie.subjectId.isEmpty()) return false
        val existing = favorites.indexOfFirst { it.subjectId == movie.subjectId }
        val nowFavorite = if (existing >= 0) {
            favorites.removeAt(existing)
            false
        } else {
            favorites.add(0, movie)
            while (favorites.size > MAX_FAVORITES) favorites.removeAt(favorites.lastIndex)
            true
        }
        persistFavorites()
        return nowFavorite
    }

    private fun loadContinue(): MutableList<ContinueEntry> {
        val list = mutableListOf<ContinueEntry>()
        try {
            val raw = prefs.getString(KEY_CONTINUE, null) ?: return list
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                list += ContinueEntry(
                    subjectId = id,
                    title = o.optString("title", ""),
                    posterUrl = o.optString("poster", ""),
                    positionMs = o.optLong("pos", 0L),
                    durationMs = o.optLong("dur", 0L),
                    quality = o.optInt("q", 0),
                    updatedAt = o.optLong("ts", 0L),
                    season = o.optInt("se", 0),
                    episode = o.optInt("ep", 0),
                    isSeries = o.optBoolean("series", false),
                )
            }
        } catch (_: Exception) {
            // Corrupt prefs — start empty rather than crashing.
        }
        list.sortByDescending { it.updatedAt }
        return list
    }

    private fun persistContinue() {
        try {
            val arr = JSONArray()
            for (e in continueList) {
                arr.put(
                    JSONObject().apply {
                        put("id", e.subjectId)
                        put("title", e.title)
                        put("poster", e.posterUrl)
                        put("pos", e.positionMs)
                        put("dur", e.durationMs)
                        put("q", e.quality)
                        put("ts", e.updatedAt)
                        put("se", e.season)
                        put("ep", e.episode)
                        put("series", e.isSeries)
                    },
                )
            }
            prefs.edit().putString(KEY_CONTINUE, arr.toString()).apply()
        } catch (_: Exception) {
            // Best-effort; playback must not depend on it.
        }
    }

    private fun loadFavorites(): MutableList<MovieBox.Movie> {
        val list = mutableListOf<MovieBox.Movie>()
        try {
            val raw = prefs.getString(KEY_FAVORITES, null) ?: return list
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                list += MovieBox.Movie(
                    subjectId = id,
                    title = o.optString("title", ""),
                    year = o.optString("year", ""),
                    posterUrl = o.optString("poster", ""),
                    isHindi = o.optBoolean("hindi", false),
                    isSeries = o.optBoolean("series", false),
                    releaseDate = o.optString("releaseDate", ""),
                )
            }
        } catch (_: Exception) {
            // Corrupt prefs — start empty rather than crashing.
        }
        return list
    }

    private fun persistFavorites() {
        try {
            val arr = JSONArray()
            for (m in favorites) {
                arr.put(
                    JSONObject().apply {
                        put("id", m.subjectId)
                        put("title", m.title)
                        put("year", m.year)
                        put("poster", m.posterUrl)
                        put("hindi", m.isHindi)
                        put("series", m.isSeries)
                        put("releaseDate", m.releaseDate)
                    },
                )
            }
            prefs.edit().putString(KEY_FAVORITES, arr.toString()).apply()
        } catch (_: Exception) {
            // Best-effort; playback must not depend on it.
        }
    }

    companion object {
        private const val MAX_CONTINUE = 20
        private const val MAX_FAVORITES = 100
        private const val MIN_SAVE_MS = 5_000L
        private const val KEY_CONTINUE = "continue_watching"
        private const val KEY_FAVORITES = "fav_movies"
    }
}

/** If episode metadata was unavailable at resume time, keep the series accessible. */
internal fun MovieStore.ContinueEntry.afterCompletion(seasons: List<MovieBox.Season>): MovieStore.ContinueEntry? {
    if (!isSeries) return null
    if (seasons.none { it.number == season && it.episodes.any { ep -> ep.number == episode } }) return this
    val next = adjacentEpisode(seasons, season, episode, 1) ?: return null
    return copy(season = next.season, episode = next.episode.number, positionMs = 0, durationMs = 0)
}
