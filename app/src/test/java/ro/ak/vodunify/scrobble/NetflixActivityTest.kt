package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.scrobble.NetflixActivity
import ro.ak.vodunify.core.scrobble.NetflixViewedItem
import ro.ak.vodunify.core.scrobble.ScrobbleEvent

class NetflixActivityTest {
    private val now = 1_763_660_000_000L
    private fun event(posMs: Long) = ScrobbleEvent(pkg = "com.netflix.ninja", reason = "pause", positionMs = posMs, at = now)

    @Test
    fun matchesByMinuteSameDay() {
        val items = listOf(
            NetflixViewedItem(title = "Old", date = now - 3_600_000, bookmark = 500, id = "1"),
            NetflixViewedItem(series = "Wednesday", season = "Season 2", episode = "Woe Thyself", title = "Wednesday: Season 2: Woe Thyself", date = now - 600_000, bookmark = 210, id = "2"),
        )
        assertEquals("2", NetflixActivity.match(event(208_000), items)!!.id)
    }

    @Test
    fun noMatchWhenMinuteFarOrOtherDay() {
        val items = listOf(
            NetflixViewedItem(title = "A", date = now, bookmark = 900, id = "1"),
            NetflixViewedItem(title = "B", date = now - 5L * 24 * 3_600_000, bookmark = 208, id = "2"),
        )
        assertNull(NetflixActivity.match(event(208_000), items))
    }

    @Test
    fun ambiguousIsSkipped() {
        val items = listOf(
            NetflixViewedItem(title = "A", date = now, bookmark = 200, id = "1"),
            NetflixViewedItem(title = "B", date = now, bookmark = 215, id = "2"),
        )
        assertNull(NetflixActivity.match(event(208_000), items))
    }

    @Test
    fun fieldsForSeriesAndMovie() {
        val ep = NetflixActivity.fields(NetflixViewedItem(series = "Dark", season = "Season 1", episode = "Episode 3", title = "Dark: Season 1: Episode 3"))
        assertEquals("Dark", ep["title"])
        assertEquals("Season 1 Episode 3", ep["display_subtitle"])
        val movie = NetflixActivity.fields(NetflixViewedItem(title = "The Irishman"))
        assertEquals("The Irishman", movie["title"])
    }

    private val min = 60_000L

    @Test
    fun matchesByWatchedTimeNearest() {
        val items = listOf(
            NetflixViewedItem(series = "The Office (U.S.)", season = "Season 1", episode = "Pilot", title = "The Office (U.S.): Season 1: Pilot", watchedAt = now - 2 * min, seasonNumber = 1, episodeNumber = 1, id = "70105212"),
            NetflixViewedItem(title = "Movie", watchedAt = now - 6 * 60 * min, id = "2"),
        )
        assertEquals("70105212", NetflixActivity.match(event(0), items)!!.id)
    }

    @Test
    fun backToBackEpisodesPickTheCloserOne() {
        val items = listOf(
            NetflixViewedItem(title = "S: Ep2", watchedAt = now + 1 * min, id = "2"),
            NetflixViewedItem(title = "S: Ep1", watchedAt = now - 30 * min, id = "1"),
        )
        assertEquals("2", NetflixActivity.match(event(1_000), items)!!.id)
    }

    @Test
    fun watchedTimeTooFarOrAmbiguousIsSkipped() {
        assertNull(NetflixActivity.match(event(1_000), listOf(NetflixViewedItem(title = "Later", watchedAt = now + 40 * min, id = "1"))))
        assertNull(NetflixActivity.match(event(1_000), listOf(NetflixViewedItem(title = "Old", watchedAt = now - 5 * 60 * min, id = "1"))))
        val close = listOf(
            NetflixViewedItem(title = "A", watchedAt = now - 1 * min, id = "1"),
            NetflixViewedItem(title = "B", watchedAt = now + 1 * min, id = "2"),
        )
        assertNull(NetflixActivity.match(event(1_000), close))
    }

    @Test
    fun numberedEpisodeFieldsParse() {
        val f = NetflixActivity.fields(NetflixViewedItem(series = "The Office (U.S.)", season = "Season 1", episode = "Pilot", title = "x", seasonNumber = 1, episodeNumber = 1))
        assertEquals("Season 1 Episode 1 Pilot", f["display_subtitle"])
        val g = ro.ak.vodunify.core.scrobble.TitleParser.guesses(f).first()
        assertEquals(ro.ak.vodunify.core.scrobble.TitleGuess.NumberedEpisode("The Office (U.S.)", 1, 1, "Pilot"), g)
    }

    @Test
    fun structuredGuessesNeverTreatEpisodeAsMovie() {
        val f = NetflixActivity.fields(NetflixViewedItem(series = "The Office (U.S.)", episode = "Pilot", title = "x", watchedAt = now))
        assertEquals(
            listOf(ro.ak.vodunify.core.scrobble.TitleGuess.SeasonNamedEpisode("The Office (U.S.)", null, "Pilot")),
            ro.ak.vodunify.core.scrobble.TitleParser.guesses(f, "Netflix"),
        )
        val g = NetflixActivity.fields(NetflixViewedItem(series = "Dark", season = "Season 2", episode = "Episode 3", title = "x"))
        assertEquals(
            listOf(ro.ak.vodunify.core.scrobble.TitleGuess.NumberedEpisode("Dark", 2, 3, null)),
            ro.ak.vodunify.core.scrobble.TitleParser.guesses(g, "Netflix"),
        )
        val movie = NetflixActivity.fields(NetflixViewedItem(title = "Pilot"))
        assertEquals(listOf(ro.ak.vodunify.core.scrobble.TitleGuess.Movie("Pilot", null)), ro.ak.vodunify.core.scrobble.TitleParser.guesses(movie, "Netflix"))
    }
}
