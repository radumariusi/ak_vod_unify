package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.engine.MovieReleasePlanner
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import ro.ak.vodunify.core.calendar.tmdb.CountryReleaseDatesDto
import ro.ak.vodunify.core.calendar.tmdb.MovieDetailsDto
import ro.ak.vodunify.core.calendar.tmdb.ProviderDto
import ro.ak.vodunify.core.calendar.tmdb.RegionProvidersDto
import ro.ak.vodunify.core.calendar.tmdb.ReleaseDateDto
import ro.ak.vodunify.core.calendar.tmdb.ReleaseDatesDto
import ro.ak.vodunify.core.calendar.tmdb.WatchProvidersDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MovieReleasePlannerTest {

    private fun rd(type: Int, date: String, note: String? = null) =
        ReleaseDateDto(releaseDate = "${date}T00:00:00.000Z", type = type, note = note)

    private fun movie(
        releases: Map<String, List<ReleaseDateDto>>,
        flatrate: Map<String, List<Int>> = emptyMap(),
    ) = MovieDetailsDto(
        id = 1,
        title = "Movie",
        releaseDates = ReleaseDatesDto(releases.map { (c, list) -> CountryReleaseDatesDto(c, list) }),
        watchProviders = WatchProvidersDto(
            results = flatrate.mapValues { (_, ids) -> RegionProvidersDto(flatrate = ids.map { ProviderDto(it) }) },
        ),
    )

    private fun settings(vararg apps: Pair<String, Region>) = apps.fold(CalendarSettings()) { s, (id, region) ->
        s.updateApp(id) { it.copy(installed = true, region = region) }
    }

    @Test
    fun `theatrical movie has cinema US, cinema RO and a US digital release`() {
        val details = movie(
            mapOf(
                "US" to listOf(rd(3, "2026-11-21"), rd(4, "2027-01-06")),
                "RO" to listOf(rd(3, "2026-11-28")),
            ),
        )
        val releases = MovieReleasePlanner.releases(details, settings())
        assertEquals(
            listOf(
                Triple(MovieReleaseKind.CINEMA_US, Region.US, LocalDate.of(2026, 11, 21)),
                Triple(MovieReleaseKind.CINEMA_RO, Region.RO, LocalDate.of(2026, 11, 28)),
                Triple(MovieReleaseKind.DIGITAL, Region.US, LocalDate.of(2027, 1, 6)),
            ),
            releases.map { Triple(it.kind, it.region, it.date) },
        )
    }

    @Test
    fun `limited cinema release only counts when there is no wide one`() {
        val limitedOnly = MovieReleasePlanner.releases(movie(mapOf("US" to listOf(rd(2, "2026-12-05")))), settings())
        assertTrue(limitedOnly.single().limited)
        val both = MovieReleasePlanner.releases(movie(mapOf("US" to listOf(rd(2, "2026-12-05"), rd(3, "2026-12-25")))), settings())
        assertEquals(LocalDate.of(2026, 12, 25), both.single().date)
        assertFalse(both.single().limited)
    }

    @Test
    fun `digital release with a service note is a streaming premiere`() {
        val details = movie(mapOf("US" to listOf(rd(4, "2026-11-20", "Netflix"))))
        val r = MovieReleasePlanner.releases(details, settings()).single()
        assertEquals(MovieReleaseKind.STREAMING, r.kind)
        assertEquals("netflix", r.service?.id)
        assertFalse(r.onMyApp)
    }

    @Test
    fun `a worldwide original lands on the app set to RO with the US date`() {
        val details = movie(mapOf("US" to listOf(rd(4, "2026-11-20", "Netflix"))))
        val releases = MovieReleasePlanner.releases(details, settings("netflix" to Region.RO))
        val ro = releases.single { it.region == Region.RO }
        assertEquals(MovieReleaseKind.STREAMING, ro.kind)
        assertEquals(LocalDate.of(2026, 11, 20), ro.date)
        assertTrue(ro.onMyApp)

        val shown = MovieReleasePlanner.calendarReleases(releases, settings("netflix" to Region.RO))
        assertEquals(Region.RO, shown.single().region)
    }

    @Test
    fun `streaming-first movie is recognized from providers, theatrical movie is not`() {
        val streamingFirst = movie(mapOf("US" to listOf(rd(4, "2026-11-20"))), flatrate = mapOf("US" to listOf(337)))
        val r = MovieReleasePlanner.releases(streamingFirst, settings("disney" to Region.US)).single()
        assertEquals(MovieReleaseKind.STREAMING, r.kind)
        assertEquals("disney", r.service?.id)
        assertTrue(r.onMyApp)

        // Out in cinemas first: the digital date is rent/buy, even if it's on Disney+ by now.
        val theatrical = movie(
            mapOf("US" to listOf(rd(3, "2026-08-01"), rd(4, "2026-11-20"))),
            flatrate = mapOf("US" to listOf(337)),
        )
        val kinds = MovieReleasePlanner.releases(theatrical, settings("disney" to Region.US)).map { it.kind }
        assertEquals(listOf(MovieReleaseKind.CINEMA_US, MovieReleaseKind.DIGITAL), kinds)
    }

    @Test
    fun `release moments in Romania time`() {
        val netflix = StreamingCatalog.byId("netflix")
        val streaming = MovieReleasePlanner.releases(movie(mapOf("US" to listOf(rd(4, "2026-11-20", "Netflix")))), settings()).single()
        assertEquals(netflix, streaming.service)
        // 00:00 Pacific = 10:00 in Romania in November.
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), MovieReleasePlanner.moment(streaming).first.toLocalDateTime())

        val digital = MovieReleasePlanner.releases(movie(mapOf("US" to listOf(rd(4, "2026-11-20")))), settings()).single()
        // 00:00 Eastern = 07:00 in Romania.
        assertEquals(LocalDateTime.of(2026, 11, 20, 7, 0), MovieReleasePlanner.moment(digital).first.toLocalDateTime())

        val cinema = MovieReleasePlanner.releases(movie(mapOf("RO" to listOf(rd(3, "2026-11-27")))), settings()).single()
        val (at, hasTime) = MovieReleasePlanner.moment(cinema)
        assertEquals(LocalDate.of(2026, 11, 27), at.toLocalDate())
        assertFalse(hasTime)
    }

    @Test
    fun `calendar only keeps the kinds turned on`() {
        val details = movie(mapOf("US" to listOf(rd(3, "2026-11-21"), rd(4, "2027-01-06"))))
        val s = settings().copy(movieKinds = setOf(MovieReleaseKind.DIGITAL))
        val shown = MovieReleasePlanner.calendarReleases(MovieReleasePlanner.releases(details, s), s)
        assertEquals(MovieReleaseKind.DIGITAL, shown.single().kind)
    }

    @Test
    fun `service notes match whole words only`() {
        val max = StreamingCatalog.byId("max")!!
        assertTrue(max.matchesNote("HBO Max"))
        assertTrue(max.matchesNote("Max"))
        assertFalse(max.matchesNote("IMAX"))
        assertTrue(StreamingCatalog.byId("disney")!!.matchesNote("Disney Plus"))
        assertNull(StreamingCatalog.all.firstOrNull { it.matchesNote("Digital") })
    }
}
