package com.sadique.iptv

import android.content.Context
import java.io.File
import java.io.IOException
import java.util.UUID

internal data class SavedPlaylist(
    val id: String,
    val name: String,
    val kind: Kind,
    val source: String = "",
    val username: String = "",
    val password: String = "",
    val lastRefresh: Long = 0L,
) {
    enum class Kind { URL, FILE, XTREAM }
}

/** Playlist metadata and cached streams use device-local encryption. */
internal class PlaylistStore(context: Context) {
    private val dir = File(context.filesDir, "playlists").apply { mkdirs() }
    private val metaFile = File(dir, "meta.txt")
    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)

    @Synchronized
    fun protectLegacyFiles(legacy: File) {
        (dir.listFiles().orEmpty().toList() + legacy).filter {
            it.isFile && (it.extension == "m3u" || it == metaFile)
        }.forEach(PrivateFiles::migrate)
    }

    @Synchronized
    fun list(): List<SavedPlaylist> = try { readMeta() } catch (_: IOException) { emptyList() }

    @Synchronized
    fun add(
        name: String,
        kind: SavedPlaylist.Kind,
        source: String,
        username: String,
        password: String,
        channels: List<Channel>,
    ): SavedPlaylist {
        val id = "pl-" + UUID.randomUUID().toString().substring(0, 8)
        val cleanName = name.replace('|', ' ').replace('\n', ' ').trim().ifEmpty { "Playlist" }
        val playlist = SavedPlaylist(
            id = id,
            name = cleanName,
            kind = kind,
            source = source.trim(),
            username = username,
            password = password,
            lastRefresh = System.currentTimeMillis(),
        )
        writeChannels(playlist, channels)
        saveMeta(readMeta() + playlist)
        return playlist
    }

    @Synchronized
    fun channelsOf(playlist: SavedPlaylist): List<Channel> {
        val file = File(dir, playlist.id + ".m3u")
        if (!file.exists()) return emptyList()
        return try {
            Playlist.parse(PrivateFiles.read(file))
        } catch (_: IOException) {
            emptyList()
        }
    }

    /**
     * Re-downloads a URL or Xtream playlist and replaces the saved copy.
     * Throws IOException on failure; the old copy is kept untouched.
     */
    @Synchronized
    fun refresh(playlist: SavedPlaylist): List<Channel> {
        val fresh = when (playlist.kind) {
            SavedPlaylist.Kind.URL -> Playlist.fetch(playlist.source)
            SavedPlaylist.Kind.XTREAM ->
                Xtream.fetchLiveStreams(playlist.source, playlist.username, playlist.password)
            SavedPlaylist.Kind.FILE -> throw IOException("Local file playlists cannot refresh.")
        }
        writeChannels(playlist, fresh)
        saveMeta(readMeta().map {
            if (it.id == playlist.id) it.copy(lastRefresh = System.currentTimeMillis()) else it
        })
        return fresh
    }

    @Synchronized
    fun remove(playlist: SavedPlaylist) {
        File(dir, playlist.id + ".m3u").delete()
        saveMeta(readMeta().filter { it.id != playlist.id })
        if (activeId == playlist.id) activeId = null
    }

    @Synchronized
    fun active(): SavedPlaylist? {
        val id = activeId ?: return null
        return list().firstOrNull { it.id == id }
    }

    var activeId: String?
        get() = prefs.getString(KEY_ACTIVE_PLAYLIST, null)
        set(value) {
            if (value == null) prefs.edit().remove(KEY_ACTIVE_PLAYLIST).apply()
            else prefs.edit().putString(KEY_ACTIVE_PLAYLIST, value).apply()
        }

    /** One-time migration of the old single imported playlist (channels.m3u). */
    @Synchronized
    fun migrateLegacy(legacyFile: File): SavedPlaylist? {
        if (!legacyFile.exists() || readMeta().isNotEmpty()) return null
        val channels = try {
            Playlist.parse(PrivateFiles.read(legacyFile))
        } catch (_: IOException) {
            return null
        }
        if (channels.isEmpty()) return null
        return add("My playlist", SavedPlaylist.Kind.FILE, "", "", "", channels)
    }

    private fun writeChannels(playlist: SavedPlaylist, channels: List<Channel>) {
        val data = Playlist.serialize(channels).toByteArray(Charsets.UTF_8)
        if (data.size > Playlist.MAX_BYTES) throw IOException("Playlist exceeds 12 MB.")
        PrivateFiles.write(File(dir, playlist.id + ".m3u"), data.toString(Charsets.UTF_8))
    }

    private fun saveMeta(playlists: List<SavedPlaylist>) {
        val text = playlists.joinToString("\n") { playlist ->
            listOf(
                playlist.id,
                playlist.name,
                playlist.kind.name,
                playlist.source,
                playlist.username,
                playlist.password,
                playlist.lastRefresh.toString(),
            ).let { org.json.JSONArray(it).toString() }
        }
        PrivateFiles.write(metaFile, text)
    }

    private fun readMeta(): List<SavedPlaylist> {
        if (!metaFile.exists()) return emptyList()
        return try {
            PrivateFiles.read(metaFile).lineSequence().filter { it.isNotBlank() }.toList().mapNotNull { line ->
                val parts = if (line.startsWith("[")) {
                    val row = org.json.JSONArray(line)
                    List(row.length()) { row.getString(it) }
                } else line.split("|")
                if (parts.size < 7) return@mapNotNull null
                val kind = runCatching { SavedPlaylist.Kind.valueOf(parts[2]) }.getOrNull()
                    ?: return@mapNotNull null
                SavedPlaylist(
                    id = parts[0],
                    name = parts[1],
                    kind = kind,
                    source = parts[3],
                    username = parts[4],
                    password = parts[5],
                    lastRefresh = parts[6].toLongOrNull() ?: 0L,
                )
            }
        } catch (error: org.json.JSONException) {
            throw IOException("Saved playlist metadata could not be read.", error)
        }
    }

    companion object {
        private const val KEY_ACTIVE_PLAYLIST = "active_playlist"
    }
}
