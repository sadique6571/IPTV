package com.sadique.iptv

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * MovieBox movie resolver (v1 — movies only, no series).
 *
 * MovieBox is an aggregator: it hosts nothing, it resolves a playable file
 * per title at play time. Its links are signed and expire within hours, so
 * they can never sit in a static M3U file — this client resolves them live,
 * the way the MovieBox app itself does, using the same signed API the
 * open-source MovieBox-TUI client (mesamirh/MovieBox-Tui, v0.1.29)
 * documents. Flow verified live from this sandbox 2026-11-18 (visitor
 * login, search, play-info, details/resourceDetectors).
 *
 * How a movie becomes playable:
 *  1. Visitor login (no account) → JWT, valid 7 days, kept in prefs.
 *  2. Search → subjects (movies with hasResource only; series, adult and
 *     CAM titles filtered out).
 *  3. Details → dubs (Hindi is usually a separate subject), subtitles,
 *     resourceDetectors (the real signed mp4 files, one per resolution).
 *  4. play-info/v2 → DASH manifest (decoded from the signCookie policy) or
 *     a direct file; resource list + resource-position/v2 recovers files
 *     whose listing link is only the placeholder clip.
 *  5. The old resource/play-info endpoints often return a 22-second
 *     "upgrade" placeholder clip (macdn.aoneroom.com/other/…mp4) — those
 *     URLs are filtered out everywhere (isDeprecationNoticeUrl).
 *
 * All requests are signed (HMAC-MD5 x-tr-signature, x-client-token,
 * x-client-build) and rotate across 7 hosts with backoff on 429. All
 * signing/endpoint code lives in this one file, so an API change is a
 * one-file fix.
 *
 * Only HttpURLConnection + org.json + javax.crypto — no new dependency.
 * Every method here is blocking: callers must run it off the main thread.
 */
internal class MovieBox(context: Context) {

    data class Movie(
        val subjectId: String = "",
        val title: String = "",
        val year: String = "",
        val posterUrl: String = "",
        val isHindi: Boolean = false,
    )

    data class Dub(val subjectId: String, val language: String, val label: String)

    data class Subtitle(val name: String, val url: String)

    data class Details(
        val movie: Movie,
        val description: String = "",
        val rating: String = "",
        val director: String = "",
        val stars: String = "",
        val durationSec: Long = 0,
        val genres: List<String> = emptyList(),
        val dubs: List<Dub> = emptyList(),
        val subtitles: List<Subtitle> = emptyList(),
        internal val detectors: JSONArray? = null,
    )

    data class MovieStream(
        val url: String,
        val resolution: Int = 0,
        val codec: String = "",
        val sizeBytes: Long = 0,
        val durationSec: Long = 0,
        val headers: Map<String, String> = emptyMap(),
        val isDash: Boolean = false,
    ) {
        val label: String get() = if (resolution > 0) "${resolution}p" else "Auto"
    }

    /** A title the app refuses to show (adult, CAM, or series in v1). */
    class BlockedException(message: String) : IOException(message)

    private class AuthException(message: String) : IOException(message)
    private class ApiException(message: String) : IOException(message)
    private class HostsExhaustedException(message: String) : IOException(message)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("moviebox", Context.MODE_PRIVATE)
    private val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: randomHex(32).also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    private val gaid: String = prefs.getString(KEY_GAID, null)
        ?: randomUuid().also { prefs.edit().putString(KEY_GAID, it).apply() }
    private val spoofedIp: String = randomSpoofedIp()
    private var activeHostIdx = 0
    private var cachedToken: String? = null

    // ---- Public API (all blocking) ----

    fun search(query: String, page: Int = 1): List<Movie> {
        val body = JSONObject().apply {
            put("keyword", query)
            put("page", page)
            put("perPage", 15)
            put("subjectType", 0)
        }.toString()
        val data = request("POST", "$BASE/subject-api/search/v2", body)
        val results = data.optJSONArray("results") ?: return emptyList()
        val movies = mutableListOf<Movie>()
        for (i in 0 until results.length()) {
            val subjects = results.optJSONObject(i)?.optJSONArray("subjects") ?: continue
            for (j in 0 until subjects.length()) {
                parseSearchMovie(subjects.optJSONObject(j))?.let { movies += it }
            }
        }
        return movies
    }

