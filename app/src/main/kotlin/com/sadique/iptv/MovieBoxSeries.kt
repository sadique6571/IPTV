package com.sadique.iptv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox series support (v2.0.27): seasons, episodes and per-episode
 * streams. Movies resolve one file per title; a series resolves one file
 * per (season, episode) through the mobile sources — resourceDetectors
 * (se/ep-tagged files), play-info/v2 with se/ep, and the resource list
 * with se/ep — with the same placeholder / CAM filtering as movies,
 * plus title-level play-info and the web player's H5 play endpoint as
 * last-resort fallbacks when the mobile sources return only the notice.
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

internal fun parseSeasons(data: JSONObject): List<MovieBox.Season> {
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
                    val e = epArr.optJSONObject(j)
                    val epNum = if (e == null) epArr.optInt(j, 0) else
                        longKeys(e, "ep", "episode", "episodeNumber", "index").toInt()
                    if (epNum <= 0) continue
                    episodes += MovieBox.EpisodeRef(
                        number = epNum,
                        title = e?.optString("title", e.optString("name", "")).orEmpty(),
                    )
                }
            } else {
                val count = longKeys(s, "episodeCount", "epCount", "totalEpisode", "count", "maxEp").coerceIn(0L, 5000L).toInt()
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
private fun longKeys(obj: JSONObject, vararg keys: String): Long {
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
    var providerSentNotice = false
    val found = mutableListOf<MovieBox.MovieStream>()
    val seen = mutableSetOf<String>()
    fun add(stream: MovieBox.MovieStream) {
        if (stream.url.isEmpty() || !stream.url.startsWith("http")) return
        if (isDeprecationNoticeUrl(stream.url)) return
        val base = stream.url.substringBefore('?')
        if (!seen.add(base)) return
        found += stream
    }
    // Referer for the file requests below (detector/H5). The file CDN
    // refuses series requests without one (HTTP 429): the app used to
    // send User-Agent only, while the same phone's browser — same
    // network — played fine because its <video> request carries the
    // detail page as Referer (verified live 2026-11-23). Movies
    // already send STREAM_REFERER and play; play-info streams get it
    // via the shared parsePlayInfoStream.
    val fileReferer = seriesFileReferer(details.movie.subjectId, details.detailPath)
    // 1. resourceDetectors — files tagged with this season/episode.
    val detectors = details.detectors
    if (detectors != null) {
        for (i in 0 until detectors.length()) {
            val det = detectors.optJSONObject(i) ?: continue
            val resList = det.optJSONArray("resolutionList")
            if (resList != null && resList.length() > 0) {
                val before = found.size
                for (j in 0 until resList.length()) {
                    parseEpisodeDetectorItem(resList.optJSONObject(j), det, season, episode, fileReferer)
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
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to fileReferer),
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
                val row = playStreams.optJSONObject(i) ?: continue
                if (isDeprecationNoticeUrl(row.optString("url"))) providerSentNotice = true
                parsePlayInfoStream(row, displayRes)?.let { add(it) }
            }
        }
    } catch (_: IOException) {
        // play-info is a fallback source — detectors may already have files.
    }
    // 3. Resource list with se/ep (+ recovery for placeholder links).
    // Paginated: the API may ignore the se/ep filter, and a long series
    // has far more than one page of files — the wanted episode's files
    // may sit on page 2+. Stop as soon as the episode is found.
    try {
        for (page in 1..5) {
            if (found.isNotEmpty()) break
            val resData = request(
                "GET",
                "$BASE/subject-api/resource?subjectId=${urlEncode(details.movie.subjectId)}&se=$season&ep=$episode&page=$page&perPage=20",
                null,
            )
            val items = resData.optJSONArray("list") ?: break
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // The API may ignore the se/ep filter — check client-side.
                if (!matchesEpisode(item, null, season, episode)) continue
                val resourceId = idString(item, "resourceId", "id").orEmpty()
                if (resourceId.isNotBlank() && detectors != null) {
                    for (index in 0 until detectors.length()) {
                        val detector = detectors.optJSONObject(index) ?: continue
                        if (!sameEpisodeFile(detector, item, season, episode)) continue
                        parseEpisodeDetectorItem(detector, item, season, episode, fileReferer)?.let { add(it) }
                    }
                }
                val parsed = parseEpisodeDetectorItem(item, item, season, episode, fileReferer)
                if (parsed != null) {
                    add(parsed)
                    continue
                }
                val link = item.optString("resourceLink", item.optString("url", "")).orEmpty()
                if (!isDeprecationNoticeUrl(link)) continue
                providerSentNotice = true
                if (resourceId.isEmpty()) continue
                // Exact season/episode first; the position endpoint
                // often returns the recovered file without se/ep tags
                // (0/0), which the exact check rejects — the same
                // reason movies (se/ep 0) recover and episodes don't.
                // resourceId already pins the exact file, so an
                // untagged recovery is safe to accept.
                (recoverResource(
                    details.movie.subjectId,
                    resourceId,
                    link,
                    longValue(item, "resolution").toInt(),
                    item.optString("codecName", item.optString("codec", "")),
                    longValue(item, "size"),
                    season,
                    episode,
                ) ?: recoverResource(
                    details.movie.subjectId,
                    resourceId,
                    link,
                    longValue(item, "resolution").toInt(),
                    item.optString("codecName", item.optString("codec", "")),
                    longValue(item, "size"),
                    0,
                    0,
                ))?.let { add(it) }
            }
        }
    } catch (_: IOException) {
        // Same: best-effort fallback.
    }
    // 4. Title-level play-info as a last resort, S1 E1 only: some
    // single-episode / movie-like series attach their only file to
    // the title instead of the episode, so the se/ep play-info
    // returns just the notice clip. Only runs when every episode
    // source failed, so it can only turn the error below into a
    // playable stream. Restricted to S1 E1 because the title-level
    // endpoint most plausibly resolves to the first episode.
    if (found.isEmpty() && season == 1 && episode == 1) {
        try {
            val playInfo = request(
                "GET",
                "$BASE/subject-api/play-info/v2?subjectId=${urlEncode(details.movie.subjectId)}",
                null,
            )
            val playStreams = playInfo.optJSONArray("streams")
            if (playStreams != null) {
                val displayRes = playInfo.optString("displayResolutions", "")
                for (i in 0 until playStreams.length()) {
                    val row = playStreams.optJSONObject(i) ?: continue
                    if (isDeprecationNoticeUrl(row.optString("url"))) providerSentNotice = true
                    parsePlayInfoStream(row, displayRes)?.let { add(it) }
                }
            }
        } catch (_: IOException) {
            // Still nothing — fall through to the error below.
        }
    }
    // 5. H5 play endpoint (the web player's resolver) as a last resort:
    // the mobile play-info/resource endpoints return only the notice
    // clip for some titles, while the web player's
    // /wefeed-h5api-bff/subject/play returns the real files (verified
    // live for The Body [Hindi] S1 E1+E2: 480p + 1080p MP4s). It needs
    // the player-page URL as Referer, with the title's exact detailPath
    // slug — a wrong or missing slug returns no streams. Only runs when
    // every mobile source failed, so it can only turn the error below
    // into a playable stream. Depends on the vidiomoviebox.xyz web
    // frontend proxying the H5 BFF; if that site goes away, this step
    // just returns nothing and the error below is thrown as before.
    if (found.isEmpty() && details.detailPath.isNotBlank()) {
        for (stream in h5PlayStreams(details.movie.subjectId, season, episode, details.detailPath)) {
            add(stream)
        }
    }
    if (found.isEmpty()) {
        if (providerSentNotice) throw IOException("Provider ne S${season} E${episode} ki video ki jagah notice bheja.")
        throw IOException("Is episode ke liye koi playable link nahi mila.")
    }
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
    referer: String,
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
        headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer),
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

