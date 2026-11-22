package com.sadique.iptv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaylistTest {
    private val origin = URI("https://example.test/start/list.m3u")
    private val entry = "#EXTINF:-1 tvg-language=\"Urdu,Punjabi\",Demo\nstream.m3u8"

    @Test fun parserResolvesRelativePathsAndSkipsDuplicates() {
        val channel = Playlist.parse("$entry\n$entry", origin).single()
        assertEquals("Demo", channel.name)
        assertEquals(setOf("urd", "pan"), channel.languages)
        assertEquals("https://example.test/start/stream.m3u8", channel.url)
    }

    @Test fun localImportsStillAcceptHttpStreams() {
        val channel = Playlist.parse("#EXTINF:-1,Demo\nhttp://example.test/live").single()
        assertEquals("http://example.test/live", channel.url)
    }

    @Test fun rejectsSingleHlsManifestAsChannelList() {
        assertThrows(IOException::class.java) { Playlist.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6") }
    }

    private fun response(uri: URI, status: Int, location: String? = null) =
        object : HttpURLConnection(uri.toURL()) {
            override fun getResponseCode() = status
            override fun getHeaderField(name: String?) = if (name == "Location") location else null
            override fun getInputStream() = entry.byteInputStream()
            override fun usingProxy() = false
            override fun connect() {}
            override fun disconnect() {}
        }

    @Test fun resolvesChannelRelativeToFinalPlaylistAfterRedirect() {
        val redirected = origin.resolve("../new/list.m3u")
        val contacts = mutableListOf<URI>()
        val result = Playlist.fetch(origin.toString(), "") { uri ->
            contacts.add(uri)
            if (uri == origin) response(uri, 302, "../new/list.m3u") else response(uri, 200)
        }
        assertEquals(listOf(origin, redirected), contacts)
        assertEquals(redirected.resolve("stream.m3u8").toString(), result.single().url)
    }

    @Test fun rejectsHttpRedirectBeforeOpeningIt() {
        val contacts = mutableListOf<URI>()
        assertThrows(IOException::class.java) {
            Playlist.fetch(origin.toString(), "") { uri ->
                contacts.add(uri)
                response(uri, 302, "http://example.test/list")
            }
        }
        assertEquals(listOf(origin), contacts)
    }

    @Test fun redirectLoopHasBoundedAttempts() {
        var count = 0
        assertThrows(IOException::class.java) {
            Playlist.fetch(origin.toString(), "") { uri ->
                count++
                response(uri, 307, origin.toString())
            }
        }
        assertEquals(5, count)
    }
}
