package com.sadique.iptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal object Playlist {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_CHANNELS = 30_000
    private val group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val country = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val logo = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val channelId = Regex("""tvg-id="[^"]*\.([A-Za-z]{2})(?:@[^"]*)?"""")
    private val languages = Regex("""tvg-language="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val youtubeChannel = Regex("UC[A-Za-z0-9_-]{22}")

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_BYTES) throw IOException("Playlist exceeds 12 MB.")
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }

    fun parse(text: String, base: URI? = null, defaultCountry: String = ""): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<Pair<String, String>>()
        var name = ""
        var category = "Imported"
        var region = ""
        var logoUrl = ""
        var languageTags = emptySet<String>()
        for (line in text.removePrefix("\uFEFF").lineSequence().map(String::trim)) {
            when {
                line.startsWith("#EXT-X-") ->
                    throw IOException("This is a single HLS stream. Use Open stream.")
                line.startsWith("#EXTINF:") -> {
                    var quoted = false
                    val comma = line.indexOfFirst { character ->
                        if (character == '"') quoted = !quoted
                        character == ',' && !quoted
                    }
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val label = group.find(line)?.groupValues?.get(1).orEmpty()
                    logoUrl = logo.find(line)?.groupValues?.get(1).orEmpty().trim()
                    languageTags = languages.find(line)?.groupValues?.get(1).orEmpty()
                        .split(',', ';').mapNotNull { value ->
                            when (value.trim().lowercase(Locale.ROOT)) {
                                "ur", "urd", "urdu" -> "urd"
                                "hi", "hin", "hindi" -> "hin"
                                "pa", "pan", "punjabi" -> "pan"
                                "en", "eng", "english" -> "eng"
                                else -> null
                            }
                        }.toSet()
                    val code = country.find(line)?.groupValues?.get(1).orEmpty()
                    val metadataCountry = code.split(';', ',').firstOrNull().orEmpty()
                    region = when {
                        metadataCountry.isNotBlank() -> metadataCountry.trim()
                        channelId.containsMatchIn(line) -> channelId.find(line)!!.groupValues[1]
                        "pakistan" in label.lowercase(Locale.ROOT) -> "PK"
                        "india" in label.lowercase(Locale.ROOT) -> "IN"
                        else -> defaultCountry
                    }.uppercase(Locale.ROOT)
                    category = when {
                        "cricket" in name.lowercase(Locale.ROOT) || "cricket" in label.lowercase(Locale.ROOT) -> "Cricket"
                        "drama" in label.lowercase(Locale.ROOT) -> "Drama"
                        else -> label.ifBlank { "Imported" }
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val uri = runCatching { base?.resolve(line) ?: URI.create(line) }.getOrNull()
                    if (name.isNotEmpty() && uri != null && validStream(uri) && seen.add(name to uri.toString())) {
                        val embed = embedSource(uri)
                        channels += Channel(name, category, embed ?: uri.toString(), embed != null, region, languageTags, logoUrl)
                        if (channels.size == MAX_CHANNELS) break
                    }
                    name = ""
                    logoUrl = ""
                }
            }
        }
        if (channels.isEmpty()) throw IOException("No compatible channel entries found.")
        return channels
    }

    fun validStream(uri: URI): Boolean =
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            uri.host != null && uri.userInfo == null

    private fun embedSource(uri: URI): String? {
        val host = uri.host.lowercase(Locale.ROOT).removePrefix("www.").removePrefix("m.")
        val path = uri.path.orEmpty().trim('/').split('/')
        // Generic web-player embeds (CricHD's playsza.st player pages):
        // the URL itself is the embed — playEmbed loads it in the WebView
        // as-is. Only this host is allowlisted (see playEmbed's client),
        // so a rotated embed host needs a one-line change there too.
        if (host == "playsza.st") return "web:$uri"
        if (host == "dai.ly" || host == "dailymotion.com") {
            val id = if (host == "dai.ly") path.first() else path.last()
            if (id.matches(Regex("[A-Za-z0-9]{5,12}"))) return "dm:$id"
            return null
        }
        if (host != "youtu.be" && host != "youtube.com" && host != "youtube-nocookie.com") return null
        val id = if (host == "youtu.be" || path.first() in setOf("embed", "live", "shorts")) {
            path.last()
        } else {
            uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("v=") }?.substring(2).orEmpty()
        }
        if (videoId.matches(id)) return id
        if (path.size >= 2 && path.first() == "channel" && youtubeChannel.matches(path[1])) {
            return "yt-channel:${path[1]}"
        }
        if (path.last() == "live" && path.all { it.matches(Regex("[@A-Za-z0-9_.-]+")) }) {
            return "yt-live:${path.joinToString("/")}"
        }
        return "unsupported"
    }

    /** Writes channels back to M3U text (favorites, recent, saved playlists). */
    fun serialize(channels: List<Channel>): String = buildString {
        appendLine("#EXTM3U")
        for (channel in channels) {
            val group = channel.group.replace('"', ' ')
            val country = channel.country.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
            val languages = channel.languages.joinToString(";")
            val logo = channel.logo.replace('"', ' ').trim()
            val logoPart = if (logo.isEmpty()) "" else " tvg-logo=\"$logo\""
            appendLine("#EXTINF:-1 group-title=\"$group\" tvg-country=\"$country\" " +
                "tvg-language=\"$languages\"$logoPart,${channel.name}")
            appendLine(serializedUrl(channel))
        }
    }

    fun serializedUrl(channel: Channel): String = when {
        !channel.web -> channel.url
        channel.url.startsWith("web:") -> channel.url.substringAfter(':')
        channel.url.startsWith("dm:") -> "https://www.dailymotion.com/video/${channel.url.substringAfter(':')}"
        channel.url.startsWith("yt-live:") -> "https://www.youtube.com/${channel.url.substringAfter(':')}"
        channel.url.startsWith("yt-channel:") -> "https://www.youtube.com/channel/${channel.url.substringAfter(':')}/live"
        else -> "https://www.youtube.com/watch?v=${channel.url}"
    }

    fun resolveYouTubeLive(path: String): String? {
        if (!path.matches(Regex("(?:[@A-Za-z0-9_.-]+/)+live"))) return null
        val connection = URI.create("https://www.youtube.com/$path").toURL().openConnection()
            as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        try {
            val document = connection.inputStream.use(::read)
            val canonical = Regex("""rel="canonical" href="https://www\.youtube\.com/watch\?v=([A-Za-z0-9_-]{11})""")
                .find(document)?.groupValues?.get(1)
            if (canonical != null) return canonical
            val channel = Regex("""itemprop="channelId" content="(UC[A-Za-z0-9_-]{22})"""")
                .find(document)?.groupValues?.get(1)
                ?: Regex("""<link rel="canonical" href="https://www\.youtube\.com/channel/(UC[A-Za-z0-9_-]{22})"""")
                    .find(document)?.groupValues?.get(1)
            return channel?.let { "yt-channel:$it" }
        } finally {
            connection.disconnect()
        }
    }

    fun fetch(address: String, defaultCountry: String = ""): List<Channel> =
        fetch(address, defaultCountry) { it.toURL().openConnection() as HttpURLConnection }

    internal fun fetch(
        address: String,
        defaultCountry: String,
        openConnection: (URI) -> HttpURLConnection,
    ): List<Channel> {
        var uri = runCatching { URI.create(address) }.getOrNull()
            ?: throw IOException("Enter a valid HTTPS playlist URL.")
        repeat(5) {
            if (!uri.scheme.equals("https", true) || !validStream(uri)) {
                throw IOException("An HTTPS playlist URL is required.")
            }
            val connection = openConnection(uri)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use { parse(read(it), uri, defaultCountry) }
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Invalid playlist redirect.")
                        uri = runCatching { uri.resolve(location) }.getOrElse {
                            throw IOException("Invalid playlist redirect.", it)
                        }
                    }
                    else -> throw IOException("Server returned HTTP $status.")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many playlist redirects.")
    }
}
