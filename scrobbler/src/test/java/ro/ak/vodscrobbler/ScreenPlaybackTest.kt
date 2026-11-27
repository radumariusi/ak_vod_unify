package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenPlaybackTest {
    private fun times(pos: Long, dur: Long = 6_000_000) = ScreenTimes(pos, dur, null)
    private val g = ScreenGuess("Dune: Part Two", null)

    @Test
    fun clockAloneIsNotAPlayer() {
        assertFalse(ScreenPlayback.looksLikePlayer(ScreenTimes(1_200_000, null, null)))
        assertTrue(ScreenPlayback.looksLikePlayer(times(60_000)))
    }

    @Test
    fun playThenPauseSendsPauseWithTitle() {
        val p = ScreenPlayback("com.nuvio.tv", "Nuvio")
        p.onScreen(g, times(100_000), 0)
        p.onScreen(g, times(105_000), 5_000)
        assertEquals(PlayState.PLAYING, p.tracker.state)
        val out = p.onScreen(g, times(105_000), 9_000)
        assertEquals(PlayState.PAUSED, p.tracker.state)
        assertEquals(1, out.size)
        assertEquals(SendReason.PAUSE, out[0].reason)
        assertEquals("Dune: Part Two", out[0].media.fields["title"])
        assertEquals(105_000, out[0].positionMs)
        assertFalse(out[0].estimated)
    }

    @Test
    fun leavingTheAppStopsAtAdvancedMinute() {
        val p = ScreenPlayback("com.debridstream.tv", "DebridStream")
        p.onScreen(g, times(600_000), 0)
        p.onTick(10_000, "com.debridstream.tv")
        val out = p.onTick(40_000, "com.google.android.tvlauncher")
        assertTrue(p.finished)
        assertEquals(SendReason.STOP, out.single().reason)
        assertTrue(out.single().positionMs >= 600_000 + 30_000)
    }

    @Test
    fun detailsPageGivesTitleWhenPlayerShowsNone() {
        val p = ScreenPlayback("com.nuvio.tv", "Nuvio")
        p.onScreen(null, times(200_000), 0, details = ScreenGuess("The Bear", "S3 E2"))
        assertEquals("The Bear", p.tracker.media?.fields?.get("title"))
    }

    @Test
    fun adBreakInScreenOnlyPlayerSavesProgressAndIgnoresAd() {
        val p = ScreenPlayback("net.cme.voyo.ro.tvapp", "VOYO")
        p.onScreen(ScreenGuess("Clanul", "S1:E2"), times(600_000), 0)
        p.onScreen(ScreenGuess("Clanul", "S1:E2"), times(610_000), 10_000)
        val preAd = p.tracker.onAdDetected(12_000)
        assertEquals(SendReason.PAUSE, preAd.single().reason)
        assertEquals(612_000L, preAd.single().positionMs)
        assertTrue(p.onScreen(ScreenGuess("Ad", null), times(15_000, 30_000), 15_000).isEmpty())
    }

    @Test
    fun voyoSeasonComesFromEpisodePage() {
        val details = ScreenGuess("Mihai Bobonete, Constantin Dita, Anca Dumitra", "Episodul 25: Nuca, Partea 1Sezonul 29")
        assertEquals("Sezonul 29 Episodul 25: Nuca, Partea 1", ScreenPlayback.withSeason("Episodul 25: Nuca, Partea 1", details))
        // Alt episod pe pagină: nu lipim sezonul.
        assertEquals("Episodul 3: Altul", ScreenPlayback.withSeason("Episodul 3: Altul", details))
        assertEquals("S1:E2", ScreenPlayback.withSeason("S1:E2", ScreenGuess("X", "Sezonul 4")))
        val p = ScreenPlayback("net.cme.voyo.ro.tvapp", "VOYO")
        p.onScreen(ScreenGuess("Las Fierbinți", "Episodul 25: Nuca, Partea 1"), times(500_000, 2_754_000), 0, details = details)
        assertEquals("Sezonul 29 Episodul 25: Nuca, Partea 1", p.tracker.media?.fields?.get("display_subtitle"))
    }

    @Test
    fun episodePageAfterPlaybackStopsWithoutFakeSession() {
        val p = ScreenPlayback("net.cme.voyo.ro.tvapp", "VOYO")
        val ep = ScreenGuess("Las Fierbinți", "Episodul 25: Nuca, Partea 1")
        p.onScreen(ep, times(500_000, 2_754_000), 0)
        p.onScreen(ep, times(560_000, 2_754_000), 60_000)
        val out = p.onScreen(
            ScreenGuess("Mihai Bobonete, Constantin Dita, Anca Dumitra", "Episodul 25: Nuca, Partea 1Sezonul 29"),
            ScreenTimes(null, null, null), 62_000, detailsScreen = true,
        )
        assertTrue(p.finished)
        assertEquals(SendReason.STOP, out.single().reason)
        assertEquals("Las Fierbinți", out.single().media.fields["title"])
        assertTrue(p.onScreen(ep, ScreenTimes(null, null, null), 70_000).isEmpty())
    }
}
