package com.sadique.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesNavigationTest {
    private val seasons = listOf(
        MovieBox.Season(3, episodes = listOf(MovieBox.EpisodeRef(2, "Premiere"))),
        MovieBox.Season(1, episodes = listOf(MovieBox.EpisodeRef(4), MovieBox.EpisodeRef(1))),
        MovieBox.Season(2),
    )

    @Test fun skipsMissingEpisodeNumbersAndEmptySeasons() {
        assertEquals(SeriesEpisode(1, MovieBox.EpisodeRef(4)), adjacentEpisode(seasons, 1, 1, 1))
        assertEquals(SeriesEpisode(3, MovieBox.EpisodeRef(2, "Premiere")), adjacentEpisode(seasons, 1, 4, 1))
        assertEquals(SeriesEpisode(1, MovieBox.EpisodeRef(4)), adjacentEpisode(seasons, 3, 2, -1))
    }

    @Test fun returnsNoEpisodeBeyondTheSeriesBoundaries() {
        assertNull(adjacentEpisode(seasons, 1, 1, -1))
        assertNull(adjacentEpisode(seasons, 3, 2, 1))
        assertNull(adjacentEpisode(emptyList(), 1, 1, 1))
    }

    @Test fun continueWatchingAdvancesAtSeasonBoundaryAndClearsFinishedSeries() {
        val saved = MovieStore.ContinueEntry("show", "Show", "", 58000, 60000, 720, 123, 1, 4, true)
        val next = saved.afterCompletion(seasons)
        assertEquals(saved.copy(season = 3, episode = 2, positionMs = 0, durationMs = 0), next)
        assertNull(next!!.afterCompletion(seasons))
        assertEquals(saved, saved.afterCompletion(emptyList()))
        assertNull(saved.copy(isSeries = false).afterCompletion(seasons))
    }
}
