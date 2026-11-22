package ro.ak.vodunify.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.DiscoverFilters
import ro.ak.vodunify.core.catalog.DiscoverPeriod
import ro.ak.vodunify.core.catalog.DiscoverQuery
import ro.ak.vodunify.core.catalog.DiscoverService
import ro.ak.vodunify.core.catalog.DiscoverSort
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.MovieReleaseEvent
import ro.ak.vodunify.core.catalog.ReleaseChannel
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleReleases
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.ui.home.FollowedTitle
import ro.ak.vodunify.ui.home.HomeSections
import java.time.LocalDate

class HomeSectionsTest {
    private val today = LocalDate.of(2026, 11, 21)

    private fun item(id: Long, kind: TitleKind) = CatalogItem(id, kind, "T$id", null, "", null, null, null)

    private fun movie(id: Long, date: String?) = FollowedTitle(TitleReleases(item(id, TitleKind.MOVIE), date, null, null))

    private fun show(id: Long, last: EpisodeAir?, next: EpisodeAir?, seen: Pair<Int, Int>? = null) =
        FollowedTitle(TitleReleases(item(id, TitleKind.TV), null, last, next), seen)

    @Test fun newMoviesAreFromTheLastMonthNewestFirst() {
        val list = listOf(movie(1, "2026-11-01"), movie(2, "2026-11-20"), movie(3, "2026-09-01"), movie(4, "2026-12-05"), movie(5, null))
        assertEquals(listOf(2L, 1L), HomeSections.newMovies(list, today).map { it.tmdbId })
        assertEquals(listOf(4L), HomeSections.soonMovies(list, today).map { it.tmdbId })
    }

    private fun movieWith(id: Long, vararg r: MovieReleaseEvent) =
        FollowedTitle(TitleReleases(item(id, TitleKind.MOVIE), null, null, null, r.toList()))

    @Test fun romanianCinemaBeatsUs() {
        val t = movieWith(
            1,
            MovieReleaseEvent(ReleaseChannel.CINEMA, "US", "2026-11-26"),
            MovieReleaseEvent(ReleaseChannel.CINEMA, "RO", "2026-12-05"),
        ).releases
        assertEquals(listOf("RO"), HomeSections.homeReleases(t).map { it.region })
    }

    @Test fun movieMovesFromSoonToNewAndCanBeInBoth() {
        val m = movieWith(
            7,
            MovieReleaseEvent(ReleaseChannel.CINEMA, "RO", "2026-11-14"),
            MovieReleaseEvent(ReleaseChannel.DIGITAL, "US", "2026-12-09"),
            MovieReleaseEvent(ReleaseChannel.STREAMING, "US", "2027-01-20", serviceId = "netflix", serviceName = "Netflix"),
        )
        val new = HomeSections.newMovies(listOf(m), today)
        val soon = HomeSections.soonMovies(listOf(m), today)
        assertEquals(ReleaseChannel.CINEMA, new.single().release?.channel)
        // Streaming bate digitalul: „acasă” înseamnă Netflix.
        assertEquals(ReleaseChannel.STREAMING, soon.single().release?.channel)
        assertEquals("20 ian", soon.single().subtitle)
        // După data lansării în cinema, filmul trece în Noutăți.
        assertEquals(emptyList<Long>(), HomeSections.soonMovies(listOf(m), LocalDate.of(2027, 1, 21)).map { it.tmdbId })
    }

    @Test fun newEpisodesSkipWhatYouAlreadySaw() {
        val ep = EpisodeAir(2, 5, "X", "2026-11-18")
        val list = listOf(
            show(1, ep, null, seen = 2 to 4),
            show(2, ep, null, seen = 2 to 5),
            show(3, ep, null),
            show(4, EpisodeAir(1, 1, null, "2026-08-01"), null),
        )
        val out = HomeSections.newEpisodes(list, today)
        assertEquals(listOf(1L, 3L), out.map { it.tmdbId })
        assertEquals("S02E05 · 18 nov", out.first().subtitle)
    }

    @Test fun soonEpisodesInTheNextWeeks() {
        val list = listOf(
            show(1, null, EpisodeAir(3, 1, null, "2026-12-01")),
            show(2, null, EpisodeAir(1, 1, null, "2027-06-01")),
            show(3, null, EpisodeAir(1, 2, null, "2026-11-25")),
        )
        assertEquals(listOf(3L, 1L), HomeSections.soonEpisodes(list, today).map { it.tmdbId })
    }

    @Test fun discoverParamsUseTheServiceLibrary() {
        val f = DiscoverFilters(
            kind = TitleKind.TV,
            sort = DiscoverSort.NEWEST,
            genreId = 18,
            period = DiscoverPeriod("2026", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            service = DiscoverService("HBO Max", setOf(1899, 384), Market.US, owned = true),
            country = "KR",
        )
        val p = DiscoverQuery.params(f, 2, "ro-RO", today)
        assertEquals("first_air_date.desc", p["sort_by"])
        assertEquals("2026-01-01", p["first_air_date.gte"])
        assertEquals("2026-11-21", p["first_air_date.lte"])
        assertEquals("384|1899", p["with_watch_providers"])
        assertEquals("US", p["watch_region"])
        assertEquals("KR", p["with_origin_country"])
        assertEquals("18", p["with_genres"])
        assertEquals("2", p["page"])
        assertNull(DiscoverQuery.params(DiscoverFilters(), 1, "ro-RO", today)["watch_region"])
    }
}