    fun details(subjectId: String): Details {
        val data = request("GET", "$BASE/subject-api/get?subjectId=${urlEncode(subjectId)}", null)
        val subject = data.optJSONObject("subject") ?: data
        val stype = subject.optLong("subjectType", subject.optLong("stype", 1))
        if (stype == 2L) throw BlockedException("Series abhi is version mein nahi hain.")
        val title = subject.optString("title", "Unknown")
        val genres = stringList(subject, "genre", "genres")
        if (genres.any { isAdultGenre(it) }) {
            throw BlockedException("Ye title dikhaya nahi ja sakta.")
        }
        if (isAdultTitle(title) || isCamTitle(title)) {
            throw BlockedException("Ye title dikhaya nahi ja sakta.")
        }
        val dubs = mutableListOf<Dub>()
        val dubsArr = subject.optJSONArray("dubs")
        if (dubsArr != null) {
            for (i in 0 until dubsArr.length()) {
                val d = dubsArr.optJSONObject(i) ?: continue
                val dubId = idString(d, "subjectId", "id").orEmpty()
                if (dubId.isEmpty()) continue
                val language = d.optString("lanName", d.optString("language", d.optString("lang", "Unknown")))
                val label = d.optString("title", d.optString("name", language))
                dubs += Dub(dubId, language, label)
            }
        }
        val detectors = data.optJSONArray("resourceDetectors")
            ?: subject.optJSONArray("resourceDetectors")
        val subtitles = parseSubtitles(detectors)
        val ratingRaw = subject.opt("imdbRatingValue") ?: subject.opt("rating")
        val rating = when (ratingRaw) {
            is Number -> String.format(Locale.US, "%.1f", ratingRaw.toDouble())
            is String -> ratingRaw
            else -> ""
        }
        val poster = subject.optJSONObject("cover")?.optString("url", "")
            ?: subject.optString("coverUrl", subject.optString("poster", subject.optString("pic", "")))
        return Details(
            movie = Movie(
                subjectId = idString(subject, "subjectId", "id").orEmpty(),
                title = title,
                year = extractYear(subject.optString("releaseDate", subject.optString("year", ""))),
                posterUrl = poster.orEmpty(),
                isHindi = title.contains("hindi", ignoreCase = true),
            ),
            description = subject.optString("description", subject.optString("intro", "")),
            rating = rating,
            director = subject.optString("director", ""),
            stars = subject.optString("stars", ""),
            durationSec = longValue(subject, "duration"),
            genres = genres,
            dubs = dubs,
            subtitles = subtitles,
            detectors = detectors,
        )
    }

