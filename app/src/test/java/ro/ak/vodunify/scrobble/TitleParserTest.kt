package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.scrobble.MatchRules
import ro.ak.vodunify.core.scrobble.ScrobbleRules
import ro.ak.vodunify.core.scrobble.TitleGuess
import ro.ak.vodunify.core.scrobble.TitleParser

class TitleParserTest {

    @Test
    fun seasonEpisodeInSubtitleWithShowInTitle() {
        val g = TitleParser.guesses(mapOf("title" to "Stranger Things", "display_subtitle" to "S1:E3 Holly, Jolly"), "Netflix")
        assertEquals(TitleGuess.NumberedEpisode("Stranger Things", 1, 3, "Holly, Jolly"), g.first())
    }

    @Test
    fun showAndNumbersInSameField() {
        val g = TitleParser.guesses(mapOf("title" to "The Bear: S02E07 \"Forks\""))
        assertEquals(TitleGuess.NumberedEpisode("The Bear", 2, 7, "Forks"), g.first())
    }

    @Test
    fun romanianSeasonEpisodeWords() {
        val g = TitleParser.guesses(mapOf("title" to "Sezonul 2 Episodul 5", "artist" to "Mercredi"))
        assertEquals(TitleGuess.NumberedEpisode("Mercredi", 2, 5, null), g.first())
    }

    @Test
    fun episodeTitleAndShowInSeparateFields() {
        val g = TitleParser.guesses(mapOf("title" to "Chapter One", "artist" to "Stranger Things", "display_subtitle" to "S1:E1"))
        assertTrue(g.contains(TitleGuess.NumberedEpisode("Stranger Things", 1, 1, "Chapter One")))
    }

    @Test
    fun episodeOnlyForMiniSeries() {
        val g = TitleParser.guesses(mapOf("title" to "Adolescence", "display_subtitle" to "Episode 2"))
        assertEquals(TitleGuess.NumberedEpisode("Adolescence", null, 2, null), g.first())
    }

    @Test
    fun movieWithoutNumbers() {
        val g = TitleParser.guesses(mapOf("title" to "Glass Onion (2022)"), "Netflix")
        assertEquals(listOf(TitleGuess.Movie("Glass Onion", 2022)), g)
    }

    @Test
    fun appNameIsNotATitle() {
        val g = TitleParser.guesses(mapOf("title" to "Dune: Part Two", "artist" to "Max"), "Max")
        assertEquals(listOf(TitleGuess.Movie("Dune: Part Two", null)), g)
    }

    @Test
    fun namedEpisodeGuessesBeforeMovie() {
        val g = TitleParser.guesses(mapOf("title" to "Pilot", "artist" to "Breaking Bad"))
        assertEquals(TitleGuess.NamedEpisode("Breaking Bad", "Pilot"), g.first())
        assertTrue(g.any { it is TitleGuess.Movie })
    }

    @Test
    fun noFieldsNoGuesses() {
        assertTrue(TitleParser.guesses(emptyMap()).isEmpty())
        assertTrue(TitleParser.guesses(mapOf("title" to "Netflix"), "Netflix").isEmpty())
    }

    @Test
    fun normalizeIgnoresDiacriticsAndPunctuation() {
        assertEquals("fratii cu sabii", TitleParser.normalize("Frații cu săbii!"))
        assertEquals("tom and jerry", TitleParser.normalize("Tom & Jerry"))
    }

    @Test
    fun exactPickOnlyAndPrefersYear() {
        val c = listOf(
            MatchRules.Candidate(1, listOf("Dune", "Dune"), 1984),
            MatchRules.Candidate(2, listOf("Dune", "Dune"), 2021),
            MatchRules.Candidate(3, listOf("Dune: Part Two", null), 2024),
        )
        assertEquals(2L, MatchRules.pickExact("Dune", c, 2021)?.id)
        assertEquals(1L, MatchRules.pickExact("dune", c)?.id)
        assertNull(MatchRules.pickExact("Dune explained", c))
    }

    @Test
    fun rules() {
        assertTrue(ScrobbleRules.isIgnoredPackage("com.google.android.youtube.tv"))
        assertTrue(ScrobbleRules.isIgnoredPackage("com.spotify.tv.android"))
        assertTrue(!ScrobbleRules.isIgnoredPackage("com.netflix.ninja"))
        assertEquals(0.5f, ScrobbleRules.progress(25 * 60_000L, 50 * 60_000L)!!, 0.0001f)
        assertNull(ScrobbleRules.progress(1000, null))
        assertTrue(ScrobbleRules.isFinished(0.95f))
        assertTrue(!ScrobbleRules.isFinished(0.85f))
        assertTrue(ScrobbleRules.isFinished(0.85f, threshold = 0.80f))
        assertTrue(!ScrobbleRules.isFinished(null))
        assertEquals(30 * 60_000L, ScrobbleRules.mergeEstimated(5 * 60_000L, 30 * 60_000L))
        assertEquals(5 * 60_000L, ScrobbleRules.mergeEstimated(5 * 60_000L, null))
    }
}