internal fun sameEpisodeFile(detector: JSONObject, row: JSONObject, season: Int, episode: Int): Boolean {
    val id = row.optString("resourceId")
    return id.isNotBlank() && id != "0" && id != "null" && id == detector.optString("resourceId") &&
        matchesEpisode(row, null, season, episode) && matchesEpisode(detector, row, season, episode)
}

private fun MovieBox.episodeUrl(row: JSONObject): String? =
    listOf("downloadUrl", "resourceLink", "url").asSequence()
        .map { row.optString(it).trim() }
        .firstOrNull { (it.startsWith("https://") || it.startsWith("http://")) && !isDeprecationNoticeUrl(it) }

/**
 * Referer for series file requests: the detail page a phone browser
 * sends when its <video> element fetches the file
 * (https://themoviebox.xyz/movies/<detailPath>?id=<subjectId>&...).
 * The file CDN (bcdnxw) refuses series requests without a Referer
 * (HTTP 429 in every sandbox probe, while the same phone's browser
 * on the same network plays fine). The H5 play-API call uses the
 * player-page URL as Referer instead — that one already works, so
 * this is only for the file requests themselves.
 */
private fun seriesFileReferer(subjectId: String, detailPath: String): String =
    "https://themoviebox.xyz/movies/$detailPath?id=$subjectId&type=/movie/detail&detailSe=&detailEp=&lang=en"

/**
 * Web-player (H5) play endpoint fallback. Unlike the mobile
 * play-info/v2, this resolves the title's real source (e.g. the
 * fzmovies upload behind the detector) to direct signed MP4s. No
 * HMAC signing is needed — the web frontend proxies the H5 BFF —
 * but the player-page URL must be sent as Referer with the exact
 * detailPath slug, otherwise the endpoint returns no streams.
 * Returns an empty list on any failure; never throws.
 */
private fun MovieBox.h5PlayStreams(
    subjectId: String,
    season: Int,
    episode: Int,
    detailPath: String,
): List<MovieBox.MovieStream> {
    val url = "https://vidiomoviebox.xyz/wefeed-h5api-bff/subject/play" +
        "?subjectId=${urlEncode(subjectId)}&se=$season&ep=$episode"
    val referer = "https://vidiomoviebox.xyz/player/$detailPath"
    val text = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Referer", referer)
            if (connection.responseCode !in 200..299) return emptyList()
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    } catch (_: IOException) {
        return emptyList()
    }
    val data = try {
        JSONObject(text).optJSONObject("data")
    } catch (_: Exception) {
        null
    } ?: return emptyList()
    val streams = data.optJSONArray("streams") ?: return emptyList()
    val out = mutableListOf<MovieBox.MovieStream>()
    for (i in 0 until streams.length()) {
        val row = streams.optJSONObject(i) ?: continue
        val streamUrl = row.optString("url", "").orEmpty()
        if (streamUrl.isEmpty() || !streamUrl.startsWith("http")) continue
        if (isDeprecationNoticeUrl(streamUrl)) continue
        val resolution = row.optString("resolutions", "")
            .split(",").firstOrNull()?.trim()?.toIntOrNull() ?: 0
        out += MovieBox.MovieStream(
            url = streamUrl,
            resolution = resolution,
            codec = row.optString("codecName", ""),
            sizeBytes = longValue(row, "size"),
            durationSec = longValue(row, "duration"),
            // The file request needs the detail-page Referer, not just
            // User-Agent: without it the CDN answers 429 (the `referer`
            // above is only for the play-API call). Verified live
            // 2026-11-23: the phone browser plays with this Referer.
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to seriesFileReferer(subjectId, detailPath),
            ),
        )
    }
    return out
}
