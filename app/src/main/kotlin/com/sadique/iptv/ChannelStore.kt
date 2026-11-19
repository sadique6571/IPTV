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
        fun oldGeo(channel: Channel?) =
            channel?.name == "Geo News" && channel.url == "yt-live:@GeoNews/live"

        if (favorites.any(::oldGeo) || recent.any(::oldGeo) || oldGeo(lastChannel)) {
            val replacement = try {
                context.assets.open("verified-channels.m3u").use {
                    Playlist.parse(Playlist.read(it)).firstOrNull { ch -> ch.name == "Geo News" && !ch.web }
                }
            } catch (_: IOException) {
                null
            }
            if (replacement != null) {
                fun update(entries: MutableList<Channel>, file: File) {
                    if (entries.none(::oldGeo)) return
                    val migrated = entries.map { if (oldGeo(it)) replacement else it }.distinctBy { it.key }
                    entries.clear()
                    entries.addAll(migrated)
                    saveFile(file, entries)
                }
                update(favorites, favoritesFile)
                update(recent, recentFile)
                if (oldGeo(lastChannel)) lastChannel = replacement
            }
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
        private const val MAX_RECENT = 20
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_VOLUME = "volume"
    }
}
