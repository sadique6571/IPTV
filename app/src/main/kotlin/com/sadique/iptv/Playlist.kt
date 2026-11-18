package com.sadique.iptv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal object Playlist {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_CHANNELS = 1000
    private val group = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val country = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE)

    fun read(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAX_BYTES) throw IOException("Playlist exceeds 2 MB.")
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }

    fun parse(text: String, base: URI? = null): List<Channel> {
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<URI>()
        var name = ""
        var category = "Imported"
        for (line in text.removePrefix("\uFEFF").lineSequence().map(String::trim)) {
            when {
                line.startsWith("#EXT-X-") ->
                    throw IOException("This is a single HLS stream. Use Open HTTPS stream.")
                line.startsWith("#EXTINF:") -> {
                    var quoted = false
                    val comma = line.indexOfFirst { character ->
                        if (character == '"') quoted = !quoted
                        character == ',' && !quoted
                    }
                    name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val label = group.find(line)?.groupValues?.get(1)?.lowercase(Locale.ROOT).orEmpty()
                    val code = country.find(line)?.groupValues?.get(1).orEmpty()
                    category = when {
                        "cricket" in label -> "Cricket"
                        "drama" in label -> "Drama"
                        "pakistan" in label || code.equals("PK", true) -> "Pakistan"
                        "india" in label || code.equals("IN", true) -> "India"
                        else -> "Imported"
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    val uri = runCatching { base?.resolve(line) ?: URI.create(line) }.getOrNull()
                    if (name.isNotEmpty() && uri != null && valid(uri) && seen.add(uri)) {
                        channels += Channel(name, category, uri.toString())
                        if (channels.size == MAX_CHANNELS) break
                    }
                    name = ""
                }
            }
        }
        if (channels.isEmpty()) throw IOException("No HTTPS channel entries found.")
        return channels
    }

    fun valid(uri: URI): Boolean =
        uri.scheme.equals("https", true) && uri.host != null && uri.userInfo == null

    fun fetch(address: String): List<Channel> {
        var uri = runCatching { URI.create(address) }.getOrNull()
            ?: throw IOException("Enter a valid HTTPS playlist URL.")
        repeat(5) {
            if (!valid(uri)) throw IOException("An HTTPS playlist URL is required.")
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 10000
            try {
                when (val status = connection.responseCode) {
                    200 -> return connection.inputStream.use { parse(read(it), uri) }
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
