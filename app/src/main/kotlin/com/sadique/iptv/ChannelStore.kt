package com.sadique.iptv

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/**
 * Favorites, recent channels, last channel and player volume. Favorites and
 * recent are stored as small M3U files so Playlist.parse can read them back;
 * the last channel and volume live in SharedPreferences.
 */
internal class ChannelStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val favoritesFile = File(context.filesDir, "favorites.m3u")
    private val recentFile = File(context.filesDir, "recent.m3u")
    private var favorites: MutableList<Channel> = loadFile(favoritesFile).toMutableList()
    private var recent: MutableList<Channel> = loadFile(recentFile).toMutableList()

    fun favoritesList(): List<Channel> = favorites.toList()

    fun recentList(): List<Channel> = recent.toList()

    fun isFavorite(channel: Channel): Boolean = favorites.any { it.key == channel.key }

    /** Returns true when the channel is a favorite after the toggle. */
    fun toggleFavorite(channel: Channel): Boolean {
        val existing = favorites.indexOfFirst { it.key == channel.key }
        val nowFavorite = if (existing >= 0) {
            favorites.removeAt(existing)
            false
        } else {
            favorites.add(channel)
            true
        }
        saveFile(favoritesFile, favorites)
        return nowFavorite
    }

    fun addRecent(channel: Channel) {
        recent.removeAll { it.key == channel.key }
        recent.add(0, channel)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        saveFile(recentFile, recent)
    }

    var lastChannel: Channel?
        get() {
            val raw = prefs.getString(KEY_LAST_CHANNEL, null) ?: return null
            val parts = raw.split("\u0001")
            if (parts.size < 3) return null
            return Channel(
                name = parts[0],
                group = parts[1],
                url = parts[2],
                web = parts.getOrNull(3) == "1",
                logo = parts.getOrNull(4).orEmpty(),
            )
        }
        set(value) {
            if (value == null) {
                prefs.edit().remove(KEY_LAST_CHANNEL).apply()
            } else {
                val raw = listOf(
                    value.name,
                    value.group,
                    value.url,
                    if (value.web) "1" else "0",
                    value.logo,
                ).joinToString("\u0001")
                prefs.edit().putString(KEY_LAST_CHANNEL, raw).apply()
            }
        }

    init {
        val retiredUrl = "https://cdn.live247stream.com/hyder/tv/playlist.m3u8"
        if (favorites.removeAll { it.url == retiredUrl }) saveFile(favoritesFile, favorites)
        if (recent.removeAll { it.url == retiredUrl }) saveFile(recentFile, recent)
        if (lastChannel?.url == retiredUrl) lastChannel = null

        fun needsUpdate(channel: Channel?) = channel != null && legacyFeeds[channel.url] == channel.name

        if (favorites.any(::needsUpdate) || recent.any(::needsUpdate) || needsUpdate(lastChannel)) {
            val catalog = try {
                context.assets.open("verified-channels.m3u").use {
                    Playlist.parse(Playlist.read(it)).associateBy { ch -> ch.name }
                }
            } catch (_: IOException) {
                emptyMap()
            }
            fun replace(channel: Channel): Channel =
                if (needsUpdate(channel)) catalog[channel.name] ?: channel else channel

            fun update(entries: MutableList<Channel>, file: File) {
                val migrated = entries.map(::replace).distinctBy { it.key }
                if (entries == migrated) return
                entries.clear()
                entries.addAll(migrated)
                saveFile(file, entries)
            }
            update(favorites, favoritesFile)
            update(recent, recentFile)
            lastChannel?.takeIf(::needsUpdate)?.let { lastChannel = replace(it) }
        }
    }

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f).coerceIn(0f, 1f)
        set(value) {
            prefs.edit().putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)).apply()
        }

    private fun loadFile(file: File): List<Channel> {
        if (!file.exists()) return emptyList()
        return try {
            Playlist.parse(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun saveFile(file: File, channels: List<Channel>) {
        try {
            if (channels.isEmpty()) {
                if (file.exists()) file.delete()
            } else {
                file.writeText(Playlist.serialize(channels), Charsets.UTF_8)
            }
        } catch (_: IOException) {
            // Favorites/recent are best-effort; playback must not depend on them.
        }
    }

    companion object {
        private val legacyFeeds = mapOf(
            "https://cdn4.mjunoon.tv:8087/streamtest/166M/chunks.m3u8" to "Green Entertainment",
            "https://cdn4.mjunoon.tv:8087/streamtest/146M/chunks.m3u8" to "24 News HD",
            "http://175.107.244.52:4456/play/a06o" to "Geo News",
            "https://cdn4.mjunoon.tv:8087/streamtest/131M/chunks.m3u8" to "8XM",
            "http://124.109.47.101/hls/stream1.m3u8" to "aurLife HD",
            "https://jmp2.uk/stvp-IN46000140Q" to "NH BollyFlix",
            "https://samitaorigin.tangotv.in/PUNJABITV/SAMITAORIGIN/index.m3u8" to "Punjabi TV",
            "http://103.86.132.58:9981/stream/channelid/1808607399" to "Hum News HD",
            "http://103.86.132.58:9981/stream/channelid/1933780354" to "A Sports HD",
            "https://live.msnetworks.net/geo-ent/master.m3u8" to "Geo Entertainment",
            "http://175.107.244.52:4456/play/a042" to "PTV Sports HD",
            "http://175.107.244.52:4456/play/a05v" to "Ten Sports Pakistan",
            "yt-live:@GeoNews/live" to "Geo News",
        )
        private const val MAX_RECENT = 20
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_VOLUME = "volume"
    }
}
