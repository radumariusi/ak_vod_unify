package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTrackerTest {
    private val episode = MediaInfo(mapOf("title" to "Chapter One", "artist" to "Stranger Things"), durationMs = 50 * 60_000L)
    private val other = MediaInfo(mapOf("title" to "Chapter Two", "artist" to "Stranger Things"), durationMs = 50 * 60_000L)

    @Test
    fun pauseSendsCurrentPosition() {
        val t = SessionTracker("com.netflix.ninja", "Netflix")
        t.onMetadata(episode, 0)
        assertTrue(t.onPlaybackState(PlayState.PLAYING, 600_000, 1_000, 1f, 1_000).isEmpty())
        val out = t.onPlaybackState(PlayState.PAUSED, 660_000, 61_000, 0f, 61_000)
        assertEquals(1, out.size)
        assertEquals(SendReason.PAUSE, out[0].reason)
        assertEquals(660_000L, out[0].positionMs)
    }

    @Test
    fun positionAdvancesWhilePlaying() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 100_000, 10_000, 1f, 10_000)
        assertEquals(130_000L, t.position(40_000))
    }

    @Test
    fun tickAtMostOncePerMinute() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 120_000, 0, 1f, 0)
        assertEquals(1, t.onTick(15_000).size)
        assertTrue(t.onTick(30_000).isEmpty())
        assertEquals(1, t.onTick(76_000).size)
    }

    @Test
    fun stopKeepsLastRealPositionWhenAppResetsToZero() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 1_000_000, 0, 1f, 0)
        val out = t.onPlaybackState(PlayState.STOPPED, 0, 20_000, 0f, 20_000)
        assertEquals(SendReason.STOP, out.single().reason)
        assertEquals(1_020_000L, out.single().positionMs)
    }

    @Test
    fun newTitleClosesPreviousOne() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 2_900_000, 0, 1f, 0)
        val out = t.onMetadata(other, 10_000)
        assertEquals(SendReason.STOP, out.single().reason)
        assertEquals("Chapter One", out.single().media.fields["title"])
        assertEquals(2_910_000L, out.single().positionMs)
    }

    @Test
    fun ignoresFirstSeconds() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 5_000, 0, 1f, 0)
        assertTrue(t.onPlaybackState(PlayState.PAUSED, 6_000, 1_000, 0f, 1_000).isEmpty())
    }

    @Test
    fun repeatedPauseAtSamePositionSentOnce() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PAUSED, 600_000, 0, 0f, 0)
        t.onPlaybackState(PlayState.BUFFERING, 600_000, 1_000, 0f, 1_000)
        assertTrue(t.onPlaybackState(PlayState.PAUSED, 601_000, 2_000, 0f, 2_000).isEmpty())
    }

    @Test
    fun titleLearnedLaterKeepsPositionAndPlayId() {
        val t = SessionTracker("com.netflix.ninja", "Netflix")
        t.onMetadata(MediaInfo(emptyMap(), null), 0)
        t.onPlaybackState(PlayState.PLAYING, 600_000, 0, 1f, 0)
        val id = t.playId
        assertTrue(t.onMetadata(episode, 10_000).isEmpty())
        val out = t.onPlaybackState(PlayState.PAUSED, null, 0, 0f, 20_000)
        assertEquals(620_000L, out.single().positionMs)
        assertEquals("Chapter One", out.single().media.fields["title"])
        assertEquals(id, out.single().playId)
    }

    @Test
    fun screenCorrectionDoesNotCloseTheViewing() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, 600_000, 0, 1f, 0)
        val id = t.playId
        assertTrue(t.onMetadata(other, 5_000, correction = true).isEmpty())
        assertEquals(id, t.playId)
        assertEquals(605_000L, t.position(5_000))
    }

    @Test
    fun newTitleGetsNewPlayId() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        val id = t.playId
        t.onMetadata(other, 1_000)
        assertTrue(id != t.playId)
    }

    @Test
    fun noPositionFromAppCountsPlayingTime() {
        val t = SessionTracker("com.netflix.ninja", "Netflix")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PLAYING, null, 0, 1f, 0)
        val out = t.onPlaybackState(PlayState.PAUSED, null, 0, 0f, 120_000)
        assertEquals(120_000L, out.single().positionMs)
        assertTrue(out.single().estimated)
        // Pauza nu se numără.
        t.onPlaybackState(PlayState.PLAYING, null, 0, 1f, 500_000)
        assertEquals(150_000L, t.playedTime(530_000))
    }

    @Test
    fun screenPositionUsedWhenAppGivesNone() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PAUSED, null, 0, 0f, 0)
        t.onScreenTimes(1_500_000, 3_577_000, 1_000)
        val out = t.onPlaybackState(PlayState.STOPPED, null, 0, 0f, 2_000)
        assertEquals(1_500_000L, out.single().positionMs)
        assertTrue(!out.single().estimated)
    }

    @Test
    fun screenPositionIgnoredWhenAppGivesOne() {
        val t = SessionTracker("p", "P")
        t.onMetadata(episode, 0)
        t.onPlaybackState(PlayState.PAUSED, 600_000, 0, 0f, 0)
        t.onScreenTimes(10_000, null, 1_000)
        assertEquals(600_000L, t.position(1_000))
    }

    @Test
    fun untitledSessionStillSends() {
        val t = SessionTracker("com.apple.atve.androidtv.appletv", "Apple TV")
        t.onMetadata(null, 0)
        t.onPlaybackState(PlayState.PLAYING, 300_000, 0, 1f, 0)
        val out = t.onPlaybackState(PlayState.PAUSED, 312_000, 12_000, 0f, 12_000)
        assertEquals(312_000L, out.single().positionMs)
    }
}
