package ro.ak.vodunify.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.SeasonSummary
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.tracking.LocalProgress
import ro.ak.vodunify.core.tracking.SeriesMarker
import ro.ak.vodunify.ui.library.LibraryLogic

class LocalProgressTest {
    private val show = MediaRef(MediaType.SHOW, ExternalIds(tmdb = 1), "Serial")

    private fun ep(s: Int, e: Int, at: Long) = WatchEventEntity(
        id = "local|tv:tmdb:1:s${s}e$e|$at",
        source = "local",
        ref = TrackedRef("tv:tmdb:1", "EPISODE", "Serial", null, s, e, null, 1, null, null, null),
        watchedAt = at,
    )

    @Test
    fun computesProgressAndNextEpisode() {
        val events = listOf(ep(1, 1, 10), ep(1, 2, 20), ep(1, 2, 25), ep(1, 3, 30))
        val p = LocalProgress.show(show, events, mapOf(0 to 4, 1 to 3, 2 to 5), "local")
        assertEquals(8, p.aired)
        assertEquals(3, p.completed)
        assertEquals(2, p.next?.season)
        assertEquals(1, p.next?.episode)
        assertTrue(EpisodeNumber(1, 2) in p.watched)
    }

    @Test
    fun summaryAndLibraryStatus() {
        val summaries = LocalProgress.summaries(listOf(ep(1, 1, 10), ep(1, 2, 20)))
        val s = summaries.getValue("tv:tmdb:1")
        assertEquals(2, s.watched)
        assertEquals(2, s.lastEpisode)

        val inProgress = LibraryLogic.mergeSeries(emptyList(), summaries, mapOf("tv:tmdb:1" to 10))
        assertEquals(setOf(LibraryStatus.IN_PROGRESS), inProgress.single().statuses)
        val done = LibraryLogic.mergeSeries(emptyList(), summaries, mapOf("tv:tmdb:1" to 2))
        assertEquals(setOf(LibraryStatus.WATCHED), done.single().statuses)
        assertEquals("S1E2 · 2/10 episoade", LibraryLogic.seriesLabel(s, 10))
    }

    @Test
    fun markingAllAiredEpisodesCompletesASeriesWithFutureEpisodes() {
        val counts = SeriesMarker.airedCounts(
            listOf(SeasonSummary(0, "Speciale", 3, null), SeasonSummary(1, "Sezon 1", 3, null),
                SeasonSummary(2, "Sezon 2", 10, null), SeasonSummary(3, "Sezon 3", 6, null)),
            EpisodeAir(2, 2, null, null),
        )
        val previous = listOf(ep(1, 1, 1), ep(1, 2, 2), ep(1, 3, 3), ep(2, 1, 4))
        assertEquals(mapOf(1 to 3, 2 to 2), counts)
        assertEquals(4, LocalProgress.show(show, previous, counts, "local").completed)
        val marked = LocalProgress.show(show, previous + ep(2, 2, 5), counts, "local")
        assertEquals(marked.aired, marked.completed)
        assertEquals(0, LocalProgress.show(show, emptyList(), counts, "local").completed)
    }
}
