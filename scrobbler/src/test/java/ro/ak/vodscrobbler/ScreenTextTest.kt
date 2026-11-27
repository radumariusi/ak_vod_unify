package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTextTest {
    private fun items(vararg t: String) = t.map { ScreenItem(it) }

    @Test
    fun netflixPauseScreenShowAndEpisode() {
        val g = ScreenText.guess(items("Back", "Stranger Things", "S1:E3 Chapter Three: Holly, Jolly", "12:34", "Next Episode", "Episodes"), "Netflix")
        assertEquals("Stranger Things", g!!.title)
        assertEquals("S1:E3 Chapter Three: Holly, Jolly", g.subtitle)
    }

    @Test
    fun inlineEpisodeLine() {
        val g = ScreenText.guess(items("Pause", "Slow Horses S2 E4 Cleaning Up"))
        assertEquals("Slow Horses", g!!.title)
        assertEquals("S2 E4 Cleaning Up", g.subtitle)
    }

    @Test
    fun viewIdsWin() {
        val g = ScreenText.guess(
            listOf(
                ScreenItem("Audio & Subtitles", "x:id/audio"),
                ScreenItem("Severance", "com.apple.atve:id/player_title"),
                ScreenItem("Season 2, Episode 1", "com.apple.atve:id/player_subtitle"),
            ),
        )
        assertEquals("Severance", g!!.title)
        assertEquals("Season 2, Episode 1", g.subtitle)
    }

    @Test
    fun movieIsFirstMeaningfulText() {
        val g = ScreenText.guess(items("Play", "Subtitles", "Dune: Part Two", "1:02:03", "-1:45:00"))
        assertEquals("Dune: Part Two", g!!.title)
        assertNull(g.subtitle)
    }

    @Test
    fun onlyButtonsMeansNoGuess() {
        assertNull(ScreenText.guess(items("Play", "Pause", "Subtitles", "12:00", "Netflix"), "Netflix"))
    }

    @Test
    fun elapsedAndRemaining() {
        val t = ScreenText.times(items("12:00", "-48:00"))
        assertEquals(12 * 60_000L, t.positionMs)
        assertEquals(60 * 60_000L, t.durationMs)
    }

    @Test
    fun elapsedSlashTotal() {
        val t = ScreenText.times(items("1:02:03 / 2:10:00"))
        assertEquals(3_723_000L, t.positionMs)
        assertEquals(7_800_000L, t.durationMs)
    }

    @Test
    fun onlyRemainingUsesKnownDuration() {
        val t = ScreenText.times(items("−45:00"))
        assertNull(t.positionMs)
        assertEquals(15 * 60_000L, t.position(60 * 60_000L))
    }

    @Test
    fun seekBarInSecondsOrMillis() {
        val s = ScreenText.times(emptyList(), listOf(ScreenRange(600f, 3_600f, false)))
        assertEquals(600_000L, s.positionMs)
        assertEquals(3_600_000L, s.durationMs)
        val ms = ScreenText.times(emptyList(), listOf(ScreenRange(600_000f, 3_577_000f, false)))
        assertEquals(600_000L, ms.positionMs)
        assertEquals(3_577_000L, ms.durationMs)
    }

    @Test
    fun ignoresClockInsideSentences() {
        assertTrue(ScreenText.times(items("New episode Friday at 12:30 PM")).isEmpty)
    }

    @Test
    fun playButton() {
        assertTrue(ScreenText.hasPlayButton(items("Stranger Things", "Play", "Episodes")))
        assertTrue(ScreenText.hasPlayButton(items("Reda episodul 3")))
        assertFalse(ScreenText.hasPlayButton(items("Stranger Things", "My List")))
    }

    @Test
    fun appleTvSeasonCommaEpisodeThenShowLast() {
        val g = ScreenText.guess(
            items("ATVE.ScreenReader.Loading", "S1, E1 · I’m Robbing the House on Christmas Eve While the Family’s at Mass, 12 12 12"),
            "Apple TV",
        )
        assertEquals("12 12 12", g!!.title)
        assertEquals("S1, E1 · I’m Robbing the House on Christmas Eve While the Family’s at Mass", g.subtitle)
    }

    @Test
    fun seasonAloneIsNeverTheTitle() {
        val g = ScreenText.guess(items("Severance", "S2, E3 Who Is Alive?"))
        assertEquals("Severance", g!!.title)
    }

    @Test
    fun resourceKeysAreIgnored() {
        assertNull(ScreenText.guess(items("ATVE.ScreenReader.Loading")))
    }

    @Test
    fun adScreenDetectsRealAdsAndIgnoresSeekbarMarkers() {
        assertTrue(ScreenText.isAdScreen(items("Ad 1 of 2", "0:15")))
        assertTrue(ScreenText.isAdScreen(items("Ad 1 of 1. This ad will end in", "00:35")))
        assertTrue(ScreenText.isAdScreen(items("Your video will resume after the ad")))
        assertTrue(ScreenText.isAdScreen(items("Reclamă", "Skip ad")))
        assertFalse(
            ScreenText.isAdScreen(
                items("The End of Oak Street", "ad_marker_1|{\"adStartTime\":0,\"adDuration\":50000}", "15:05"),
            ),
        )
    }

    @Test
    fun ignoresChannelGuideAndDpadButtons() {
        assertNull(ScreenText.guess(items("DPAD Top Button", "DPAD Left Button", "peacocktv"), "Peacock TV"))
        assertEquals("Gunsmoke", ScreenText.guess(items("Channel Guide", "Live TV", "Gunsmoke"), "Pluto TV")!!.title)
    }

    @Test
    fun ignoresScheduleRangesAndDetectsDoubleSpaceAds() {
        assertTrue(ScreenText.times(items("Live", "00:00 - 02:00")).isEmpty)
        assertTrue(ScreenText.isAdScreen(items("AD  1 of 4")))
        assertTrue(ScreenText.hasPlayButton(items("Las Fierbinți", "Redare episod")))
        assertNull(ScreenText.guess(items("6 EPISODES", "58MIN", "TV-MA")))
    }

    @Test
    fun plutoPlayerTitleBeatsSuggestionCarousel() {
        val g = ScreenText.guess(
            listOf(
                ScreenItem("2h 13m left", "tv.pluto.android:id/time_left"),
                ScreenItem("Rocky", "tv.pluto.android:id/content_title_selected"),
                ScreenItem("Rocky", "tv.pluto.android:id/content_title_not_selected"),
                ScreenItem("Movie", "tv.pluto.android:id/content_type"),
                ScreenItem("Pause, button", "tv.pluto.android:id/play_pause_button"),
                ScreenItem("A Star Is Born (2018)", "tv.pluto.android:id/content_title"),
                ScreenItem("0:02:11", "tv.pluto.android:id/current_time_label"),
            ),
            "Pluto TV",
        )
        assertEquals("A Star Is Born (2018)", g?.title)
    }

    @Test
    fun overlayBadgesAndImageLabelsAreNotTitles() {
        assertEquals(null, ScreenText.guess(items("On Now", "Info, button", "Loading Pluto TV", "Picture of home header logo."), "Pluto TV"))
    }
}
