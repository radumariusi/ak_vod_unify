package ro.ak.vodunify.scrobble

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.scrobble.LaunchHints
import ro.ak.vodunify.core.scrobble.ScrobbleEvent

class LaunchHintsTest {
    private val t0 = 1_000_000_000L
    private fun hint(type: String = "MOVIE", precision: String = "TITLE", runtimeMin: Long? = 100, usedBy: String? = null) =
        LaunchHints.Hint(pkg = "com.app", at = t0, type = type, tmdb = 1, title = "X", runtimeMs = runtimeMin?.let { it * 60_000 }, precision = precision, usedBy = usedBy)
    private fun event(durationMin: Long = 101, afterMin: Long = 2, playId: String = "p1", pkg: String = "com.app") =
        ScrobbleEvent(pkg = pkg, reason = "tick", positionMs = 60_000, durationMs = durationMin * 60_000, at = t0 + afterMin * 60_000, playId = playId)

    @Test fun movieConfirmedByDuration() = assertNull(LaunchHints.Rules.check(hint(), event()))
    @Test fun durationTooFarIsRejected() = assertNotNull(LaunchHints.Rules.check(hint(), event(durationMin = 95)))
    @Test fun otherAppIsRejected() = assertNotNull(LaunchHints.Rules.check(hint(), event(pkg = "com.other")))
    @Test fun oldLaunchIsRejected() = assertNotNull(LaunchHints.Rules.check(hint(), event(afterMin = 25)))
    @Test fun episodeNeedsDirectLink() {
        assertNotNull(LaunchHints.Rules.check(hint(type = "EPISODE", precision = "SERIES", runtimeMin = 45), event(durationMin = 45)))
        assertNull(LaunchHints.Rules.check(hint(type = "EPISODE", precision = "EPISODE", runtimeMin = 45), event(durationMin = 45)))
    }
    @Test fun usedByAnotherPlaybackIsRejected() = assertNotNull(LaunchHints.Rules.check(hint(usedBy = "p0"), event()))
    @Test fun unknownDurationIsRejected() = assertNotNull(LaunchHints.Rules.check(hint(), event(durationMin = 0)))
}
