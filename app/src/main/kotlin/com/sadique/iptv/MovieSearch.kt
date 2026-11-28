package com.sadique.iptv

import java.text.Normalizer

private val languageTag = Regex(
    """(?i)[\[(]\s*(?:hindi|हिंदी|हिन्दी|english|urdu|punjabi|tamil|telugu|malayalam|kannada|bengali|korean|chinese|japanese|spanish|french)(?:\s+(?:dubbed|dub))?\s*[\])]"""
)
private val hindiSuffix = Regex("""(?i)(?:\s*[-–·:]\s*hindi(?:\s+(?:dubbed|dub))?|\s+hindi\s+(?:dubbed|dub))$""")
private val hindiQuerySuffix = Regex("""(?i)\s+hindi(?:\s+(?:dubbed|dub))?$""")
private val hindiTag = Regex("""(?i)\bhindi\b|हिंदी|हिन्दी""")
private val nonTitleCharacters = Regex("""[^\p{L}\p{M}\p{N}]+""")
private val accents = Regex("""\p{InCombiningDiacriticalMarks}+""")
private val articles = setOf("a", "an", "the")
private val isoReleaseDate = Regex("""\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\d|3[01])""")

internal fun isHindiMovieTitle(title: String): Boolean =
    languageTag.findAll(title).any { hindiTag.containsMatchIn(it.value) } ||
        hindiSuffix.containsMatchIn(title)

internal fun movieSearchTerm(query: String): String =
    hindiQuerySuffix.replace(stripLanguageTags(query), "").trim().ifBlank { query.trim() }

private fun stripLanguageTags(title: String): String =
    hindiSuffix.replace(languageTag.replace(title, " ").trim(), "").trim()

private fun titleWords(title: String): List<String> {
    val normalized = accents.replace(Normalizer.normalize(stripLanguageTags(title), Normalizer.Form.NFD), "")
    return nonTitleCharacters.split(normalized.lowercase()).filter { it.isNotEmpty() }
}

private fun significantWords(words: List<String>): List<String> =
    words.filterNot { it in articles }.ifEmpty { words }

internal const val CLOSE_TITLE_MATCH = 3

internal class MovieTitleSearch(query: String) {
    private val words = significantWords(titleWords(movieSearchTerm(query)))
    private val phrase = words.joinToString(" ")

    fun relevance(title: String): Int {
        if (words.isEmpty()) return 0
        val titleWords = significantWords(titleWords(title))
        val titlePhrase = titleWords.joinToString(" ")
        if (titlePhrase == phrase) return 5
        if (" $titlePhrase ".contains(" $phrase ")) return 4
        val matches = words.count { word -> titleWords.any { similarWord(word, it) } }
        if (matches == words.size) return CLOSE_TITLE_MATCH
        if (matches * 2 >= words.size && words.any { it.length >= 4 && it in titleWords }) return 2
        return 0
    }

    fun relatedTerms(): List<String> {
        val keywords = words.filter { it.length >= 4 }
        val longest = keywords.maxByOrNull { it.length } ?: return emptyList()
        return listOfNotNull(
            keywords.minByOrNull { it.length }?.takeIf { words.size > 1 },
            longest.take((longest.length - 2).coerceAtLeast(3)),
        ).distinct()
    }

    fun rank(movies: List<MovieBox.Movie>): List<MovieBox.Movie> =
        movies.distinctBy { it.subjectId }
            .map { it to relevance(it.title) }
            .sortedWith(compareByDescending<Pair<MovieBox.Movie, Int>> { it.second }
                .thenByDescending { it.second > 0 && it.first.isHindi }
                .thenByDescending { it.first.year.toIntOrNull() ?: 0 }
                .thenByDescending { (movie, _) ->
                    movie.releaseDate.take(10).takeIf { isoReleaseDate.matches(it) }.orEmpty()
                })
            .map { it.first }
}

private fun similarWord(query: String, title: String): Boolean {
    if (query == title) return true
    if (query.length < 4 || title.length < 4) return false
    if (query.length >= 5 && title.startsWith(query)) return true
    if (query.length == title.length) {
        val differences = query.indices.filter { query[it] != title[it] }
        return differences.size == 1 || differences.size == 2 &&
            differences[1] == differences[0] + 1 &&
            query[differences[0]] == title[differences[1]] &&
            query[differences[1]] == title[differences[0]]
    }
    if (kotlin.math.abs(query.length - title.length) != 1) return false
    val (shorter, longer) = if (query.length < title.length) query to title else title to query
    val difference = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: shorter.length
    return shorter.regionMatches(difference, longer, difference + 1, shorter.length - difference)
}
