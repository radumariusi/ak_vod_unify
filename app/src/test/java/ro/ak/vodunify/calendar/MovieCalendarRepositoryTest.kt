package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.anticipated.FixedAnticipatedMoviesSource
import ro.ak.vodunify.core.calendar.engine.MovieCalendarRepository
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.SavedShow
import ro.ak.vodunify.core.calendar.omdb.OmdbClient
import ro.ak.vodunify.core.calendar.quality.QualityVerdict
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class MovieCalendarRepositoryTest {
    private val server = MockWebServer()
    private val day = LocalDate.of(2026, 11, 20)
    private val discoverQueries = mutableListOf<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                val body = when {
                    path.endsWith("/omdb/") -> when (url.queryParameter("i")) {
                        "tt30" -> """{"Response":"True","imdbRating":"7.9","imdbVotes":"120,000"}"""
                        else -> """{"Response":"False","Error":"Incorrect IMDb ID."}"""
                    }
                    path.endsWith("/discover/movie") -> {
                        val region = url.queryParameter("region")
                        val types = url.queryParameter("with_release_type")
                        synchronized(discoverQueries) { discoverQueries += "$region:$types" }
                        when ("$region:$types") {
                            "US:2|3" -> page(10, 40, 70)
                            "RO:2|3" -> page(20)
                            "US:4" -> page(30, 40, 50, 60)
                            "RO:4" -> page()
                            else -> page()
                        }
                    }
                    path.endsWith("/movie/10") -> CINEMA_HYPED
                    path.endsWith("/movie/20") -> CINEMA_RO_ONLY
                    path.endsWith("/movie/30") -> DIGITAL_GOOD
                    path.endsWith("/movie/40") -> NETFLIX_ORIGINAL
                    path.endsWith("/movie/50") -> DIGITAL_BAD
                    path.endsWith("/movie/60") -> INDIAN
                    path.endsWith("/movie/70") -> CINEMA_UNKNOWN
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo(anticipated: Set<Int> = setOf(10, 40)) = MovieCalendarRepository(
        apiFactory = { key -> testTmdbApi(server.url("/3/").toString(), key) },
        imdbRatings = OmdbClient(OkHttpClient(), baseUrl = server.url("/omdb/").toString()),
        anticipatedSource = FixedAnticipatedMoviesSource(anticipated),
        today = { day },
    )

    private fun settings(netflix: Region? = Region.RO) = CalendarSettings().let { s ->
        if (netflix == null) s else s.updateApp("netflix") { it.copy(installed = true, region = netflix) }
    }

    @Test
    fun `builds the day's movie releases by kind, in Romania time`() = runTest {
        val result = repo().moviesBetween(day, day, settings(), "k")
        val shown = result.entries.map { it.title to it.kind }
        assertEquals(
            listOf(
                "Digital Good" to MovieReleaseKind.DIGITAL,
                "Netflix Original" to MovieReleaseKind.STREAMING,
                "Cinema Hyped" to MovieReleaseKind.CINEMA_US,
                "Cinema RO" to MovieReleaseKind.CINEMA_RO,
            ),
            shown,
        )

        val digital = result.entries[0]
        assertEquals(LocalDateTime.of(2026, 11, 20, 7, 0), digital.releaseAt.toLocalDateTime())
        assertTrue(digital.hasTime)

        val netflix = result.entries[1]
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), netflix.releaseAt.toLocalDateTime())
        assertEquals("netflix", netflix.watchOn?.id)
        assertEquals(Region.RO, netflix.watchRegion)
        assertEquals(QualityVerdict.PASS_ANTICIPATED, netflix.verdict)

        val cinema = result.entries[2]
        assertFalse(cinema.hasTime)
        assertEquals(3, cinema.releases.size) // cinema US, cinema RO and digital US, for the details screen
    }

    @Test
    fun `quality gate and category filters`() = runTest {
        val result = repo().moviesBetween(day, day, settings(), "k")
        val rejected = result.rejected.associate { it.title to it.verdict }
        assertEquals(QualityVerdict.REJECT_LOW_RATING, rejected["Digital Bad"])
        assertEquals(QualityVerdict.REJECT_NOT_ANTICIPATED, rejected["Cinema Unknown"])
        assertEquals(1, result.hiddenByCategory) // the Indian movie, found from its details
        assertTrue(result.entries.none { it.title == "Indian Movie" })
    }

    @Test
    fun `IMDb from OMDb is used when there is a key`() = runTest {
        val result = repo().moviesBetween(day, day, settings(), "k", omdbKey = "o")
        val good = result.entries.first { it.title == "Digital Good" }
        assertEquals(7.9, good.imdb!!.rating, 0.0)
        assertTrue(result.usedImdb)
    }

    @Test
    fun `followed and hidden movies`() = runTest {
        val s = settings().followMovie(SavedShow(50, "Digital Bad")).hideMovie(SavedShow(30, "Digital Good"))
        val result = repo().moviesBetween(day, day, s, "k")
        assertEquals(QualityVerdict.PASS_FOLLOWED, result.entries.first { it.title == "Digital Bad" }.verdict)
        assertTrue(result.entries.none { it.title == "Digital Good" })
        assertEquals(1, result.hiddenByUser)
    }

    @Test
    fun `only my apps drops streaming on other apps but keeps cinema and digital`() = runTest {
        val result = repo().moviesBetween(day, day, settings(netflix = null).copy(onlyInstalledApps = true), "k")
        assertTrue(result.entries.none { it.kind == MovieReleaseKind.STREAMING })
        assertEquals(1, result.notOnMyApps)
        assertTrue(result.entries.any { it.kind == MovieReleaseKind.CINEMA_US })
    }

    @Test
    fun `turned off kinds are neither asked for nor shown`() = runTest {
        val s = settings(netflix = null).copy(movieKinds = setOf(MovieReleaseKind.CINEMA_RO))
        val result = repo().moviesBetween(day, day, s, "k")
        assertEquals(listOf("Cinema RO"), result.entries.map { it.title })
        assertEquals(listOf("RO:2|3"), discoverQueries)
    }

    @Test
    fun `hidden categories are dropped before fetching details`() = runTest {
        val s = settings().copy(hiddenCategories = setOf(ContentFilter.DOCUMENTARY))
        val result = repo().moviesBetween(day, day, s, "k")
        // Movie 70 is a documentary in discover: dropped without asking for its details.
        assertTrue(result.rejected.none { it.title == "Cinema Unknown" })
        assertEquals(1, result.hiddenByCategory)
    }

    companion object {
        private val GENRES = mapOf(10 to "[28]", 20 to "[18]", 30 to "[18]", 40 to "[53]", 50 to "[35]", 60 to "[18]", 70 to "[99]")
        private val LANGS = emptyMap<Int, String>() // the Indian movie only shows as Indian in its details

        fun page(vararg ids: Int) = """{"page":1,"total_pages":1,"total_results":${ids.size},"results":[${
            ids.joinToString(",") { """{"id":$it,"title":"m$it","original_language":"${LANGS[it] ?: "en"}","genre_ids":${GENRES[it]},"popularity":${100 - it}}""" }
        }]}"""

        private fun details(
            id: Int,
            title: String,
            releases: String,
            votes: Int,
            rating: Double,
            extra: String = "",
        ) = """{"id":$id,"title":"$title","original_title":"$title","original_language":"en","origin_country":["US"],
            "genres":[{"id":18,"name":"Drama"}],"runtime":110,"vote_average":$rating,"vote_count":$votes,"popularity":${100 - id},
            "imdb_id":"tt$id","release_dates":{"results":[$releases]}$extra}"""

        private fun country(code: String, vararg dates: Pair<Int, String>, note: String = "") =
            """{"iso_3166_1":"$code","release_dates":[${dates.joinToString(",") { (t, d) -> """{"type":$t,"release_date":"${d}T00:00:00.000Z","note":"$note"}""" }}]}"""

        val CINEMA_HYPED = details(10, "Cinema Hyped", listOf(
            country("US", 3 to "2026-11-20", 4 to "2027-01-10"),
            country("RO", 3 to "2026-11-27"),
        ).joinToString(","), votes = 12, rating = 8.0)

        val CINEMA_RO_ONLY = details(20, "Cinema RO", country("RO", 3 to "2026-11-20"), votes = 900, rating = 7.4)

        val DIGITAL_GOOD = details(30, "Digital Good", listOf(
            country("US", 3 to "2026-09-01", 4 to "2026-11-20"),
        ).joinToString(","), votes = 1500, rating = 7.2)

        val NETFLIX_ORIGINAL = details(40, "Netflix Original", country("US", 4 to "2026-11-20", note = "Netflix"), votes = 3, rating = 0.0)

        val DIGITAL_BAD = details(50, "Digital Bad", country("US", 3 to "2026-08-01", 4 to "2026-11-20"), votes = 800, rating = 5.1)

        val INDIAN = details(60, "Indian Movie", country("US", 4 to "2026-11-20"), votes = 900, rating = 7.5)
            .replace("\"original_language\":\"en\",\"origin_country\":[\"US\"]", "\"original_language\":\"hi\",\"origin_country\":[\"IN\"]")

        val CINEMA_UNKNOWN = details(70, "Cinema Unknown", country("US", 3 to "2026-11-20"), votes = 0, rating = 0.0)
    }
}
