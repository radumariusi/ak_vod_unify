package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.anticipated.FixedAnticipatedSource
import ro.ak.vodunify.core.calendar.engine.EpisodeCalendarRepository
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.calendar.model.QualityThresholds
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class EpisodeCalendarRepositoryTest {
    private val server = MockWebServer()
    private val day = LocalDate.of(2026, 11, 20)

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                val body = when {
                    path.endsWith("/omdb/") -> when (url.queryParameter("i")) {
                        "tt4" -> """{"Response":"True","imdbRating":"8.0","imdbVotes":"50,123"}"""
                        else -> """{"Response":"False","Error":"Incorrect IMDb ID."}"""
                    }
                    path.endsWith("/discover/tv") && url.queryParameter("first_air_date.gte") != null -> EMPTY_PAGE
                    path.endsWith("/discover/tv") -> DISCOVER
                    path.endsWith("/tv/1") -> SHOW_NETFLIX
                    path.endsWith("/tv/1/season/2") -> SEASON_NETFLIX
                    path.endsWith("/tv/2") -> SHOW_HBO
                    path.endsWith("/tv/2/season/1") -> SEASON_HBO
                    path.endsWith("/tv/4") -> SHOW_LOW
                    path.endsWith("/tv/4/season/3") -> SEASON_LOW
                    path.endsWith("/tv/5") -> SHOW_NEW_HYPED
                    path.endsWith("/tv/5/season/1") -> SEASON_NEW_HYPED
                    path.endsWith("/tv/6") -> SHOW_NEW_UNKNOWN
                    path.endsWith("/tv/6/season/1") -> SEASON_NEW_UNKNOWN
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun repo(anticipated: Set<Int> = setOf(5)) = EpisodeCalendarRepository(
        apiFactory = { key -> testTmdbApi(server.url("/3/").toString(), key) },
        imdbRatings = OmdbClient(OkHttpClient(), baseUrl = server.url("/omdb/").toString()),
        anticipatedSource = FixedAnticipatedSource(anticipated),
        today = { day },
    )

    private fun settings(netflix: Region? = Region.US, max: Region? = Region.RO) = CalendarSettings().let { s ->
        var out = s
        if (netflix != null) out = out.updateApp("netflix") { it.copy(installed = true, region = netflix) }
        if (max != null) out = out.updateApp("max") { it.copy(installed = true, region = max) }
        out
    }

    @Test
    fun `builds today's calendar in Romania time with app and region`() = runTest {
        val entries = repo().episodesBetween(day, day, settings(), "test-key").entries

        // The hyped new show (US broadcast, no app, so its original 04:00), the HBO episode
        // from the 19th US evening (04:00 on the 20th) and the Netflix drop on the 20th (10:00).
        assertEquals(
            listOf("New Hyped" to 2, "Show HBO" to 3, "Show Netflix" to 5),
            entries.map { it.showName to it.episodeNumber },
        )

        val hbo = entries[1]
        assertEquals(LocalDateTime.of(2026, 11, 20, 4, 0), hbo.releaseAt.toLocalDateTime())
        assertEquals("max", hbo.watchOn?.id)
        assertEquals(Region.RO, hbo.watchRegion)

        val netflix = entries[2]
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), netflix.releaseAt.toLocalDateTime())
        assertEquals("netflix", netflix.watchOn?.id)
        assertEquals(Region.US, netflix.watchRegion)
        assertEquals("tt123", netflix.imdbId)
        assertEquals("Netflix", netflix.networkNames.single())
        assertEquals(52, netflix.runtimeMinutes)

        val apiKeys = (1..server.requestCount).mapNotNull { server.takeRequest().requestUrl!!.queryParameter("api_key") }
        assertEquals(setOf("test-key"), apiKeys.toSet())
    }

    @Test
    fun `quality gate rejects low rated returning shows and unknown new shows`() = runTest {
        val result = repo().episodesBetween(day, day, settings(), "k")
        val rejected = result.rejected.associate { it.showName to it.verdict }
        assertEquals(QualityVerdict.REJECT_LOW_RATING, rejected["Low Rated"])
        assertEquals(QualityVerdict.REJECT_NOT_ANTICIPATED, rejected["New Unknown"])
        assertEquals(QualityVerdict.PASS_ANTICIPATED, result.entries.first { it.showName == "New Hyped" }.verdict)
        assertEquals(1, result.hiddenByCategory) // the anime
    }

    @Test
    fun `IMDb rating from OMDb wins over TMDB`() = runTest {
        val result = repo().episodesBetween(day, day, settings(), "k", omdbKey = "omdb")
        val low = result.entries.first { it.showName == "Low Rated" }
        assertEquals(8.0, low.imdb!!.rating, 0.0)
        assertEquals(50_123, low.imdb!!.votes)
        assertTrue(result.usedImdb)
    }

    @Test
    fun `new shows pass when the anticipated switch is off`() = runTest {
        val s = settings().copy(thresholds = QualityThresholds(newShowsOnlyIfAnticipated = false))
        val result = repo().episodesBetween(day, day, s, "k")
        assertEquals(QualityVerdict.PASS_NEW_ALLOWED, result.entries.first { it.showName == "New Unknown" }.verdict)
    }

    @Test
    fun `followed shows always pass and hidden shows never appear`() = runTest {
        val s = settings()
            .follow(SavedShow(4, "Low Rated"))
            .hide(SavedShow(1, "Show Netflix"))
        val result = repo().episodesBetween(day, day, s, "k")
        assertEquals(QualityVerdict.PASS_FOLLOWED, result.entries.first { it.showName == "Low Rated" }.verdict)
        assertTrue(result.entries.none { it.showName == "Show Netflix" })
        assertEquals(1, result.hiddenByUser)
    }

    @Test
    fun `only my apps keeps episodes on installed apps`() = runTest {
        val result = repo().episodesBetween(day, day, settings().copy(onlyInstalledApps = true), "k")
        assertEquals(setOf("Show HBO", "Show Netflix"), result.entries.map { it.showName }.toSet())
        assertTrue(result.notOnMyApps > 0)
    }

    @Test
    fun `week view spans several days`() = runTest {
        val result = repo().episodesBetween(day.minusDays(7), day.plusDays(7), settings(), "k")
        val days = result.byDay()
        assertEquals(listOf(4, 5, 6), result.entries.filter { it.showName == "Show Netflix" }.map { it.episodeNumber })
        assertTrue(days.keys.all { !it.isBefore(day.minusDays(7)) && !it.isAfter(day.plusDays(7)) })
    }

    @Test
    fun `show only on a region the user does not use has no app`() = runTest {
        // Max is set to US but the show is only on Max RO.
        val entries = repo().episodesBetween(day, day, settings(max = Region.US), "k").entries
        val hbo = entries.first { it.showName == "Show HBO" }
        // Falls back to original network match (HBO network 49 belongs to Max).
        assertEquals("max", hbo.watchOn?.id)
        assertEquals(Region.US, hbo.watchRegion)

        val noApps = repo().episodesBetween(day, day, CalendarSettings(), "k").entries
        assertNull(noApps.first { it.showName == "Show HBO" }.watchOn)
    }

    @Test
    fun `hidden categories are dropped`() = runTest {
        val result = repo().episodesBetween(day, day, CalendarSettings(hiddenCategories = setOf(ContentFilter.ANIME)), "k")
        assertEquals(false, (result.entries + result.rejected).any { it.showName == "Anime" })
    }

    companion object {
        const val EMPTY_PAGE = """{"page":1,"total_pages":1,"total_results":0,"results":[]}"""

        val DISCOVER = """
            {"page":1,"total_pages":1,"total_results":6,"results":[
              {"id":1,"name":"Show Netflix","original_language":"en","origin_country":["US"],"genre_ids":[18]},
              {"id":2,"name":"Show HBO","original_language":"en","origin_country":["US"],"genre_ids":[18]},
              {"id":3,"name":"Anime","original_language":"ja","origin_country":["JP"],"genre_ids":[16]},
              {"id":4,"name":"Low Rated","original_language":"en","origin_country":["US"],"genre_ids":[35]},
              {"id":5,"name":"New Hyped","original_language":"en","origin_country":["US"],"genre_ids":[18]},
              {"id":6,"name":"New Unknown","original_language":"en","origin_country":["US"],"genre_ids":[18]}
            ]}
        """.trimIndent()

        val SHOW_NETFLIX = """
            {"id":1,"name":"Show Netflix","original_language":"en","origin_country":["US"],
             "genres":[{"id":18,"name":"Drama"}],"networks":[{"id":213,"name":"Netflix"}],
             "vote_average":8.1,"vote_count":1200,"poster_path":"/p.jpg","episode_run_time":[52],
             "last_episode_to_air":{"air_date":"2026-11-13","episode_number":4,"season_number":2},
             "next_episode_to_air":{"air_date":"2026-11-20","episode_number":5,"season_number":2},
             "watch/providers":{"results":{"US":{"flatrate":[{"provider_id":8,"provider_name":"Netflix"}]},
                                           "RO":{"flatrate":[{"provider_id":8,"provider_name":"Netflix"}]}}},
             "external_ids":{"imdb_id":"tt123"}}
        """.trimIndent()

        val SEASON_NETFLIX = """
            {"season_number":2,"episodes":[
              {"air_date":"2026-11-13","episode_number":4,"season_number":2,"name":"E4"},
              {"air_date":"2026-11-20","episode_number":5,"season_number":2,"name":"E5"},
              {"air_date":"2026-11-27","episode_number":6,"season_number":2,"name":"E6"}
            ]}
        """.trimIndent()

        val SHOW_HBO = """
            {"id":2,"name":"Show HBO","original_language":"en","origin_country":["US"],
             "genres":[{"id":18,"name":"Drama"}],"networks":[{"id":49,"name":"HBO"}],
             "vote_average":8.5,"vote_count":3000,
             "last_episode_to_air":{"air_date":"2026-11-19","episode_number":3,"season_number":1},
             "next_episode_to_air":{"air_date":"2026-11-26","episode_number":4,"season_number":1},
             "watch/providers":{"results":{"RO":{"flatrate":[{"provider_id":1899,"provider_name":"Max"}]}}}}
        """.trimIndent()

        val SEASON_HBO = """
            {"season_number":1,"episodes":[
              {"air_date":"2026-11-12","episode_number":2,"season_number":1},
              {"air_date":"2026-11-19","episode_number":3,"season_number":1},
              {"air_date":"2026-11-26","episode_number":4,"season_number":1}
            ]}
        """.trimIndent()

        val SHOW_LOW = """
            {"id":4,"name":"Low Rated","original_language":"en","origin_country":["US"],
             "genres":[{"id":35,"name":"Comedy"}],"networks":[{"id":2,"name":"ABC"}],
             "vote_average":5.9,"vote_count":900,
             "last_episode_to_air":{"air_date":"2026-11-19","episode_number":7,"season_number":3},
             "external_ids":{"imdb_id":"tt4"}}
        """.trimIndent()

        val SEASON_LOW = """
            {"season_number":3,"episodes":[{"air_date":"2026-11-19","episode_number":7,"season_number":3}]}
        """.trimIndent()

        val SHOW_NEW_HYPED = """
            {"id":5,"name":"New Hyped","original_language":"en","origin_country":["US"],
             "genres":[{"id":18,"name":"Drama"}],"networks":[{"id":6,"name":"NBC"}],
             "vote_average":0.0,"vote_count":0,
             "last_episode_to_air":{"air_date":"2026-11-19","episode_number":2,"season_number":1}}
        """.trimIndent()

        val SEASON_NEW_HYPED = """
            {"season_number":1,"episodes":[{"air_date":"2026-11-19","episode_number":2,"season_number":1}]}
        """.trimIndent()

        val SHOW_NEW_UNKNOWN = """
            {"id":6,"name":"New Unknown","original_language":"en","origin_country":["US"],
             "genres":[{"id":18,"name":"Drama"}],"networks":[{"id":6,"name":"NBC"}],
             "vote_average":6.0,"vote_count":10,
             "last_episode_to_air":{"air_date":"2026-11-19","episode_number":3,"season_number":1}}
        """.trimIndent()

        val SEASON_NEW_UNKNOWN = """
            {"season_number":1,"episodes":[{"air_date":"2026-11-19","episode_number":3,"season_number":1}]}
        """.trimIndent()
    }
}
