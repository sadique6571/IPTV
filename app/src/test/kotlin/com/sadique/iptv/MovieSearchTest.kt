package com.sadique.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieSearchTest {
    @Test fun prefersHindiForTheSameTitleButKeepsAnExactOriginalAboveRelatedTitles() {
        val original = MovieBox.Movie("original", "The Body [English]")
        val hindi = MovieBox.Movie("hindi", "The Body [Hindi]", isHindi = true)
        val related = MovieBox.Movie("related", "The Body Guard [Hindi]", isHindi = true)
        val search = MovieTitleSearch("The Body Hindi Dubbed")
        assertEquals(listOf(hindi, original, related), search.rank(listOf(related, original, hindi, hindi)))
        assertEquals(listOf(original, related), search.rank(listOf(related, original)))
    }

    @Test fun recognizesMisspellingsTransposedLettersAndPartialTitles() {
        assertEquals(CLOSE_TITLE_MATCH, MovieTitleSearch("Avengrs").relevance("Avengers [Hindi]"))
        assertEquals(CLOSE_TITLE_MATCH, MovieTitleSearch("The Boddy").relevance("Body [Hindi]"))
        assertEquals(CLOSE_TITLE_MATCH, MovieTitleSearch("The Bpdy").relevance("The Body"))
        assertEquals(CLOSE_TITLE_MATCH, MovieTitleSearch("Avnegers").relevance("Avengers"))
        assertTrue(MovieTitleSearch("Mission Impossible: Fallout").relevance("Mission Impossible") > 0)
        assertEquals(0, MovieTitleSearch("Mission Impossible: Fallout").relevance("Mission Mars"))
    }

    @Test fun sortsEquallyRelevantTitlesByNewestReleaseAfterHindiPreference() {
        val hindi = MovieBox.Movie("hindi", "The Body [Hindi]", "2019", isHindi = true)
        val old = MovieBox.Movie("old", "The Body", "2024")
        val early = MovieBox.Movie("early", "The Body", "2026", releaseDate = "2026-02-14")
        val late = MovieBox.Movie("late", "The Body", "2026", releaseDate = "2026-09-03")
        val undated = MovieBox.Movie("undated", "The Body")
        val related = MovieBox.Movie("related", "The Body Guard", "2026", isHindi = true)
        val search = MovieTitleSearch("The Body")
        assertEquals(
            listOf(hindi, late, early, old, undated, related),
            search.rank(listOf(old, undated, related, early, late, hindi)),
        )
    }

    @Test fun identifiesLanguageTagsRatherThanWordsInTheMovieTitle() {
        assertTrue(isHindiMovieTitle("The Body [Hindi]"))
        assertTrue(isHindiMovieTitle("The Body - Hindi Dubbed"))
        assertTrue(isHindiMovieTitle("The Body (हिंदी)"))
        assertFalse(isHindiMovieTitle("Hindi Medium"))
    }
}
