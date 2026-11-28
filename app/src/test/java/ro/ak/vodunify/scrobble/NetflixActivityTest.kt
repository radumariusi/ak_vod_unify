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
    fun netflixIdResolvesAnOlderEventWithSeveralNearbyTitles() {
        val event = event(208_000).copy(fields = mapOf("netflix_id" to "70105212"))
        val items = listOf(
            NetflixViewedItem(title = "Alt film", watchedAt = now, id = "70105211"),
            NetflixViewedItem(title = "Filmul corect", watchedAt = now - 7L * 24 * 60 * min, id = "70105212"),
        )
        assertEquals("Filmul corect", NetflixActivity.match(event, items)?.title)
    }

    @Test
    fun mixedActivityFallsBackToBookmarkForAnOlderDay() {
        val items = listOf(
            NetflixViewedItem(title = "Recent", watchedAt = now + 50 * min, id = "1"),
            NetflixViewedItem(title = "Potrivire", date = now - 12 * 60 * min, bookmark = 210, id = "2"),
        )
        assertEquals("2", NetflixActivity.match(event(208_000), items)?.id)
    }

    @Test
    fun neverInterpretAnIncompleteSeriesRecordAsAMovie() {
        val fields = NetflixActivity.fields(NetflixViewedItem(series = "Dark", title = "Dark"))
        assertEquals(emptyList<ro.ak.vodunify.core.scrobble.TitleGuess>(), ro.ak.vodunify.core.scrobble.TitleParser.guesses(fields, "Netflix"))
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

    @Test
    fun consumedNetflixMovieDoesNotStealSubsequentDelayedSession() {
        val drop = NetflixViewedItem(title = "Drop", watchedAt = now - 22 * min, id = "81900001")
        val firstSession = ScrobbleEvent(pkg = "com.netflix.ninja", reason = "stop", positionMs = 1_100_000L, at = now - 16 * min, playId = "nf-1")
        val secondSession = ScrobbleEvent(pkg = "com.netflix.ninja", reason = "pause", positionMs = 1_484_000L, at = now, playId = "nf-2")

        // Prima sesiune potrivește „Drop”.
        val m1 = NetflixActivity.match(firstSession, listOf(drop))!!
        assertEquals("81900001", m1.id)
        val claims = mapOf(
            NetflixActivity.viewedKey(m1) to ro.ak.vodunify.core.scrobble.NetflixClaim(
                itemKey = NetflixActivity.viewedKey(m1),
                watchedAt = m1.watchedAt!!,
                eventAt = firstSession.at,
                playId = firstSession.playId,
            ),
        )

        // A doua sesiune (înainte ca Netflix să actualizeze noul titlu) NU fură „Drop”.
        assertNull(NetflixActivity.match(secondSession, listOf(drop), claims))

        // Când apare noul titlu pe Netflix, a doua sesiune îl potrivește corect.
        val hunger = NetflixViewedItem(title = "The Hunger Games", watchedAt = now + 4 * min, id = "81600002")
        assertEquals("81600002", NetflixActivity.match(secondSession, listOf(hunger, drop), claims)?.id)
    }

    @Test
    fun rapidMovieSwitchingSeparatedByContinueWatchingBookmarks() {
        val items = listOf(
            NetflixViewedItem(title = "Movie A", watchedAt = now - 30_000, bookmark = 896, duration = 6000, id = "101"),
            NetflixViewedItem(title = "Movie B", watchedAt = now - 10_000, bookmark = 2136, duration = 6400, id = "102"),
            NetflixViewedItem(title = "Movie C", watchedAt = now + 10_000, bookmark = 3551, duration = 7200, id = "103"),
        )
        assertEquals("101", NetflixActivity.match(event(896_000).copy(playId = "p1"), items)?.id)
        assertEquals("102", NetflixActivity.match(event(2_136_000).copy(playId = "p2"), items)?.id)
        assertEquals("103", NetflixActivity.match(event(3_551_000).copy(playId = "p3"), items)?.id)
    }

    @Test
    fun matchesByPlaybackStartWhenTitlesStartedAMinuteApart() {
        // TV 28.11: Grinch pornit 09:52:10, Lupin 09:53:40; Netflix notează ora pornirii.
        val grinchAt = now
        val lupinAt = now + 90_000L
        val items = listOf(
            NetflixViewedItem(title = "Dr. Seuss' The Grinch", watchedAt = grinchAt - 2_000L, id = "g"),
            NetflixViewedItem(series = "Lupin", season = "Part 1", episode = "Chapter 1", title = "Lupin: Part 1: Chapter 1", watchedAt = lupinAt - 2_000L, id = "l"),
        )
        val first = ScrobbleEvent(pkg = "com.netflix.ninja", reason = "pause", positionMs = 63_000L, at = grinchAt + 72_000L)
        val second = ScrobbleEvent(pkg = "com.netflix.ninja", reason = "pause", positionMs = 70_000L, at = lupinAt + 76_000L)
        assertEquals("g", NetflixActivity.match(first, items)!!.id)
        assertEquals("l", NetflixActivity.match(second, items)!!.id)
    }
}
