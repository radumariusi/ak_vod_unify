package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ecrane reale din jurnalul din 23.11.2026 (MobLand S2:E10 în DebridStream). */
class DebridStreamScreenTest {
    private fun items(line: String) = line.split(" | ").map { ScreenItem(it) }

    private fun screen(clock: String, ends: String, label: String, a: String, b: String) =
        items("MobLand | S2:E10 - The Queen Is Dead | FHD | 3.2 GB (10 Mb/s) | torbox | $clock | AM | Ends at $ends AM | $label | $a | $b | English | English | English (5.1) | English (5.1) | Episodes | Episodes")

    private val info = items(
        "MobLand | 87% | Crime | TV-MA | S2:E10 | The Queen Is Dead | Coordinated raids set the Harrigans' empire on fire. With Co | 12:22 | AM | Ends at 12:47 AM | Play | 20:54 | 25:11 | English | English | English (5.1) | English (5.1) | Episodes | Episodes",
    )

    private fun min(m: Int, s: Int) = (m * 60 + s) * 1000L

    @Test
    fun wallClockIsNotThePosition() {
        val t = ScreenText.times(screen("12:46", "12:48", "Pause", "43:53", "02:12"))
        assertEquals(min(43, 53), t.positionMs)
        assertEquals(min(46, 5), t.durationMs)
        assertEquals(min(2, 12), t.remainingMs)
    }

    @Test
    fun startScreenRemainingWithoutMinus() {
        val t = ScreenText.times(screen("12:00", "12:46", "Pause", "00:07", "45:58"))
        assertEquals(min(0, 7), t.positionMs)
        assertEquals(min(46, 5), t.durationMs)
    }

    @Test
    fun noEndsAtTwentyFourHourClockStillFoundByEndsAt() {
        val t = ScreenText.times(items("Show | S1:E1 | 23:10 | Ends at 23:40 | 15:00 | 30:05"))
        assertEquals(min(15, 0), t.positionMs)
        assertEquals(min(45, 5), t.durationMs)
    }

    @Test
    fun ambiguousPairDecidedAcrossReads() {
        val p = ScreenPlayback("x.player", "X")
        val g = ScreenGuess("Show", "S1:E2")
        p.onScreen(g, ScreenText.times(items("Show | S1:E2 | 10:00 | 35:00")), 0)
        p.onScreen(g, ScreenText.times(items("Show | S1:E2 | 10:20 | 34:40")), 20_000)
        assertEquals(min(45, 0), p.tracker.media?.durationMs)
    }

    @Test
    fun ratingPanelDoesNotRenameTheShow() {
        val p = ScreenPlayback("com.debridstream.tv", "DebridStream")
        p.onScreen(ScreenText.guess(screen("12:21", "12:46", "Play", "20:32", "25:33"), "DebridStream"), ScreenText.times(screen("12:21", "12:46", "Play", "20:32", "25:33")), 0)
        assertEquals("MobLand", p.tracker.media?.fields?.get("title"))
        p.onScreen(ScreenText.guess(info, "DebridStream"), ScreenText.times(info), 2_000)
        assertEquals("MobLand", p.tracker.media?.fields?.get("title"))
    }

    /** Toată seara din jurnal: trebuie să ajungă la final și să trimită oprirea aproape de 46:05. */
    @Test
    fun wholeEveningEndsNearTheEnd() {
        val p = ScreenPlayback("com.debridstream.tv", "DebridStream")
        val sent = mutableListOf<Outgoing>()
        val reads = listOf(
            Triple(0L, "12:13|12:46|Play|12:48|33:17", 0),
            Triple(3_000L, "12:13|12:46|Pause|12:50|33:15", 0),
            Triple(19_000L, "12:14|12:47|Pause|12:37|33:28", 0),
            Triple(490_000L, "12:21|12:46|Play|20:32|25:33", 0),
            Triple(518_000L, "12:22|12:47|Pause|20:33|25:32", 0),
            Triple(621_000L, "12:24|12:49|Pause|20:56|25:09", 0),
            Triple(811_000L, "12:27|12:49|Play|24:04|22:01", 0),
            Triple(866_000L, "12:28|12:49|Play|24:18|21:47", 0),
            Triple(912_000L, "12:29|12:50|Pause|24:18|21:47", 0),
            Triple(1_943_000L, "12:46|12:48|Pause|43:53|02:12", 0),
            Triple(1_996_000L, "12:47|12:47|Play|45:44|00:21", 0),
        )
        for ((at, line, _) in reads) {
            val (c, e, l, a, b) = line.split("|")
            val s = screen(c, e, l, a, b)
            sent += p.onScreen(ScreenText.guess(s, "DebridStream"), ScreenText.times(s), at)
            sent += p.onTick(at + 1_000, "com.debridstream.tv")
        }
        sent += p.onTick(2_020_000L, "com.debridstream.tv")
        sent += p.onTick(2_050_000L, "com.google.android.apps.tv.launcherx")
        assertTrue(p.finished)
        val last = sent.last()
        assertEquals(SendReason.STOP, last.reason)
        assertTrue("minut ${last.positionMs}", last.positionMs >= min(45, 44))
        assertEquals(min(46, 5), last.media.durationMs)
        assertEquals("MobLand", last.media.fields["title"])
        assertTrue(sent.none { it.positionMs < 30_000 })
        assertNull(sent.firstOrNull { (it.media.durationMs ?: 0) < min(40, 0) })
    }
}