    /**
     * Resolves playable streams for a movie, best first: resourceDetectors
     * (real signed mp4s) → play-info/v2 (DASH or direct) → resource list
     * with placeholder recovery. Entries whose length wildly mismatches the
     * known runtime are dropped when better options exist.
     */
    fun streams(details: Details): List<MovieStream> {
        val found = mutableListOf<MovieStream>()
        val seen = mutableSetOf<String>()
        fun add(stream: MovieStream) {
            if (stream.url.isEmpty() || !stream.url.startsWith("http")) return
            if (isDeprecationNoticeUrl(stream.url)) return
            val base = stream.url.substringBefore('?')
            if (!seen.add(base)) return
            found += stream
        }
        // 1. resourceDetectors — the real signed mp4 files (primary source).
        val detectors = details.detectors
        if (detectors != null) {
            for (i in 0 until detectors.length()) {
                val det = detectors.optJSONObject(i) ?: continue
                val resList = det.optJSONArray("resolutionList")
                if (resList != null && resList.length() > 0) {
                    for (j in 0 until resList.length()) {
                        parseDetectorItem(resList.optJSONObject(j))?.let { add(it) }
                    }
                    continue
                }
                // No resolution list — a single downloadUrl entry.
                val downloadUrl = det.optString("downloadUrl", "").orEmpty()
                if (downloadUrl.isEmpty()) continue
                val source = det.optString("resourceLink", "").orEmpty()
                add(
                    MovieStream(
                        url = downloadUrl,
                        resolution = resolutionFromSourceLink(source),
                        codec = det.optString("codecName", ""),
                        sizeBytes = longValue(det, "firstSize"),
                        headers = mapOf("User-Agent" to USER_AGENT),
                    ),
                )
            }
        }
        // 2. play-info/v2 — DASH manifest (via signCookie) or a direct file.
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
                    parsePlayInfoStream(playStreams.optJSONObject(i), displayRes)?.let { add(it) }
                }
            }
        } catch (_: IOException) {
            // play-info is a fallback source — detectors may already have files.
        }
        // 3. Resource list (+ recovery for placeholder links).
        try {
            val resData = request(
                "GET",
                "$BASE/subject-api/resource?subjectId=${urlEncode(details.movie.subjectId)}&page=1&perPage=20",
                null,
            )
            val items = resData.optJSONArray("list")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (longValue(item, "se") != 0L || longValue(item, "ep") != 0L) continue
                    val parsed = parseResourceItem(item)
                    if (parsed != null) {
                        add(parsed)
                        continue
                    }
                    // Placeholder link — try to recover the real file.
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
        if (found.isEmpty()) throw IOException("Is movie ke liye koi playable link nahi mila.")
        val runtime = details.durationSec
        val matched = if (runtime > 0) {
            found.filter { it.durationSec <= 0 || (it.durationSec * 2 >= runtime && it.durationSec * 2 <= runtime * 3) }
        } else {
            found
        }
        val usable = if (matched.isNotEmpty()) matched else found
        return usable.sortedWith(
            compareByDescending<MovieStream> { it.resolution }
                .thenBy { it.isDash } // direct mp4 before DASH at the same resolution
                .thenByDescending { it.sizeBytes },
        )
    }

    // ---- Parsing ----

    private fun parseSearchMovie(s: JSONObject?): Movie? {
        if (s == null) return null
        val id = idString(s, "subjectId", "id").orEmpty()
        if (id.isEmpty()) return null
        // v1 is movies only — series (subjectType 2) are not shown.
        val stype = s.optLong("subjectType", s.optLong("stype", 1))
        if (stype != 1L) return null
        if (!s.optBoolean("hasResource", false)) return null
        val title = s.optString("title", s.optString("name", "")).orEmpty()
        if (title.isEmpty()) return null
        if (isCamTitle(title) || isAdultTitle(title)) return null
        val genres = stringList(s, "genre", "genres")
        if (genres.any { isAdultGenre(it) }) return null
        val year = extractYear(
            s.optString("releaseDate", s.optString("year", s.optString("releaseInfo", ""))),
        )
        val poster = s.optJSONObject("cover")?.optString("url", "")
            ?: s.optString("coverUrl", s.optString("poster", s.optString("pic", "")))
        return Movie(
            subjectId = id,
            title = title,
            year = year,
            posterUrl = poster.orEmpty(),
            isHindi = title.contains("hindi", ignoreCase = true),
        )
    }

    private fun parseDetectorItem(item: JSONObject?): MovieStream? {
        if (item == null) return null
        // Movies are se 0 / ep 0; skip anything else.
        if (longValue(item, "se") != 0L || longValue(item, "ep") != 0L) return null
        val url = item.optString("resourceLink", item.optString("url", "")).orEmpty()
        if (url.isEmpty() || isDeprecationNoticeUrl(url)) return null
        val title = item.optString("title", item.optString("fileName", "")).orEmpty()
        if (isCamTitle(title)) return null
        return MovieStream(
            url = url,
            resolution = longValue(item, "resolution").toInt(),
            codec = item.optString("codecName", item.optString("codec", "")),
            sizeBytes = longValue(item, "size"),
            durationSec = longValue(item, "duration"),
            headers = mapOf("User-Agent" to USER_AGENT),
        )
    }

    private fun parseResourceItem(item: JSONObject): MovieStream? {
        val url = item.optString("resourceLink", item.optString("url", "")).orEmpty()
        if (url.isEmpty() || isDeprecationNoticeUrl(url)) return null
        val title = item.optString("fileName", item.optString("title", "")).orEmpty()
        if (isCamTitle(title)) return null
        return MovieStream(
            url = url,
            resolution = longValue(item, "resolution").toInt(),
            codec = item.optString("codecName", item.optString("codec", "")),
            sizeBytes = longValue(item, "size"),
            durationSec = longValue(item, "duration"),
            headers = mapOf("User-Agent" to USER_AGENT),
        )
    }

    private fun parsePlayInfoStream(stream: JSONObject?, displayResolutions: String): MovieStream? {
        if (stream == null) return null
        val signCookie = stream.optString("signCookie", "").orEmpty()
        val streamUrl = stream.optString("url", "").orEmpty()
        val manifest = resolveDashManifest(signCookie)
        val (url, isDash) = when {
            manifest != null -> manifest to true
            !isDeprecationNoticeUrl(streamUrl) && streamUrl.startsWith("http") -> streamUrl to false
            else -> return null
        }
        val resolutions = stream.optString("resolutions", displayResolutions).orEmpty()
            .split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
        val maxRes = resolutions.maxOrNull() ?: 1080
        val headers = mutableMapOf("Referer" to STREAM_REFERER, "User-Agent" to USER_AGENT)
        if (signCookie.isNotEmpty()) {
            val cleanCookie = signCookie.trimEnd(';').split(';')
                .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
            headers["Cookie"] = cleanCookie
        }
        return MovieStream(
            url = url,
            resolution = maxRes,
            codec = stream.optString("codecName", stream.optString("codec", "")),
            sizeBytes = longValue(stream, "size"),
            durationSec = longValue(stream, "duration"),
            headers = headers,
            isDash = isDash,
        )
    }

    private fun recoverResource(
        subjectId: String,
        resourceId: String,
        failUrl: String,
        origResolution: Int,
        origCodec: String,
        origSize: Long,
    ): MovieStream? {
        val query = listOf(
            "subjectId" to subjectId,
            "resourceId" to resourceId,
            "failUrl" to failUrl,
            "failCode" to "404",
            "resourceNum" to "0",
            "isVip" to "true",
        ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val data = try {
            request("GET", "$BASE/subject-api/resource-position/v2?$query", null)
        } catch (_: IOException) {
            return null
        }
        val items = data.optJSONArray("list") ?: return null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (idString(item, "resourceId", "id").orEmpty() != resourceId) continue
            if (longValue(item, "se") != 0L || longValue(item, "ep") != 0L) continue
            val url = item.optString("resourceLink", item.optString("url", "")).orEmpty()
            if (url.isEmpty() || isDeprecationNoticeUrl(url)) continue
            if (!url.startsWith("http")) continue
            val res = longValue(item, "resolution").toInt()
            return MovieStream(
                url = url,
                // The recovered item may not repeat the metadata — keep the
                // original release's resolution/codec/size as fallback.
                resolution = if (res > 0) res else origResolution,
                codec = item.optString("codecName", item.optString("codec", origCodec)),
                sizeBytes = longValue(item, "size").takeIf { it > 0 } ?: origSize,
                durationSec = longValue(item, "duration"),
                headers = mapOf("User-Agent" to USER_AGENT),
            )
        }
        return null
    }

    private fun parseSubtitles(detectors: JSONArray?): List<Subtitle> {
        if (detectors == null) return emptyList()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<Subtitle>()
        for (i in 0 until detectors.length()) {
            val captions = detectors.optJSONObject(i)?.optJSONArray("extCaptions") ?: continue
            for (j in 0 until captions.length()) {
                val cap = captions.optJSONObject(j) ?: continue
                val url = cap.optString("url", "").orEmpty()
                if (url.isEmpty() || "aa348f2541d13ffe" in url) continue
                val size = longValue(cap, "size")
                if (size in 1..50) continue
                val name = cap.optString("lanName", cap.optString("lan", "Unknown"))
                if (name.equals("in", ignoreCase = true) && size <= 100) continue
                if (!seen.add(url)) continue
                out += Subtitle(name, url)
            }
        }
        return out
    }

    // ---- DASH manifest from a signed cookie ----

    private fun resolveDashManifest(signCookie: String): String? {
        for (part in signCookie.split(";")) {
            val trimmed = part.trim()
            val urlPrefixIdx = trimmed.indexOf("urlprefix=")
            if (urlPrefixIdx >= 0) {
                val prefixPart = trimmed.substring(urlPrefixIdx + "urlprefix=".length)
                val b64Token = prefixPart.substringBefore(':').trim()
                val normalized = b64Token.map { when (it) { '-' -> '+'; '_' -> '/'; else -> it } }
                    .joinToString("")
                val decoded = decodeBase64Padded(normalized) ?: continue
                toDashMpd(String(decoded, Charsets.UTF_8))?.let { return it }
            }
            if (trimmed.startsWith("CloudFront-Policy=")) {
                val policyRaw = trimmed.substring("CloudFront-Policy=".length).trim()
                val normalized = policyRaw.map {
                    when (it) { '-' -> '+'; '_' -> '='; '~' -> '/'; else -> it }
                }.joinToString("")
                val decoded = decodeBase64Padded(normalized) ?: continue
                val resource = try {
                    JSONObject(String(decoded, Charsets.UTF_8))
                        .optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource", "")
                } catch (_: Exception) {
                    null
                }.orEmpty()
                if (resource.isNotEmpty()) toDashMpd(resource)?.let { return it }
            }
        }
        return null
    }

    private fun toDashMpd(urlStr: String): String? {
        val base = urlStr.trimEnd('*').trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.startsWith("http://") && !base.startsWith("https://")) return null
        if ("/dash/" !in base) return null
        return "$base/index.mpd"
    }

    private fun decodeBase64Padded(s: String): ByteArray? {
        return try {
            val padded = s + "=".repeat((4 - s.length % 4) % 4)
            Base64.decode(padded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ---- Filters ----

    /**
     * The 22-second "upgrade" placeholder clip the old endpoints return
     * instead of the real file. Three known file hashes, the notice name,
     * and the whole macdn /other/ directory are placeholders.
     */
    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.US)
        return "1c7de0bd3393702d9191801f15f88f8d" in lower ||
            "9a0461bc39da389663bf3dbb17091d3f" in lower ||
            "b164fbfb4347792950bdfbfb563d39d9" in lower ||
            "/notice.mp4" in lower ||
            ("macdn.aoneroom.com" in lower && "/other/" in lower)
    }

    private fun isCamTitle(title: String): Boolean = CAM_TITLE_REGEX.containsMatchIn(title)

    private fun isAdultTitle(title: String): Boolean = ADULT_TITLE_REGEX.containsMatchIn(title)

    private fun isAdultGenre(genre: String): Boolean {
        val g = genre.lowercase(Locale.US).trim()
        return g in ADULT_GENRES || "adult" in g || "erotic" in g || "porn" in g
    }

    // ---- Session (visitor JWT, 7 days) ----

    private fun loadSession(): Boolean {
        val token = prefs.getString(KEY_TOKEN, null).orEmpty()
        if (token.isBlank()) return false
        val exp = prefs.getLong(KEY_TOKEN_EXP, 0)
        val created = prefs.getLong(KEY_TOKEN_CREATED, 0)
        val now = System.currentTimeMillis() / 1000
        val valid = if (exp > 0) now + 60 < exp else now < created + 7 * 24 * 3600
        if (!valid) return false
        cachedToken = token
        return true
    }

    private fun saveSession(token: String) {
        val exp = parseJwtExp(token)
        val now = System.currentTimeMillis() / 1000
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_TOKEN_EXP, exp)
            .putLong(KEY_TOKEN_CREATED, now)
            .apply()
        cachedToken = token
    }

    private fun clearSession() {
        cachedToken = null
        prefs.edit()
            .remove(KEY_TOKEN).remove(KEY_TOKEN_EXP).remove(KEY_TOKEN_CREATED)
            .apply()
    }

    private fun ensureToken(): String {
        if (cachedToken.isNullOrBlank() && !loadSession()) {
            val data = requestHosts("POST", "$BASE/user-api/visitor-login", "{}", null)
            val token = data.optString("token").orEmpty()
            if (token.isBlank()) throw IOException("MovieBox login failed: no token.")
            saveSession(token)
        }
        return cachedToken!!
    }

    private fun parseJwtExp(token: String): Long {
        return try {
            val parts = token.split(".")
            if (parts.size < 2) return 0
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE), Charsets.UTF_8)
            JSONObject(payload).optLong("exp", 0)
        } catch (_: Exception) {
            0
        }
    }

    // ---- Signed requests with host rotation ----

    private fun request(method: String, path: String, body: String?): JSONObject {
        val token = ensureToken()
        try {
            return requestHosts(method, path, body, token)
        } catch (e: AuthException) {
            // Bad/expired token (HTTP 401 or API code 441) — one fresh login.
            clearSession()
            return requestHosts(method, path, body, ensureToken())
        } catch (e: HostsExhaustedException) {
            // A dead token can look like dead hosts (every host 403s) — one
            // fresh login before giving up.
            clearSession()
            return requestHosts(method, path, body, ensureToken())
        }
    }

    private fun requestHosts(
        method: String,
        path: String,
        body: String?,
        token: String?,
    ): JSONObject {
        val startIdx = activeHostIdx
        var backoffMs = 50L
        var lastError: String? = null
        for (i in HOSTS.indices) {
            if (i > 0) {
                Thread.sleep(backoffMs)
                backoffMs = 50L
            }
            val idx = (startIdx + i) % HOSTS.size
            val url = HOSTS[idx] + path
            try {
                val (status, headers, text) = httpCall(method, url, body, token)
                absorbXUser(headers)
                if (status in RETRY_STATUSES) {
                    activeHostIdx = (idx + 1) % HOSTS.size
                    if (status == 429) {
                        backoffMs = headers["retry-after"]?.toLongOrNull()
                            ?.let { (it * 1000).coerceAtMost(3000) } ?: 400L
                    }
                    lastError = "HTTP $status"
                    continue
                }
                if (status == 401) throw AuthException("HTTP 401")
                if (status !in 200..299) throw ApiException("MovieBox HTTP $status")
                activeHostIdx = idx
                val json = JSONObject(text)
                val code = json.optInt("code", 0)
                if (code == 441) throw AuthException("API code 441")
                if (code != 0) throw ApiException("MovieBox error $code: ${json.optString("message")}")
                return json.optJSONObject("data") ?: json
            } catch (e: AuthException) {
                throw e
            } catch (e: ApiException) {
                throw e
            } catch (e: IOException) {
                lastError = e.message
                activeHostIdx = (idx + 1) % HOSTS.size
                continue
            } catch (e: Exception) {
                // Bad JSON from this host — try the next one.
                lastError = e.message
                activeHostIdx = (idx + 1) % HOSTS.size
                continue
            }
        }
        throw HostsExhaustedException("MovieBox: all hosts failed ($lastError)")
    }

    private fun httpCall(
        method: String,
        url: String,
        body: String?,
        token: String?,
    ): Triple<Int, Map<String, String>, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = true
        for ((key, value) in signedHeaders(method, url, body, token)) {
            connection.setRequestProperty(key, value)
        }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val headers = connection.headerFields.entries
            .filter { it.key != null }
            .associate { it.key.lowercase(Locale.US) to (it.value.firstOrNull().orEmpty()) }
        val text = try {
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            stream?.use { readCapped(it, MAX_RESPONSE_BYTES) }.orEmpty()
        } catch (_: IOException) {
            ""
        } finally {
            connection.disconnect()
        }
        return Triple(status, headers, text)
    }

    private fun absorbXUser(headers: Map<String, String>) {
        val raw = headers["x-user"].orEmpty()
        if (raw.isEmpty()) return
        val token = try {
            JSONObject(raw).optString("token", "")
        } catch (_: Exception) {
            ""
        }
        if (token.isNotEmpty() && token != cachedToken) saveSession(token)
    }

    private fun readCapped(stream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > maxBytes) {
                out.write(buffer, 0, (maxBytes - out.size()).coerceAtLeast(0))
                break
            }
            out.write(buffer, 0, count)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ---- Request signing (single place — an API change is a one-file fix) ----

    private fun signedHeaders(
        method: String,
        url: String,
        body: String?,
        token: String?,
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val clientToken = "$ts,${md5Hex(ts.toString().reversed().toByteArray(Charsets.UTF_8))}"
        val buildTag = buildTag(ts)
        val signature = sign(method, url, body, ts)
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Connection" to "keep-alive",
            "x-client-token" to clientToken,
            "x-tr-signature" to signature,
            "x-client-build" to buildTag,
            "x-client-info" to clientInfoJson(buildTag),
            "x-client-status" to "0",
            "x-forwarded-for" to spoofedIp,
        )
        if (!token.isNullOrBlank()) headers["Authorization"] = "Bearer $token"
        return headers
    }

    private fun clientInfoJson(buildTag: String): String =
        "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"3.0.03.0529.03\"," +
            "\"version_code\":50020044,\"os\":\"android\",\"os_version\":\"11\",\"install_ch\":\"ps\"," +
            "\"device_id\":\"$deviceId\",\"install_store\":\"ps\",\"gaid\":\"$gaid\",\"brand\":\"Redmi\"," +
            "\"model\":\"2201117TY\",\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\"," +
            "\"timezone\":\"Asia/Kolkata\",\"sp_code\":\"40401\",\"X-Play-Mode\":\"2\"," +
            "\"X-Client-Build\":\"$buildTag\"}"

    private fun sign(method: String, url: String, body: String?, ts: Long): String {
        val canonical = canonicalString(method, url, body, ts)
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        val sig = Base64.encodeToString(
            mac.doFinal(canonical.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$ts|2|$sig"
    }

    private fun canonicalString(method: String, url: String, body: String?, ts: Long): String {
        val (path, query) = try {
            val uri = URI(url)
            uri.rawPath.orEmpty() to uri.rawQuery.orEmpty()
        } catch (_: Exception) {
            url to ""
        }
        val sorted = sortedQuery(query)
        val canonicalUrl = if (sorted.isEmpty()) path else "$path?$sorted"
        val (bodyHash, bodyLength) = if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val truncated = if (bytes.size > 102400) bytes.copyOf(102400) else bytes
            md5Hex(truncated) to bytes.size.toString()
        } else {
            "" to ""
        }
        return listOf(
            method.uppercase(Locale.US),
            "application/json",
            "application/json",
            bodyLength,
            ts.toString(),
            bodyHash,
            canonicalUrl,
        ).joinToString("\n")
    }

    private fun sortedQuery(rawQuery: String): String {
        if (rawQuery.isEmpty()) return ""
        return rawQuery.split("&").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq < 0) return@mapNotNull null
            urlDecode(part.substring(0, eq)) to urlDecode(part.substring(eq + 1))
        }.sortedBy { it.first }.joinToString("&") { (k, v) -> "$k=$v" }
    }

    private fun buildTag(ts: Long): String {
        val stamp = "${ts / 1000}${String.format(Locale.US, "%03d", ts % 1000)}000${Random.nextInt(1000)}"
        val hash = md5Hex((CERT_SHA256 + "oneroom" + stamp).toByteArray(Charsets.UTF_8))
        return "$stamp.$hash"
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { String.format(Locale.US, "%02x", it.toInt() and 0xff) }
    }

    // ---- Small helpers ----

    private fun idString(obj: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = when (val raw = obj.opt(key)) {
                is String -> raw
                is Number -> raw.toString()
                else -> continue
            }
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun longValue(obj: JSONObject, key: String): Long {
        if (!obj.has(key) || obj.isNull(key)) return 0
        return when (val raw = obj.opt(key)) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0
            else -> 0
        }
    }

    private fun stringList(obj: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val arr = obj.optJSONArray(key)
            if (arr != null) {
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i, "").orEmpty()
                    if (value.isNotEmpty()) out += value
                }
                return out
            }
            val str = obj.optString(key, "").orEmpty()
            if (str.isNotEmpty()) return str.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        return emptyList()
    }

    private fun extractYear(value: String): String =
        Regex("(19|20)\\d\\d").find(value)?.value.orEmpty()

    private fun resolutionFromSourceLink(link: String): Int {
        val upper = link.uppercase(Locale.US)
        var idx = upper.indexOf('P')
        while (idx >= 0) {
            var start = idx
            while (start > 0 && upper[start - 1].isDigit()) start--
            val digits = upper.substring(start, idx)
            if (digits.length in 3..4) return digits.toIntOrNull() ?: 0
            idx = upper.indexOf('P', idx + 1)
        }
        return 0
    }

    private fun urlEncode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun urlDecode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun randomHex(len: Int): String {
        val chars = "0123456789abcdef"
        return (1..len).map { chars[Random.nextInt(16)] }.joinToString("")
    }

    private fun randomUuid(): String =
        "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"

    private fun randomSpoofedIp(): String {
        val prefixes = arrayOf(
            "103.241", "49.36", "117.195", "106.198", "122.162",
            "157.32", "182.70", "103.58", "27.60", "59.90",
        )
        return "${prefixes[Random.nextInt(prefixes.size)]}.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}"
    }

    companion object {
        private const val BASE = "/wefeed-mobile-bff"
        private const val STREAM_REFERER = "https://sportslive.wine"
        private const val USER_AGENT =
            "com.community.oneroom/50020044 (Linux; U; Android 11; en_US; " +
                "2201117TY; Build/RP1A.200720.011; Cronet/135.0.7012.3)"
        private const val CERT_SHA256 =
            "03d4b741632d136bc23e2de4c6adcdb7712f1980b619c183221801730bbd0410"
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val KEY_TOKEN = "mb_token"
        private const val KEY_TOKEN_EXP = "mb_token_exp"
        private const val KEY_TOKEN_CREATED = "mb_token_created"
        private const val KEY_DEVICE_ID = "mb_device_id"
        private const val KEY_GAID = "mb_gaid"
        private val HOSTS = arrayOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com",
        )
        private val RETRY_STATUSES = setOf(403, 406, 407, 429, 500, 502, 503, 504)
        // HMAC-MD5 key, from the canonical MovieBox-TUI client
        // (mesamirh/MovieBox-Tui) — the same 30-byte secret in both repos.
        private val SECRET_BYTES = byteArrayOf(
            0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(),
            0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
            0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(),
            0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
            0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(),
            0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
            0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(),
            0xbd.toByte(), 0x8e.toByte(),
        )
        private val ADULT_GENRES =
            setOf("adult", "erotic", "erotica", "porn", "pornography", "xxx", "hentai")
        private val CAM_TITLE_REGEX = Regex(
            "(?i)\\b(cam|hdcam|hdts|hd-ts|camrip|cam-rip|tsrip|telesync|telecine|hdtc|predvd)\\b" +
                "|\\bhd\\s*-?\\s*cam\\b",
        )
        private val ADULT_TITLE_REGEX = Regex(
            "(?i)\\b(xxx|porn|porno|erotic|erotica|hentai)\\b|\\bsex\\s+tape\\b|18\\+",
        )
    }
}
