package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.vodunify.core.scrobble.MatchRules

class SeasonRemapTest {

    private val lf = listOf("Las Fierbinţi", null, "Las Fierbinți")

    @Test
    fun lasFierbintiVoyoSeasonsMapToTmdb() {
        assertEquals(23, MatchRules.remapSeason(lf, 23))
        assertEquals(24, MatchRules.remapSeason(lf, 24))
        assertEquals(25, MatchRules.remapSeason(lf, 25))
        assertEquals(27, MatchRules.remapSeason(lf, 26))
        assertEquals(28, MatchRules.remapSeason(lf, 27))
        assertEquals(29, MatchRules.remapSeason(lf, 28))
        assertEquals(30, MatchRules.remapSeason(lf, 29))
    }

    @Test
    fun cedillaAndCommaSpellingsBothMatch() {
        assertEquals(30, MatchRules.remapSeason(listOf("Las Fierbinţi"), 29))
        assertEquals(30, MatchRules.remapSeason(listOf("LAS FIERBINȚI"), 29))
    }

    @Test
    fun otherShowsUntouched() {
        assertEquals(29, MatchRules.remapSeason(listOf("The Simpsons"), 29))
        assertEquals(3, MatchRules.remapSeason(lf, 3))
    }
}
