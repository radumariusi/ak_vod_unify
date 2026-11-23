package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeResolverTest {
    private fun pair(position: Long, second: Long) =
        ScreenTimes(position * 1_000, second * 1_000, null, position * 1_000 to second * 1_000)

    @Test
    fun countdownIsLearnedAcrossFrequentReads() {
        val resolver = ScreenTimeResolver()
        for (elapsed in 0L..2L) assertNull(resolver.resolve(pair(600 + elapsed, 1800 - elapsed)).durationMs)
        val result = resolver.resolve(pair(603, 1797))
        assertEquals(2_400_000L, result.durationMs)
        assertEquals(1_797_000L, result.remainingMs)
        assertEquals(2_400_000L, resolver.resolve(pair(605, 1795)).durationMs)
    }

    @Test
    fun fixedTotalDoesNotTurnIntoRemainingAfterASeek() {
        val resolver = ScreenTimeResolver()
        resolver.resolve(pair(300, 1800))
        assertEquals(1_800_000L, resolver.resolve(pair(303, 1800)).durationMs)
        assertEquals(1_800_000L, resolver.resolve(pair(80, 1800)).durationMs)
        resolver.reset()
        assertNull(resolver.resolve(pair(80, 1720)).durationMs)
        assertEquals(1_800_000L, resolver.resolve(pair(80, 1720), knownDuration = 1_800_000L).durationMs)
    }
}
