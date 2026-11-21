package ro.ak.vodunify.calendar

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.calendar.cinema.CinemaCityRoSource
import ro.ak.vodunify.core.calendar.cinema.OrangeFilmSource
import ro.ak.vodunify.core.calendar.cinema.RoCinemaProgram
import ro.ak.vodunify.core.calendar.cinema.RoCinemaRelease
import java.time.LocalDate

class RoCinemaProgramTest {
    private val today = LocalDate.of(2026, 11, 21)

    @Test
    fun `Cinema City film list`() {
        val films = CinemaCityRoSource.parse(CINEMA_CITY)
        assertEquals(listOf("Club kid", "Jocurile foamei: Răsăritul în ziua extra", "Avengers: Doomsday", "Avengers: Doomsday dub"), films.map { it.title })
        assertEquals(LocalDate.of(2026, 12, 18), films[2].date)
        assertEquals(2026, films[2].year)
        assertTrue(films[1].truncated)
        assertTrue(films.none { it.title.contains("Dune") }) // re-release of a 2021 film
    }

    @Test
    fun `Orange Film city page`() {
        // The last film runs into the site's 2015 sample block; the source drops dates that old.
        val films = OrangeFilmSource.parse(ORANGE).filter { it.date.year > 2020 }
        assertEquals(3, films.size)
        val rocky = films.first { it.title.startsWith("I Play Rocky") }
        assertEquals("I Play Rocky", rocky.originalTitle)
        assertEquals(LocalDate.of(2026, 11, 20), rocky.date)
        assertEquals("Cum să spargi o bancă", films[1].title)
        assertEquals("How to Rob a Bank", films[1].originalTitle)
        assertNull(films[0].originalTitle)
    }

    @Test
    fun `program merges sources and finds TMDb movies by any title`() = runTest {
        val program = RoCinemaProgram.merge(
            CinemaCityRoSource.parse(CINEMA_CITY) + OrangeFilmSource.parse(ORANGE),
        )
        // Cinema City's cut title and its dubbed copy are the same film.
        assertEquals(1, program.count { it.title.startsWith("Avengers") })
        val hunger = RoCinemaProgram.find(program, listOf("The Hunger Games: Sunrise on the Reaping", "Jocurile foamei: Răsăritul în ziua extragerii"), 2026)
        assertNotNull(hunger)
        assertEquals(LocalDate.of(2026, 11, 20), hunger!!.date)
        // Orange gives the original title: an English-only TMDb title still matches.
        assertNotNull(RoCinemaProgram.find(program, listOf("How to Rob a Bank"), 2026))
        // Same title, film from 1990: not this premiere.
        assertNull(RoCinemaProgram.find(program, listOf("Club Kid"), 1990))
        assertNull(RoCinemaProgram.find(program, listOf("Club"), 2026))
    }

    @Test
    fun `normalize drops diacritics, punctuation and format words`() {
        assertEquals("ebenezer o poveste de craciun", RoCinemaProgram.normalize("Ebenezer:O poveste de Crăciun dub"))
        assertEquals("cum sa spargi o banca", RoCinemaProgram.normalize("Cum să spargi o bancă (3D)"))
        assertEquals("ramayana inceputul legendei", RoCinemaProgram.normalize("Ramayana: Începutul legendei"))
        assertEquals("Avengers: Doomsday", RoCinemaProgram.searchQuery("Avengers: Doomsday dub"))
    }

    @Test
    fun `a failing source does not stop the others`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("/films/until/2027-03-21") -> MockResponse().setBody(CINEMA_CITY)
                else -> MockResponse().setResponseCode(503)
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val program = RoCinemaProgram(
                listOf(
                    CinemaCityRoSource(http, server.url("/cc/").toString()),
                    OrangeFilmSource(http, baseUrl = server.url("/orange/").toString()),
                ),
            ).load(today)
            assertEquals(3, program.size)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `both sources together, old dates dropped`() = runTest {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("/films/until/") -> MockResponse().setBody(CINEMA_CITY)
                request.path!!.endsWith("/acum-la-cinema/Bucuresti") -> MockResponse().setBody(ORANGE)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val http = OkHttpClient()
            val program = RoCinemaProgram(
                listOf(
                    CinemaCityRoSource(http, server.url("/cc/").toString()),
                    OrangeFilmSource(http, baseUrl = server.url("/orange/").toString()),
                ),
            ).load(today)
            // Club Kid (both), Jocurile foamei, Avengers, Cum să spargi o bancă, I Play Rocky; not Nașul II (2015 sample date).
            assertEquals(5, program.size)
            assertTrue(program.none { it.title.startsWith("Na") })
        } finally {
            server.shutdown()
        }
    }

    companion object {
        val CINEMA_CITY = """{"body":{"films":[
            {"id":"8528s2r","name":"Club kid","length":122,"link":"https://www.cinemacity.ro/films/club-kid/8528s2r","releaseYear":"2026","releaseDate":"2026-11-20T00:00:00","attributeIds":["comedy"]},
            {"id":"8530s2r","name":"Jocurile foamei: Răsăritul în ziua extra","releaseYear":"2026","releaseDate":"2026-11-20T00:00:00"},
            {"id":"7001s2r","name":"Dune re-release","releaseYear":"2021","releaseDate":"2026-11-27T00:00:00"},
            {"id":"8600s2r","name":"Avengers: Doomsday","releaseYear":"2026","releaseDate":"2026-12-18T00:00:00"},
            {"id":"8601s2r","name":"Avengers: Doomsday dub","releaseYear":"2026","releaseDate":"2026-12-18T00:00:00"},
            {"id":"x","name":"","releaseDate":null}
        ]}}"""

        /** Shaped like orange.ro/info/film/acum-la-cinema/<oraș>: title link, original in brackets, "Premiera." and the date. */
        val ORANGE = """
            <div class="film"><a href="https://www.orange.ro/info/film/info/club-kid/14623/Bucuresti"><img alt="film Club Kid" src="x.jpg"></a>
              <h3><a href="https://www.orange.ro/info/film/info/club-kid/14623/Bucuresti">Club Kid</a></h3>
              <p><strong>Premiera.</strong></p><p>20.11.2026</p><p><strong>Director.</strong></p>
              <a href="https://www.orange.ro/info/film/cinema/16">Cinema City Mega Mall</a><ul><li>19:00 2D, SUB</li></ul></div>
            <div class="film"><a href="/info/film/info/cum-sa-spargi-o-banca/14598/Bucuresti"></a>
              <h3><a href="/info/film/info/cum-sa-spargi-o-banca/14598/Bucuresti">Cum s&#259; spargi o banc&#259;</a></h3>
              <p>(How to Rob a Bank)</p>
              <p><strong>Premiera.</strong></p><p>13.11.2026</p></div>
            <div class="film"><h3><a href="/info/film/info/i-play-rocky/14624/Bucuresti">I Play Rocky: Povestea din spatele legendei</a></h3>
              <p>(I Play Rocky)</p><span>Premiera.</span> <span>20.11.2026</span>
              <a href="https://www.orange.ro/info/film/cinema/26">Cineplexx Baneasa</a></div>
            <div class="film"><h3><a href="/info/film/info/nasul-ii/5528/Bucuresti">Nașul II</a></h3><p>(The Godfather: Part II)</p></div>
            <div class="sample"><h3>Star Wars: The Force Awakens</h3><p>Premiera.</p><p>03.07.2015</p></div>
        """.trimIndent()
    }
}
