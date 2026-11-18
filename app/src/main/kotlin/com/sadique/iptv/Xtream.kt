package com.sadique.iptv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONException

/** Minimal Xtream Codes client: live streams plus their categories only. */
internal object Xtream {
    private const val TIMEOUT_MS = 15000

    fun fetchLiveStreams(server: String, username: String, password: String): List<Channel> {
        val base = server.trim().trimEnd('/')
        if (base.isEmpty() || username.isEmpty() || password.isEmpty()) {
            throw IOException("Server, username and password are all required.")
        }
        val baseUri = runCatching { URI.create(base) }.getOrNull()
            ?: throw IOException("Enter a valid Xtream server URL.")
        if ((baseUri.scheme != "https" && baseUri.scheme != "http") || baseUri.host == null) {
            throw IOException("Enter a valid Xtream server URL.")
        }
        val user = URLEncoder.encode(username, "UTF-8")
        val pass = URLEncoder.encode(password, "UTF-8")
        val auth = "username=$user&password=$pass"
        val categories = try {
            parseCategories(get("$base/player_api.php?$auth&action=get_live_categories"))
        } catch (_: IOException) {
            emptyMap()
        } catch (_: JSONException) {
            emptyMap()
        }
        val streams = get("$base/player_api.php?$auth&action=get_live_streams")
        return parseStreams(streams, categories, base, user, pass)
    }

    private fun get(address: String): String {
        val connection = URI.create(address).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode != 200) {
                throw IOException("Xtream server returned HTTP ${connection.responseCode}.")
            }
            return connection.inputStream.use { Playlist.read(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCategories(json: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("category_id")
            val name = item.optString("category_name")
            if (id.isNotEmpty() && name.isNotEmpty()) result[id] = name
        }
        return result
    }

    private fun parseStreams(
        json: String,
        categories: Map<String, String>,
        base: String,
        user: String,
        pass: String,
    ): List<Channel> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            throw IOException("Invalid Xtream response. Check the server details.")
        }
        val channels = mutableListOf<Channel>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val streamId = item.optString("stream_id")
            if (name.isEmpty() || streamId.isEmpty()) continue
            val url = "$base/live/$user/$pass/$streamId.ts"
            if (!seen.add(url)) continue
            val group = categories[item.optString("category_id")] ?: "Xtream"
            val logo = item.optString("stream_icon")
            channels += Channel(name, group, url, logo = logo)
            if (channels.size == Playlist.MAX_CHANNELS) break
        }
        if (channels.isEmpty()) throw IOException("No live streams found on this Xtream account.")
        return channels
    }
}
