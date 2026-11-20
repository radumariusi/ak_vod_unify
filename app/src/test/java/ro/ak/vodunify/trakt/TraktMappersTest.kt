package ro.ak.vodunify.trakt

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
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
import ro.ak.vodunify.core.trakt.TraktHistoryItem
import ro.ak.vodunify.core.trakt.TraktMappers
import ro.ak.vodunify.core.trakt.TraktPlayback
import ro.ak.vodunify.core.trakt.TraktShowProgress
import ro.ak.vodunify.core.trakt.TraktToken
import java.time.Instant

class TraktMappersTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val playbackJson = """
        [
          {"progress": 10.5, "paused_at": "2015-01-25T22:01:32.000Z", "id": 13, "type": "movie",
           "movie": {"title": "Batman Begins", "year": 2005, "ids": {"trakt": 1, "slug": "batman-begins-2005", "imdb": "tt0372784", "tmdb": 272}}},
          {"progress": 65.5, "paused_at": "2015-01-25T22:01:32.000Z", "id": 37, "type": "episode",
           "episode": {"season": 0, "number": 1, "title": "Good Cop Bad Cop", "ids": {"trakt": 1253, "tmdb": 62131}},
           "show": {"title": "Breaking Bad", "year": 2008, "ids": {"trakt": 1, "imdb": "tt0903747", "tmdb": 1396}}},
          {"paused_at": "2015-01-25T22:01:32.000Z", "id": 38, "type": "movie",
           "movie": {"title": "Fără progres", "year": 2001, "ids": {"tmdb": 99}}}
        ]
    """.trimIndent()

    @Test fun playbackKeepsMissingPositionAsUnknown() {
        val items = json.decodeFromString(ListSerializer(TraktPlayback.serializer()), playbackJson)
            .mapNotNull(TraktMappers::resume)
        assertEquals(3, items.size)
        assertEquals(0.105f, items[0].progress!!, 0.0001f)
        assertEquals("movie:tmdb:272", items[0].media.itemKey)
        assertEquals(13L, items[0].remoteId)

        val ep = items[1]
        assertEquals(MediaType.EPISODE, ep.media.type)
        assertEquals(1396L, ep.media.ids.tmdb) // id-ul serialului, nu al episodului
        assertEquals("tv:tmdb:1396:s0e1", ep.media.itemKey)
        assertEquals("Good Cop Bad Cop", ep.media.episodeTitle)
        assertEquals(0.655f, ep.progress!!, 0.0001f)

        assertNull("progres lipsă = necunoscut, nu 0%", items[2].progress)
        assertEquals(Instant.parse("2015-01-25T22:01:32Z"), items[2].updatedAt)
    }

    @Test fun historyMapsMoviesAndEpisodes() {
        val raw = """
            [{"id": 1982346, "watched_at": "2014-03-31T09:28:53.000Z", "action": "scrobble", "type": "episode",
              "episode": {"season": 2, "number": 1, "title": "Pawnee Zoo", "ids": {"trakt": 251}},
              "show": {"title": "Parks and Recreation", "year": 2009, "ids": {"trakt": 4, "tmdb": 8592}}},
             {"id": 1982347, "watched_at": "2014-03-31T09:28:53.000Z", "action": "watch", "type": "movie",
              "movie": {"title": "The Dark Knight", "year": 2008, "ids": {"trakt": 6, "tmdb": 155}}},
             {"id": 5, "watched_at": "nu e dată", "type": "movie", "movie": {"title": "x"}}]
        """
        val events = json.decodeFromString(ListSerializer(TraktHistoryItem.serializer()), raw).mapNotNull(TraktMappers::history)
        assertEquals(2, events.size)
        assertEquals("tv:tmdb:8592:s2e1", events[0].media.itemKey)
        assertEquals("movie:tmdb:155", events[1].media.titleKey)
        assertEquals(1982347L, events[1].remoteId)
    }

    @Test fun showProgressFindsWatchedAndNext() {
        val raw = """
            {"aired": 8, "completed": 6, "last_watched_at": "2015-03-21T19:03:58.000Z",
             "seasons": [{"number": 1, "aired": 8, "completed": 6, "episodes": [
               {"number": 1, "completed": true}, {"number": 2, "completed": true}, {"number": 7, "completed": false}]}],
             "next_episode": {"season": 1, "number": 7, "title": "Unfaithful", "ids": {"trakt": 62}}}
        """
        val show = MediaRef(MediaType.SHOW, ExternalIds(tmdb = 1399), "Game of Thrones", 2011)
        val p = TraktMappers.progress(show, json.decodeFromString(TraktShowProgress.serializer(), raw))
        assertEquals(setOf(EpisodeNumber(1, 1), EpisodeNumber(1, 2)), p.watched)
        assertEquals(8, p.aired)
        assertEquals(MediaType.EPISODE, p.next!!.type)
        assertEquals(7, p.next!!.episode)
        assertEquals("tv:tmdb:1399:s1e7", p.next!!.itemKey)
    }

    @Test fun syncBodyForEpisodeUsesShowIdsAndNumbers() {
        val ep = MediaRef(MediaType.EPISODE, ExternalIds(tmdb = 1399, imdb = "tt0944947"), "GoT", season = 2, episode = 3)
        val body = TraktMappers.syncBody(ep, Instant.parse("2024-01-01T10:00:00Z"))
        val show = body["shows"]!!.jsonArray[0].jsonObject
        assertEquals("1399", show["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertEquals("tt0944947", show["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        val season = show["seasons"]!!.jsonArray[0].jsonObject
        assertEquals("2", season["number"]!!.jsonPrimitive.content)
        val episode = season["episodes"]!!.jsonArray[0].jsonObject
        assertEquals("3", episode["number"]!!.jsonPrimitive.content)
        assertEquals("2024-01-01T10:00:00Z", episode["watched_at"]!!.jsonPrimitive.content)
    }

    @Test fun syncBodyForMovieAndRemoveHasNoDate() {
        val movie = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603), "The Matrix", 1999)
        val body = TraktMappers.syncBody(movie)
        val item = body["movies"]!!.jsonArray[0].jsonObject
        assertEquals("603", item["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
        assertTrue("watched_at" !in item)
    }

    @Test fun scrobbleBodyIsPercent() {
        val movie = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603), "The Matrix")
        val body = TraktMappers.scrobbleBody(movie, 0.42f)
        assertEquals(42.0, body["progress"]!!.jsonPrimitive.content.toDouble(), 0.01)
    }

    @Test fun tokenParses() {
        val t = json.decodeFromString(
            TraktToken.serializer(),
            """{"access_token":"a","token_type":"bearer","expires_in":86400,"refresh_token":"r","scope":"public","created_at":1487889741}""",
        )
        assertEquals(86400L, t.expiresIn)
        assertEquals(1487889741L, t.createdAt)
    }

    @Test fun titleKeyFallsBackWhenNoTmdb() {
        assertEquals("movie:imdb:tt1", MediaRef(MediaType.MOVIE, ExternalIds(imdb = "tt1"), "X").titleKey)
        assertEquals("tv:trakt:5", MediaRef(MediaType.SHOW, ExternalIds(trakt = 5), "X").titleKey)
        assertEquals("tv:trakt:5:s1e2", MediaRef(MediaType.EPISODE, ExternalIds(trakt = 5), "X", season = 1, episode = 2).itemKey)
    }
}
