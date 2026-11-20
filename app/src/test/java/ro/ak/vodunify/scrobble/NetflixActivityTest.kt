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
}
