package com.sadique.iptv

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox home feeds (v2.0.27): the New Movies, Recommended and Series
 * sections on the Movies home screen.
 *
 * Sources (from the MovieBox-TUI client and the MovieBox internal-API doc):
 * the tab-operating home feed, the filterable subject list, and the
 * trending / daily-rec / top-rec recommendation endpoints. Every function
 * here is best-effort: it returns an empty list when its endpoint fails,
 * and the caller falls back to the next source — a dead endpoint must
 * never blank the home screen. All subjects go through parseSearchMovie,
 * so the adult / CAM / no-resource filters apply to feed items too.
 *
 * Note: these endpoints cannot be verified from the sandbox (the hosts
 * region-block it), so the on-device check after the build is the real
 * test — same as the original MovieBox work.
 */
internal data class HomeSection(val title: String, val movies: List<MovieBox.Movie>)

/** One tab of the home feed (tabId 0 = main home, 2 = Movies, 5 = Indian
 * Web Series, 19 = Western TV Series; tabId 9 is ADULT — never used). */
internal fun MovieBox.homeFeed(tabId: Int): List<HomeSection> {
    val data = try {
        request("GET", "$BASE/tab-operating?page=1&tabId=$tabId&version=", null)
    } catch (_: IOException) {
        return emptyList()
    }
    val items = data.optJSONArray("items") ?: return emptyList()
    val sections = mutableListOf<HomeSection>()
    for (i in 0 until items.length()) {
        val group = items.optJSONObject(i) ?: continue
        val title = group.optString("title",
            group.optString("name",
                group.optString("moduleTitle",
                    group.optString("tabName", ""))))
        val movies = mutableListOf<MovieBox.Movie>()
        // Plain subject list.
        group.optJSONArray("subjects")?.let { arr ->
            for (j in 0 until arr.length()) {
                parseSearchMovie(arr.optJSONObject(j))?.let { movies += it }
            }
        }
        // Banner carousel: banner.banners[].subject
        group.optJSONObject("banner")?.optJSONArray("banners")?.let { banners ->
            for (j in 0 until banners.length()) {
                parseSearchMovie(banners.optJSONObject(j)?.optJSONObject("subject"))
                    ?.let { movies += it }
            }
        }
        // Custom rows: customData.items[].subject
        group.optJSONObject("customData")?.optJSONArray("items")?.let { arr ->
            for (j in 0 until arr.length()) {
                parseSearchMovie(arr.optJSONObject(j)?.optJSONObject("subject"))
                    ?.let { movies += it }
            }
        }
        val distinct = movies.distinctBy { it.subjectId }
        if (distinct.isNotEmpty()) sections += HomeSection(title, distinct)
    }
    return sections
}

/** New Movies: the filterable catalogue with the newest sort, falling
 * back to recent-year titles from the home feed. */
internal fun MovieBox.newMovies(): List<MovieBox.Movie> {
    // 1. Catalogue list, newest first.
    try {
        val body = JSONObject().apply {
            put("tabId", 2)
            put("page", 1)
            put("perPage", 20)
            put("classify", "All")
            put("country", "All")
            put("genre", "All")
            put("sort", "Newest")
            put("year", "All")
        }.toString()
        val data = request("POST", "$BASE/subject-api/list", body)
        val list = subjectsFrom(data).filter { !it.isSeries }
        if (list.isNotEmpty()) return list.take(20)
    } catch (_: IOException) {
        // Fall through to the home-feed fallback.
    }
    // 2. Home feed movies from the last two release years.
    val feed = (homeFeed(0) + homeFeed(2)).flatMap { it.movies }
    val recent = feed.filter { !it.isSeries && (it.year == "2026" || it.year == "2025") }
        .distinctBy { it.subjectId }
        .sortedByDescending { it.year }
    if (recent.isNotEmpty()) return recent.take(20)
    // 3. Whatever the home feed has — better than an empty section.
    return feed.filter { !it.isSeries }.distinctBy { it.subjectId }.take(20)
}

/** Recommended: trending, then the daily curated picks, then the
 * Discover top picks, then the home feed's trending section. */
internal fun MovieBox.recommended(): List<MovieBox.Movie> {
    val bodies = listOf(
        "$BASE/subject-api/trending/v2" to
            JSONObject().apply { put("page", 1); put("perPage", 20) }.toString(),
        "$BASE/subject-api/daily-movie-rec" to JSONObject().toString(),
        "$BASE/subject-api/top-rec" to
            JSONObject().apply { put("page", 1); put("perPage", 20) }.toString(),
    )
    for ((path, body) in bodies) {
        try {
            val data = request("POST", path, body)
            val list = subjectsFrom(data).filter { !it.isSeries }
            if (list.isNotEmpty()) return list.take(20)
        } catch (_: IOException) {
            continue
        }
    }
    val sections = homeFeed(0)
    val trending = sections.firstOrNull { it.title.contains("rend", ignoreCase = true) }
        ?: sections.firstOrNull()
    return trending?.movies?.filter { !it.isSeries }?.take(20) ?: emptyList()
}

/** Series: series titles from the home feed and the web-series tabs. */
internal fun MovieBox.seriesFeed(): List<MovieBox.Movie> {
    val feed = (homeFeed(0) + homeFeed(5) + homeFeed(19)).flatMap { it.movies }
    return feed.filter { it.isSeries }.distinctBy { it.subjectId }.take(20)
}

/** Subjects from any response shape: grouped (results[].subjects[]) or
 * flat (list / subjects / items / data). */
private fun MovieBox.subjectsFrom(data: JSONObject): List<MovieBox.Movie> {
    val out = mutableListOf<MovieBox.Movie>()
    val results = data.optJSONArray("results")
    if (results != null) {
        for (i in 0 until results.length()) {
            val subjects = results.optJSONObject(i)?.optJSONArray("subjects") ?: continue
            for (j in 0 until subjects.length()) {
                parseSearchMovie(subjects.optJSONObject(j))?.let { out += it }
            }
        }
        if (out.isNotEmpty()) return out
    }
    for (key in listOf("list", "subjects", "items", "data")) {
        val arr = data.optJSONArray(key) ?: continue
        for (i in 0 until arr.length()) {
            parseSearchMovie(arr.optJSONObject(i))?.let { out += it }
        }
        if (out.isNotEmpty()) return out
    }
    return out
}
