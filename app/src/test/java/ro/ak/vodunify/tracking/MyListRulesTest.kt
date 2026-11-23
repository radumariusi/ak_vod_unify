package ro.ak.vodunify.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.tracking.HiddenTitles
import ro.ak.vodunify.core.tracking.SeriesMarker
import java.time.LocalDate

class MyListRulesTest {
    @Test
    fun hiddenUntilWatchedAgain() {
        val hidden = mapOf("tv:tmdb:1" to 1_000L)
        assertTrue(HiddenTitles.isHidden(hidden, "tv:tmdb:1", 900L))
        assertFalse(HiddenTitles.isHidden(hidden, "tv:tmdb:1", 2_000L))
        assertFalse(HiddenTitles.isHidden(hidden, "tv:tmdb:2", 0L))
    }

    @Test
    fun onlyAiredEpisodesAreMarked() {
        fun ep(n: Int, date: String?) = Episode(1, n, "E$n", "", null, date, null)
        val list = listOf(ep(1, "2026-11-01"), ep(2, "2026-11-23"), ep(3, "2026-11-30"), ep(4, null))
        assertEquals(listOf(1, 2), SeriesMarker.aired(list, LocalDate.of(2026, 11, 23)).map { it.number })
    }
}
