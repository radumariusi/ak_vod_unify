package ro.ak.vodunify.wetrakr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers
import java.time.Instant

class WeTrakrMappersTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    private val episodeRow = obj(
        """{"type":"episode","ids":{"tmdb":63056},"series_ids":{"tmdb":1399,"imdb":"tt0944947","tvdb":121361},
            "season":1,"episode":1,"watched":{"is_watched":true,"play_count":1,"last_watched_at":"2026-11-01T20:00:00Z"}}""",
    )

    @Test fun rowKeyHangsEpisodesFromSeries() {
        assertEquals("episode:tmdb:1399:1:1", WeTrakrMappers.rowKey(episodeRow))
        assertEquals("movie:imdb:tt0133093", WeTrakrMappers.rowKey(obj("""{"type":"movie","ids":{"imdb":"tt0133093"}}""")))
    }

    @Test fun watchedEpisodeIsHistoryAndStartedShow() {
        val h = WeTrakrMappers.history(episodeRow)!!
        assertEquals(MediaType.EPISODE, h.media.type)
        assertEquals(1399L, h.media.ids.tmdb)
        assertEquals(Instant.parse("2026-11-01T20:00:00Z"), h.watchedAt)
        assertEquals(MediaType.SHOW, WeTrakrMappers.watchedTitle(episodeRow)!!.media.type)
    }

    @Test fun unknownWatchDateIsNotAnEvent() {
        val row = obj("""{"type":"movie","ids":{"tmdb":603},"watched":{"is_watched":true,"unknown":true}}""")
        assertNull(WeTrakrMappers.history(row))
        assertEquals(Instant.EPOCH, WeTrakrMappers.watchedTitle(row)!!.watchedAt)
    }

    @Test fun progressMissingPercentStaysUnknown() {
        val known = WeTrakrMappers.resume(obj("""{"type":"movie","ids":{"tmdb":603},"percent":37,"updated_at":"2026-11-02T10:00:00Z"}"""))!!
        assertEquals(0.37f, known.progress!!, 0.0001f)
        val fromPosition = WeTrakrMappers.resume(obj("""{"type":"movie","ids":{"tmdb":603},"position_ms":1800000,"runtime_s":7200}"""))!!
        assertEquals(0.25f, fromPosition.progress!!, 0.0001f)
        val unknown = WeTrakrMappers.resume(obj("""{"type":"movie","ids":{"tmdb":603}}"""))!!
        assertNull(unknown.progress)
    }

    @Test fun nextEpisodes() {
        val p = WeTrakrMappers.upNext(obj("""{"show":{"title":"GoT","ids":{"tmdb":1399}},"episode":{"season":2,"number":3,"title":"X"},"last_watched_at":"2026-11-01T20:00:00Z"}"""))!!
        assertEquals(2, p.next!!.season)
        assertEquals(3, p.next!!.episode)
    }

    @Test fun scrobbleEventForEpisode() {
        val m = MediaRef(MediaType.EPISODE, ExternalIds(tmdb = 1399), "GoT", 2011, 2, 3)
        val e = WeTrakrMappers.event("scrobble", m, 1f)
        assertEquals("episode", e["media_type"]!!.jsonPrimitive.content)
        assertEquals("100.0", e["progress"]!!.jsonPrimitive.content)
        assertEquals("1399", e["show_ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
    }
}
