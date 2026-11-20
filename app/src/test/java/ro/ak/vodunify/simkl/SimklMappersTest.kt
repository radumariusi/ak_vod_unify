package ro.ak.vodunify.simkl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.simkl.SimklMappers
import ro.ak.vodunify.core.simkl.SimklMappers.Kind
import java.time.Instant

class SimklMappersTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val show = obj(
        """
        {"status":"watching","last_watched_at":"2026-11-01T20:00:00Z","next_to_watch":"S01E03",
         "watched_episodes_count":2,"total_episodes_count":10,"not_aired_episodes_count":2,
         "show":{"title":"Charmed","year":1998,"ids":{"simkl":297,"slug":"charmed","imdb":"tt0158552","tvdb":"70626","tmdb":"1981"}},
         "seasons":[{"number":1,"episodes":[{"number":1,"watched_at":"2026-10-30T20:00:00Z"},{"number":2,"watched_at":"2026-11-01T20:00:00Z"}]}]}
        """,
    )

    @Test fun idsAcceptStringsAndNumbers() {
        val ids = SimklMappers.ids(obj("""{"simkl":297,"imdb":"tt0158552","tvdb":"70626","tmdb":"1981","mal":"5114"}"""))
        assertEquals(ExternalIds(tmdb = 1981, imdb = "tt0158552", simkl = 297, tvdb = 70626, mal = 5114), ids)
    }

    @Test fun showHistoryAndProgress() {
        val history = SimklMappers.history(show, Kind.SHOWS)
        assertEquals(2, history.size)
        assertEquals(MediaType.EPISODE, history[0].media.type)
        assertEquals(1981L, history[0].media.ids.tmdb)
        val p = SimklMappers.progress(show, Kind.SHOWS)!!
        assertEquals(8, p.aired)
        assertEquals(2, p.completed)
        assertEquals(1, p.next!!.season)
        assertEquals(3, p.next!!.episode)
        assertEquals(Instant.parse("2026-11-01T20:00:00Z"), p.lastWatchedAt)
    }

    @Test fun completedMovieWithoutDateIsWatchedButNotAnEvent() {
        val movie = obj("""{"status":"completed","movie":{"title":"The Godfather","year":1972,"ids":{"simkl":53434,"imdb":"tt0068646","tmdb":"238"}}}""")
        assertTrue(SimklMappers.history(movie, Kind.MOVIES).isEmpty()) // nu inventăm o dată
        assertEquals(Instant.EPOCH, SimklMappers.watchedTitle(movie, Kind.MOVIES)!!.watchedAt)
    }

    @Test fun planToWatchIsWatchlist() {
        val movie = obj("""{"status":"plantowatch","added_to_watchlist_at":"2026-11-02T10:00:00Z","movie":{"title":"Dune","year":2021,"ids":{"tmdb":"438631"}}}""")
        val w = SimklMappers.watchlist(movie, Kind.MOVIES)!!
        assertEquals(438631L, w.media.ids.tmdb)
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), w.listedAt)
        assertNull(SimklMappers.watchlist(show, Kind.SHOWS))
    }

    @Test fun animeUsesTvdbNumberingWhenMapped() {
        val anime = obj(
            """
            {"status":"watching","show":{"title":"Frieren","ids":{"simkl":1,"tvdb":"424536","mal":"52991"}},
             "seasons":[{"number":1,"episodes":[{"number":30,"watched_at":"2026-11-01T20:00:00Z","tvdb":{"season":2,"episode":2}}]}]}
            """,
        )
        val eps = SimklMappers.watchedEpisodes(anime, anime = true)
        assertEquals(EpisodeNumber(2, 2), eps.single().first)
    }

    @Test fun episodeCodes() {
        assertEquals(EpisodeNumber(1, 5), SimklMappers.parseEpisodeCode("S01E05"))
        assertEquals(EpisodeNumber(1, 12), SimklMappers.parseEpisodeCode("E12"))
        assertNull(SimklMappers.parseEpisodeCode("finished"))
    }

    @Test fun playbackAcceptsBothEpisodeNumberFields() {
        val a = obj("""{"id":7,"progress":42.5,"paused_at":"2026-11-03T10:00:00Z","type":"episode","episode":{"season":1,"number":3},"show":{"title":"X","ids":{"tmdb":"1"}}}""")
        val b = obj("""{"id":8,"progress":10,"paused_at":"2026-11-03T10:00:00Z","type":"episode","episode":{"season":1,"episode":5},"show":{"title":"X","ids":{"tmdb":"1"}}}""")
        val ra = SimklMappers.resume(a)!!
        assertEquals(3, ra.media.episode)
        assertEquals(0.425f, ra.progress!!, 0.0001f)
        assertEquals(7L, ra.remoteId)
        assertEquals(5, SimklMappers.resume(b)!!.media.episode)
    }

    @Test fun historyBodyForEpisode() {
        val m = MediaRef(MediaType.EPISODE, ExternalIds(tmdb = 1399, simkl = 5), "GoT", 2011, 2, 4)
        val body: JsonObject = SimklMappers.historyBody(m, Instant.parse("2026-11-03T10:00:00Z"))
        val s = body["shows"]!!.jsonArray[0].jsonObject
        assertEquals("5", s["ids"]!!.jsonObject["simkl"]!!.jsonPrimitive.content)
        val season = s["seasons"]!!.jsonArray[0].jsonObject
        assertEquals("2", season["number"]!!.jsonPrimitive.content)
        assertEquals("4", season["episodes"]!!.jsonArray[0].jsonObject["number"]!!.jsonPrimitive.content)
    }

    @Test fun addToListPutsToOnEachItem() {
        val m = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603), "The Matrix", 1999)
        val item = SimklMappers.addToListBody(m, "plantowatch")["movies"]!!.jsonArray[0].jsonObject
        assertEquals("plantowatch", item["to"]!!.jsonPrimitive.content)
    }
}
