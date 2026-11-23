package ro.ak.vodunify.library

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.tracking.SeriesSummary
import ro.ak.vodunify.core.playback.PosterStage
import ro.ak.vodunify.core.reconcile.reconcile
import ro.ak.vodunify.ui.library.LibraryFilter
import ro.ak.vodunify.ui.library.LibraryLogic
import ro.ak.vodunify.ui.library.LibraryTab
import java.time.Instant

class LibraryLogicTest {
    private val matrix = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603), "The Matrix", 1999)
    private val dune = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 438631), "Dune", 2021)
    private val got = MediaRef(MediaType.SHOW, ExternalIds(tmdb = 1399), "GoT", 2011)

    private val entries = reconcile(
        resume = listOf(TrackingEntities.resume(ResumePoint(got.copy(type = MediaType.EPISODE, season = 1, episode = 2), null, Instant.ofEpochMilli(5000), "simkl"))),
        latestWatches = emptyList(),
        watched = listOf(TrackingEntities.watched(WatchEvent(matrix, Instant.ofEpochMilli(3000), "trakt"))),
        list = listOf(
            TrackingEntities.watchlist(WatchlistEntry(dune, Instant.ofEpochMilli(4000), "wetrakr")),
            TrackingEntities.watchlist(WatchlistEntry(dune, Instant.ofEpochMilli(4000), "trakt")),
        ),
        next = emptyList(),
        own = emptyList(),
    ).library

    @Test fun filterBySourceAndTab() {
        assertEquals(3, LibraryLogic.filter(entries, LibraryFilter()).size)
        assertEquals(listOf("tv:tmdb:1399"), LibraryLogic.filter(entries, LibraryFilter(source = "simkl")).map { it.titleKey })
        assertEquals(2, LibraryLogic.filter(entries, LibraryFilter(source = "trakt")).size)
        assertEquals(listOf("movie:tmdb:438631"), LibraryLogic.filter(entries, LibraryFilter(LibraryTab.WATCHLIST, "wetrakr")).map { it.titleKey })
        assertEquals(0, LibraryLogic.filter(entries, LibraryFilter(LibraryTab.WATCHED, "wetrakr")).size)
    }

    @Test fun countsAndSubtitles() {
        val counts = LibraryLogic.countsBySource(entries, LibraryTab.ALL)
        assertEquals(3, counts[null])
        assertEquals(2, counts["trakt"])
        val gotEntry = entries.first { it.titleKey == "tv:tmdb:1399" }
        assertEquals("S01E02", LibraryLogic.subtitle(gotEntry, LibraryTab.IN_PROGRESS))
    }
    @Test fun watchedSeriesOnlyShowsEpisodeCount() {
        val summary = SeriesSummary("Serial", 1399, null, 36, 3, 12, 0L)
        assertEquals("36/36", LibraryLogic.seriesLabel(summary, 36, PosterStage.WATCHED))
        assertEquals("S3E12 · 36/40 episoade", LibraryLogic.seriesLabel(summary, 40, PosterStage.IN_PROGRESS))
    }

    @Test fun posterUsesTheExplicitRowStatusAndFallsBackToTheMostActiveStatusInAll() {
        val entry = entries.first { it.titleKey == "tv:tmdb:1399" }
        assertEquals(PosterStage.IN_PROGRESS, LibraryLogic.posterStage(entry, LibraryTab.ALL))
        assertEquals(PosterStage.WATCHED, LibraryLogic.posterStage(entry, LibraryTab.WATCHED))
        assertEquals(PosterStage.UNWATCHED, LibraryLogic.posterStage(entry, LibraryTab.WATCHLIST))
    }

}
