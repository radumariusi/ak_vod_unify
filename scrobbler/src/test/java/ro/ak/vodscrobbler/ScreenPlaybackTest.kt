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
}
