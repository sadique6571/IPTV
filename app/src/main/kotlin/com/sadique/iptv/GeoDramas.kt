package com.sadique.iptv

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Geo Dramas (v17): Har Pal Geo dramas from YouTube, no API key needed.
 *
 * The official channel is HAR PAL GEO (UCe9JSDmyqNgA_l2BzGHq1Ug) — NOT
 * @GeoEntertainment (UC_BHj2jZvrPJVr8KYso86YA), which is an old dormant
 * channel whose RSS feed still shows a 2011 video. Drama episodes live in
 * per-drama playlists on HAR PAL GEO, and this client reads them through
 * YouTube's public Innertube browse API (the same WEB client key every
 * browser session uses — no login, no key to manage, the way NewPipe and
 * yt-dlp do it).
 *
 * Flow, verified live from this sandbox 2026-11-18:
 *  1. Playlists tab (browseId = channel, params = playlists tab) →
 *     gridRenderer.items of lockupViewModel: title, contentId (playlist
 *     ID, e.g. Bano = PLRLUcbjccoI4), contentType, thumbnail, "N episodes"
 *     badge. Only LOCKUP_CONTENT_TYPE_SHOW is kept (real drama series);
 *     titles with Shorts/Clips/Promo/Teaser/Best Scene/Best Clips are
 *     excluded. Continuation pages follow the same shape.
 *  2. Playlist contents (browseId = VL<playlistId>) → lockupViewModel
 *     episodes: contentId is the video ID, the full title carries the
 *     episode number AND the air date ("Bano Episode 34 … | 17th November
 *     2026 | HAR PAL GEO"), metadata rows carry views + relative time.
 *     Episodes arrive oldest-first; callers get newest-first.
 *  3. Sorting: a drama's latest-episode date comes from its episode
 *     titles (air date parsed out, relative time as fallback). The list
 *     shows immediately in playlists-tab order (already roughly
 *     recency-ordered) and re-sorts in the background once dates arrive.
 *
 * Responses are cached on disk for 6 hours (LogoLoader-style file cache),
 * so opening the section twice in a day costs one playlists fetch, and a
 * drama whose dates were already refreshed opens instantly.
 *
 * Only HttpURLConnection + org.json — no new dependency. Every method
 * here is blocking: callers must run it off the main thread. All parsing
 * uses opt* accessors and never throws on a missing field — a changed
 * YouTube response shape degrades to an empty list, never a crash.
 */
internal class GeoDramas(context: Context) {

    data class Drama(
        val playlistId: String,
        val title: String,
        val thumbnailUrl: String = "",
        val episodeCount: Int = 0,
        val latestMs: Long = 0L,
        val latestLabel: String = "",
    )

    data class Episode(
        val videoId: String,
        val title: String,
        val episodeNumber: Int = 0,
        val publishedMs: Long = 0L,
        val dateLabel: String = "",
        val views: String = "",
    ) {
        /** Short row title: "Episode 34", or the full title when no number parses. */
        val shortTitle: String
            get() = if (episodeNumber > 0) "Episode $episodeNumber" else title
    }

    /** Har Pal Geo channel avatar, parsed from the playlists response (the
     * header carries it — no extra request). Empty until dramas() runs once;
     * callers fall back to a letter tile when it is empty. */
    var channelAvatarUrl: String = ""
        private set

    private val cacheDir = File(context.cacheDir, "geodramas").apply { mkdirs() }

    // ---- Public API (all blocking) ----

    /** Drama playlists in playlists-tab order (roughly newest-updated
     * first). Cached 6h. Never throws on a parse problem — returns what
     * parsed, possibly empty. */
    fun dramas(): List<Drama> {
        val found = mutableListOf<Drama>()
        val seen = mutableSetOf<String>()
        var response = cachedOrFetch(CACHE_DRAMAS) { playlistsRequest() }
        var pages = 0
        while (response != null && pages < MAX_PLAYLIST_PAGES) {
            pages++
            val items = gridItems(response) ?: continuationItems(response)
            if (items == null) break
            var token: String? = null
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val lockup = item.optJSONObject("lockupViewModel")
                if (lockup != null) {
                    parseDrama(lockup)?.let { drama ->
                        if (seen.add(drama.playlistId)) found += drama
                    }
                } else {
                    token = token ?: continuationToken(item)
                }
            }
            if (pages == 1) {
                // The first page's header carries the channel avatar.
                parseAvatar(response)?.let { channelAvatarUrl = it }
            }
            response = if (token != null && pages < MAX_PLAYLIST_PAGES) {
                runCatching { continuationRequest(token) }.getOrNull()
            } else {
                null
            }
        }
        return found
    }

    /** Episodes of one drama, NEWEST first. Cached 6h per playlist. */
    fun episodes(playlistId: String): List<Episode> {
        val key = CACHE_EPISODES_PREFIX + playlistId
        val found = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var response = cachedOrFetch(key) { playlistRequest(playlistId) }
        var pages = 0
        while (response != null && pages < MAX_EPISODE_PAGES) {
            pages++
            val lockups = playlistLockups(response)
            var token: String? = null
            for (lockup in lockups) {
                parseEpisode(lockup)?.let { episode ->
                    if (seen.add(episode.videoId)) found += episode
                }
            }
            // A trailing continuation item may sit beside the lockups.
            token = findContinuationToken(response)
            response = if (token != null && pages < MAX_EPISODE_PAGES) {
                runCatching { continuationRequest(token) }.getOrNull()
            } else {
                null
            }
        }
        // Innertube returns episodes oldest-first; the UI wants newest-first.
        return found.reversed()
    }

    /** Latest-episode time of a drama (0 when unknown). Reuses the episode
     * cache, so calling this for every drama also warms the detail views. */
    fun latestDate(playlistId: String): Long =
        episodes(playlistId).maxOfOrNull { it.publishedMs } ?: 0L

    /** Drops all cached responses — the next load fetches fresh data. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    // ---- Requests ----

    private fun playlistsRequest(): JSONObject {
        val body = baseBody().apply {
            put("browseId", CHANNEL_ID)
            put("params", PLAYLISTS_PARAMS)
        }
        return postBrowse(body)
    }

    private fun playlistRequest(playlistId: String): JSONObject {
        val body = baseBody().apply {
            put("browseId", "VL$playlistId")
        }
        return postBrowse(body)
    }

    private fun continuationRequest(token: String): JSONObject {
        val body = baseBody().apply {
            put("continuation", token)
        }
        return postBrowse(body)
    }

    private fun baseBody(): JSONObject = JSONObject().apply {
        put("context", JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB")
                put("clientVersion", "2.20240111.09.00")
                put("hl", "en")
                put("gl", "US")
            })
        })
    }

    private fun postBrowse(body: JSONObject): JSONObject {
        val connection = URI.create(BROWSE_URL).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
        )
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) {
                throw IOException("YouTube returned HTTP ${connection.responseCode}.")
            }
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > MAX_RESPONSE_BYTES) {
                        throw IOException("YouTube response too large.")
                    }
                    bytes.write(buffer, 0, count)
                }
            }
            return JSONObject(bytes.toString("UTF-8"))
        } finally {
            connection.disconnect()
        }
    }

    // ---- Disk cache (6h, LogoLoader-style) ----

    private fun cachedOrFetch(key: String, fetch: () -> JSONObject): JSONObject? {
        val file = File(cacheDir, sha256(key) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS) {
            runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()?.let { return it }
            runCatching { file.delete() }
        }
        val fresh = runCatching { fetch() }.getOrNull() ?: return null
        runCatching {
            FileOutputStream(file).use { it.write(fresh.toString().toByteArray(Charsets.UTF_8)) }
        }
        return fresh
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---- Parsing: playlists tab ----

    private fun gridItems(response: JSONObject): JSONArray? {
        // contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer.content
        // holds the gridRenderer; walk down defensively instead of assuming
        // one fixed path (YouTube moves these wrappers around).
        val tabs = response.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs") ?: return findGridItems(response)
        for (i in 0 until tabs.length()) {
            val content = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content") ?: continue
            findGridItems(content)?.let { return it }
        }
        return findGridItems(response)
    }

    private fun findGridItems(node: Any?): JSONArray? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("gridRenderer")?.optJSONArray("items")?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findGridItems(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findGridItems(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun continuationItems(response: JSONObject): JSONArray? {
        val actions = response.optJSONArray("onResponseReceivedActions") ?: return null
        for (i in 0 until actions.length()) {
            val items = actions.optJSONObject(i)
                ?.optJSONObject("appendContinuationItemsAction")
                ?.optJSONArray("continuationItems")
            if (items != null) return items
        }
        return null
    }

    private fun continuationToken(item: JSONObject): String? =
        item.optJSONObject("continuationItemRenderer")
            ?.optJSONObject("continuationEndpoint")
            ?.optJSONObject("continuationCommand")
            ?.optString("token", null)?.takeIf { it.isNotEmpty() }

    private fun parseDrama(lockup: JSONObject): Drama? {
        if (lockup.optString("contentType") != "LOCKUP_CONTENT_TYPE_SHOW") return null
        val playlistId = lockup.optString("contentId", "")
        if (!playlistId.startsWith("PL")) return null
        val title = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel")
            ?.optJSONObject("title")
            ?.optString("content", "").orEmpty().trim()
        if (title.isEmpty() || EXCLUDED_TITLE.containsMatchIn(title)) return null
        val thumbnail = lockup.optJSONObject("contentImage")
            ?.optJSONObject("collectionThumbnailViewModel")
            ?.optJSONObject("primaryThumbnail")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONObject("image")
            ?.optJSONArray("sources")
            ?.let { sources ->
                // Last source is the largest.
                sources.optJSONObject(sources.length() - 1)?.optString("url", "")
            }.orEmpty()
        val badge = EPISODE_BADGE.find(lockup.toString())?.groupValues?.get(1)
            ?.toIntOrNull() ?: 0
        return Drama(playlistId, title, thumbnail, badge)
    }

    private fun parseAvatar(response: JSONObject): String? {
        val avatar = findAvatar(response) ?: return null
        val sources = avatar.optJSONObject("image")?.optJSONArray("sources") ?: return null
        if (sources.length() == 0) return null
        var url = sources.optJSONObject(sources.length() - 1)?.optString("url", "").orEmpty()
        if (url.isEmpty()) return null
        // yt3 avatar URLs take a size parameter (=s68-, =s120- …); ask for
        // 176px so the header icon stays crisp on a TV.
        url = AVATAR_SIZE_REGEX.replace(url, "=s176-")
        return url
    }

    private fun findAvatar(node: Any?): JSONObject? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("avatarViewModel")?.let { return it }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findAvatar(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findAvatar(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ---- Parsing: playlist contents ----

    private fun playlistLockups(response: JSONObject): List<JSONObject> {
        // Preferred path: tabs[0] → sectionListRenderer → itemSectionRenderer
        // → contents[] of {lockupViewModel}. Fall back to a whole-response
        // scan when YouTube moves the wrappers.
        val structured = mutableListOf<JSONObject>()
        val tabs = response.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        if (tabs != null && tabs.length() > 0) {
            val content = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
            val sections = content?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            if (sections != null) {
                for (i in 0 until sections.length()) {
                    val items = sections.optJSONObject(i)
                        ?.optJSONObject("itemSectionRenderer")
                        ?.optJSONArray("contents") ?: continue
                    for (j in 0 until items.length()) {
                        items.optJSONObject(j)?.optJSONObject("lockupViewModel")
                            ?.let { structured += it }
                    }
                }
            }
        }
        if (structured.isNotEmpty()) return structured
        // Continuation pages carry their items under onResponseReceivedActions.
        continuationItems(response)?.let { items ->
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("lockupViewModel")?.let { structured += it }
            }
            if (structured.isNotEmpty()) return structured
        }
        // Last resort: scan the whole response for video lockups.
        val scanned = mutableListOf<JSONObject>()
        collectVideoLockups(response, scanned)
        return scanned
    }

    private fun collectVideoLockups(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("lockupViewModel")?.let { lockup ->
                    if (lockup.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") {
                        out += lockup
                    }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectVideoLockups(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectVideoLockups(node.opt(i), out)
                }
            }
        }
    }

    private fun findContinuationToken(response: JSONObject): String? {
        // Continuation items can sit in the grid/section contents or in a
        // continuationItems array; scan both.
        val tabs = response.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")
        if (tabs != null) {
            for (i in 0 until tabs.length()) {
                findTokenIn(tabs.optJSONObject(i))?.let { return it }
            }
        }
        continuationItems(response)?.let { items ->
            for (i in 0 until items.length()) {
                continuationToken(items.optJSONObject(i) ?: continue)?.let { return it }
            }
        }
        return null
    }

    private fun findTokenIn(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("continuationItemRenderer")) {
                    continuationToken(node)?.let { return it }
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findTokenIn(node.opt(keys.next()))?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findTokenIn(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseEpisode(lockup: JSONObject): Episode? {
        if (lockup.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        // contentId IS the video ID on episode lockups.
        val videoId = lockup.optString("contentId", "")
        if (!videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) return null
        val metadata = lockup.optJSONObject("metadata")
            ?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = metadata.optJSONObject("title")?.optString("content", "").orEmpty().trim()
        if (title.isEmpty()) return null
        val rows = metadata.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        var views = ""
        var relative = ""
        if (rows != null) {
            for (i in 0 until rows.length()) {
                val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
                for (j in 0 until parts.length()) {
                    val text = parts.optJSONObject(j)?.optJSONObject("text")
                        ?.optString("content", "").orEmpty()
                    when {
                        "view" in text.lowercase(Locale.ROOT) -> views = text
                        RELATIVE_TIME.containsMatchIn(text) -> relative = text
                    }
                }
            }
        }
        val dateMatch = TITLE_DATE.find(title)
        val dateLabel = dateMatch?.value.orEmpty()
        val publishedMs = when {
            dateMatch != null -> parseTitleDate(dateMatch)
            relative.isNotEmpty() -> parseRelativeTime(relative)
            else -> 0L
        }
        val number = EPISODE_NUMBER.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return Episode(videoId, title, number, publishedMs, dateLabel, views)
    }

    // ---- Dates ----

    /** "17th November 2026" (or 1st/2nd/3rd) inside an episode title. */
    private fun parseTitleDate(match: MatchResult): Long {
        val day = match.groupValues[1]
        val month = match.groupValues[3]
        val year = match.groupValues[4]
        return try {
            SimpleDateFormat("d MMMM yyyy", Locale.US)
                .parse("$day $month $year")?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** "1 month ago", "4 weeks ago", "2 days ago", "5 hours ago". */
    private fun parseRelativeTime(text: String): Long {
        val match = RELATIVE_TIME.find(text) ?: return 0L
        val amount = match.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "second", "seconds" -> 1_000L
            "minute", "minutes" -> 60_000L
            "hour", "hours" -> 3_600_000L
            "day", "days" -> 86_400_000L
            "week", "weeks" -> 7 * 86_400_000L
            "month", "months" -> 30 * 86_400_000L
            "year", "years" -> 365 * 86_400_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - amount * unitMs
    }

    companion object {
        /** HAR PAL GEO — the live official channel. @GeoEntertainment
         * (UC_BHj2jZvrPJVr8KYso86YA) is dormant; never use it here. */
        private const val CHANNEL_ID = "UCe9JSDmyqNgA_l2BzGHq1Ug"
        private const val PLAYLISTS_PARAMS = "EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA"
        private const val BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        /** Two playlists pages (~62 playlists) cover every running drama
         * plus recent completed ones; older pages are years-old shows. */
        private const val MAX_PLAYLIST_PAGES = 2
        private const val MAX_EPISODE_PAGES = 3
        private const val CACHE_DRAMAS = "dramas"
        private const val CACHE_EPISODES_PREFIX = "episodes:"
        private val EXCLUDED_TITLE = Regex(
            "(?i)shorts|clips|promo|teaser|best scene|best clips|trailer|recap",
        )
        private val EPISODE_BADGE = Regex(""""text":\s*"(\d+) episodes""")
        private val TITLE_DATE = Regex("""(\d{1,2})(st|nd|rd|th)\s+([A-Za-z]+)\s+(\d{4})""")
        private val EPISODE_NUMBER = Regex("""(?i)episode\s+(\d+)""")
        private val RELATIVE_TIME =
            Regex("""(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)
        private val AVATAR_SIZE_REGEX = Regex("""=s\d+-""")
    }
}
