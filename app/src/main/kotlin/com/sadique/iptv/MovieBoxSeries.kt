package com.sadique.iptv

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox series support (v2.0.27): seasons, episodes and per-episode
 * streams. Movies resolve one file per title; a series resolves one file
 * per (season, episode) through the same three sources — resourceDetectors
 * (se/ep-tagged files), play-info/v2 with se/ep, and the resource list
 * with se/ep — with the same placeholder / CAM filtering as movies.
 *
 * The season-info response shape is parsed defensively (several key
 * spellings), and when it yields nothing the seasons are derived from
 * the resource list's own se/ep tags — the structure of a series is
 * visible in its files even when the metadata endpoint changes.
 */
internal fun MovieBox.seasons(subjectId: String): List<MovieBox.Season> {
    // 1. season-info endpoint.
    try {
        val data = request(
            "GET",
            "$BASE/subject-api/season-info?subjectId=${urlEncode(subjectId)}",
            null,
        )
        val parsed = parseSeasons(data)
        if (parsed.isNotEmpty()) return parsed
    } catch (_: IOException) {
        // Fall through to the resource-list derivation.
    }
    // 2. Derive seasons from the resource list's se/ep tags.
    val pairs = mutableSetOf<Pair<Int, Int>>()
    for (page in 1..2) {
        try {
            val data = request(
                "GET",
                "$BASE/subject-api/resource?subjectId=${urlEncode(subjectId)}&page=$page&perPage=20",
                null,
            )
            val items = data.optJSONArray("list") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val se = longValue(item, "se").toInt()
                val ep = longValue(item, "ep").toInt()
                if (se > 0 && ep > 0) pairs += se to ep
            }
        } catch (_: IOException) {
            break
        }
    }
    return pairs.groupBy({ it.first }, { it.second })
        .map { (se, eps) ->
            MovieBox.Season(
                number = se,
                episodes = eps.sorted().distinct()
                    .map { MovieBox.EpisodeRef(number = it) },
            )
        }
        .sortedBy { it.number }
}

private fun MovieBox.parseSeasons(data: JSONObject): List<MovieBox.Season> {
    val arrays = listOfNotNull(
        data.optJSONArray("seasons"),
        data.optJSONArray("seasonList"),
        data.optJSONArray("list"),
    )
    val seasons = mutableListOf<MovieBox.Season>()
    for (arr in arrays) {
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val number = longKeys(s, "se", "season", "seasonNumber", "index").toInt()
            if (number <= 0) continue
            val name = s.optString("name", s.optString("title", s.optString("seasonName", "")))
            val episodes = mutableListOf<MovieBox.EpisodeRef>()
            val epArr = s.optJSONArray("episodes")
                ?: s.optJSONArray("episodeList")
                ?: s.optJSONArray("eps")
            if (epArr != null) {
                for (j in 0 until epArr.length()) {
                    val e = epArr.optJSONObject(j) ?: continue
                    val epNum = longKeys(e, "ep", "episode", "episodeNumber", "index").toInt()
                    if (epNum <= 0) continue
                    episodes += MovieBox.EpisodeRef(
                        number = epNum,
                        title = e.optString("title", e.optString("name", "")),
                    )
                }
            } else {
                val count = longKeys(s, "episodeCount", "epCount", "totalEpisode", "count").toInt()
                for (n in 1..count) episodes += MovieBox.EpisodeRef(number = n)
            }
            if (episodes.isNotEmpty()) {
                seasons += MovieBox.Season(number = number, name = name, episodes = episodes.sortedBy { it.number })
            }
        }
        if (seasons.isNotEmpty()) return seasons.sortedBy { it.number }
    }
    return emptyList()
}

/** First non-zero long among several key spellings (the API is not
 * consistent about se/season/ep/episode naming). */
private fun MovieBox.longKeys(obj: JSONObject, vararg keys: String): Long {
    for (key in keys) {
        val v = obj.optLong(key, 0L)
        if (v != 0L) return v
    }
    return 0L
}

/** Playable streams for one episode, best first. The details overload
 * reuses detectors the caller already fetched (no second details call). */
internal fun MovieBox.episodeStreams(subjectId: String, season: Int, episode: Int): List<MovieBox.MovieStream> =
    episodeStreams(details(subjectId), season, episode)

