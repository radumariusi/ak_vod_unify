package ro.ak.vodunify.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.reconcile.LibraryEntry
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.tracking.HiddenTitles
import ro.ak.vodunify.core.tracking.SeriesMarker
import ro.ak.vodunify.ui.library.LibraryFilter
import ro.ak.vodunify.ui.library.LibraryLogic
import ro.ak.vodunify.ui.library.LibraryTab
import java.time.LocalDate

class MyListRulesTest {
    @Test
    fun savedRowsOnlyContainUnstartedTitles() {
        fun saved(id: Long, type: String, vararg activity: LibraryStatus): LibraryEntry {
            val key = "$type:$id"
            val statuses = setOf(LibraryStatus.WATCHLIST, *activity)
            return LibraryEntry(
                titleKey = key,
                ref = TrackedRef(key, type, key, null, null, null, null, id, null, null, null),
                statuses = statuses,
                sources = setOf("local"),
                sourcesByStatus = statuses.associateWith { setOf("local") },
                lastActivityAt = id,
                resume = null,
                upNext = null,
            )
        }
        val movie = saved(1, "MOVIE")
        val show = saved(2, "SHOW")
        val all = listOf(movie, show, saved(3, "SHOW", LibraryStatus.IN_PROGRESS),
            saved(4, "MOVIE", LibraryStatus.WATCHED), saved(5, "SHOW", LibraryStatus.UP_NEXT))
        assertEquals(listOf(movie, show), LibraryLogic.filter(all, LibraryFilter(LibraryTab.WATCHLIST)))
        assertEquals(mapOf(null to 2, "local" to 2), LibraryLogic.countsBySource(all, LibraryTab.WATCHLIST))
    }

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
