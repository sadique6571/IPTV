package com.sadique.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBufferWindowTest {
    @Test fun keepsAnHourOfVideoAndRetainsThePlaybackPositionAfterResume() {
        val window = LiveBufferWindow()
        val retired = mutableListOf<File>()
        for (index in 0L..600L) window.append(chunk(index), retired::add)
        assertEquals(0L, window.segments.first.sequence)
        assertTrue(window.durationUs >= 3_600_000_000L)
        assertTrue(retired.isEmpty())

        // The cursor has advanced 90 seconds while the recorder keeps adding segments.
        for (index in 601L..615L) window.append(chunk(index), retired::add)
        assertTrue(window.segments.first.sequence <= 15)
        assertTrue(window.segments.any { it.sequence == 15L })
        assertEquals(615L, window.segments.last.sequence)
        assertEquals(window.segments.first.sequence.toInt(), retired.size)
    }

    @Test fun dropsOldSegmentsWhenStorageLimitIsReached() {
        val window = LiveBufferWindow(maxBytes = 20)
        val retired = mutableListOf<File>()
        for (index in 0L..2L) window.append(chunk(index, size = 10), retired::add)
        assertEquals(listOf(1L, 2L), window.segments.map { it.sequence })
        assertEquals(listOf(File("0.ts")), retired)
    }

    private fun chunk(index: Long, size: Long = 1_500_000L) =
        LiveCachedSegment(index, 0, File("$index.ts"), null, 6_000_000L, size)
}