internal fun MovieBox.episodeStreams(
    details: MovieBox.Details,
    season: Int,
    episode: Int,
): List<MovieBox.MovieStream> {
    val found = mutableListOf<MovieBox.MovieStream>()
    val seen = mutableSetOf<String>()
    fun add(stream: MovieBox.MovieStream) {
        if (stream.url.isEmpty() || !stream.url.startsWith("http")) return
        if (isDeprecationNoticeUrl(stream.url)) return
        val base = stream.url.substringBefore('?')
        if (!seen.add(base)) return
        found += stream
    }
    // 1. resourceDetectors — files tagged with this season/episode.
    val detectors = details.detectors
    if (detectors != null) {
        for (i in 0 until detectors.length()) {
            val det = detectors.optJSONObject(i) ?: continue
            val resList = det.optJSONArray("resolutionList")
            if (resList != null && resList.length() > 0) {
                val before = found.size
                for (j in 0 until resList.length()) {
                    parseEpisodeDetectorItem(resList.optJSONObject(j), det, season, episode)
                        ?.let { add(it) }
                }
                if (found.size > before) continue
            }
            if (!matchesEpisode(det, null, season, episode)) continue
            val downloadUrl = episodeUrl(det) ?: continue
            if (downloadUrl.isEmpty()) continue
            add(
                MovieBox.MovieStream(
                    url = downloadUrl,
                    resolution = longValue(det, "resolution").toInt(),
                    codec = det.optString("codecName", ""),
                    sizeBytes = longValue(det, "firstSize"),
                    headers = mapOf("User-Agent" to USER_AGENT),
                ),
            )
        }
    }
    // 2. play-info/v2 with se/ep — DASH manifest or a direct file.
    try {
        val playInfo = request(
            "GET",
            "$BASE/subject-api/play-info/v2?subjectId=${urlEncode(details.movie.subjectId)}&se=$season&ep=$episode",
            null,
        )
        val playStreams = playInfo.optJSONArray("streams")
        if (playStreams != null) {
            val displayRes = playInfo.optString("displayResolutions", "")
            for (i in 0 until playStreams.length()) {
                parsePlayInfoStream(playStreams.optJSONObject(i), displayRes)?.let { add(it) }
            }
        }
    } catch (_: IOException) {
        // play-info is a fallback source — detectors may already have files.
    }
    // 3. Resource list with se/ep (+ recovery for placeholder links).
    try {
        val resData = request(
            "GET",
            "$BASE/subject-api/resource?subjectId=${urlEncode(details.movie.subjectId)}&se=$season&ep=$episode&page=1&perPage=20",
            null,
        )
        val items = resData.optJSONArray("list")
        if (items != null) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // The API may ignore the se/ep filter — check client-side.
                if (!matchesEpisode(item, null, season, episode)) continue
                val parsed = parseEpisodeDetectorItem(item, item, season, episode)
                if (parsed != null) {
                    add(parsed)
                    continue
                }
                val link = item.optString("resourceLink", item.optString("url", "")).orEmpty()
                val resourceId = idString(item, "resourceId", "id").orEmpty()
                if (resourceId.isEmpty() || !isDeprecationNoticeUrl(link)) continue
                recoverResource(
                    details.movie.subjectId,
                    resourceId,
                    link,
                    longValue(item, "resolution").toInt(),
                    item.optString("codecName", item.optString("codec", "")),
                    longValue(item, "size"),
                )?.let { add(it) }
            }
        }
    } catch (_: IOException) {
        // Same: best-effort fallback.
    }
    if (found.isEmpty()) throw IOException("Is episode ke liye koi playable link nahi mila.")
    // No runtime filter here: episode lengths vary too much for the
    // movie-style duration check.
    return found.sortedWith(
        compareByDescending<MovieBox.MovieStream> { it.resolution }
            .thenBy { it.isDash } // direct mp4 before DASH at the same resolution
            .thenByDescending { it.sizeBytes },
    )
}

/** A detector item for one episode: same as the movie parse, but the
 * file must match this season/episode instead of being se 0 / ep 0. */
private fun MovieBox.parseEpisodeDetectorItem(
    item: JSONObject?,
    parent: JSONObject,
    season: Int,
    episode: Int,
): MovieBox.MovieStream? {
    if (item == null) return null
    if (!matchesEpisode(item, parent, season, episode)) return null
    val url = episodeUrl(item) ?: return null
    val title = item.optString("title", item.optString("fileName", "")).orEmpty()
    if (isCamTitle(title)) return null
    return MovieBox.MovieStream(
        url = url,
        resolution = longValue(item, "resolution").toInt(),
        codec = item.optString("codecName", item.optString("codec", "")),
        sizeBytes = longValue(item, "size"),
        durationSec = longValue(item, "duration"),
        headers = mapOf("User-Agent" to USER_AGENT),
    )
}

internal fun matchesEpisode(item: JSONObject, parent: JSONObject?, season: Int, episode: Int): Boolean {
    fun number(vararg names: String): Int? {
        for (record in listOfNotNull(item, parent)) {
            for (name in names) {
                if (record.has(name) && !record.isNull(name)) return record.optInt(name, -1)
            }
        }
        return null
    }
    return number("se", "season", "seasonNumber") == season &&
        number("ep", "episode", "episodeNumber") == episode
}

private fun MovieBox.episodeUrl(row: JSONObject): String? =
    listOf("downloadUrl", "resourceLink", "url").asSequence()
        .map { row.optString(it).trim() }
        .firstOrNull { (it.startsWith("https://") || it.startsWith("http://")) && !isDeprecationNoticeUrl(it) }
