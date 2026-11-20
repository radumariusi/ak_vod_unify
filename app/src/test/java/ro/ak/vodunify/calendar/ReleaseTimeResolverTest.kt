package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import ro.ak.vodunify.core.calendar.time.ReleaseTimeResolver
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReleaseTimeResolverTest {
    private val resolver = ReleaseTimeResolver()
    private val netflix = StreamingCatalog.byId("netflix")!!
    private val max = StreamingCatalog.byId("max")!!
    private val peacock = StreamingCatalog.byId("peacock")!!
    private val skyShowtime = StreamingCatalog.byId("skyshowtime")!!

    @Test
    fun `US broadcast episode lands next night in Romania`() {
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("US"), setOf(6), null, Region.US)
        assertEquals(RomaniaTime.ZONE, r.at.zone)
        // 21:00 New York (EST) = 04:00 Bucharest (EET) next day
        assertEquals(LocalDateTime.of(2026, 11, 20, 4, 0), r.at.toLocalDateTime())
        assertTrue(r.approximate)
    }

    @Test
    fun `Netflix original drops at midnight Pacific`() {
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("US"), setOf(213), netflix, Region.RO)
        // 00:00 PST = 10:00 EET same day
        assertEquals(LocalDateTime.of(2026, 11, 19, 10, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `summer time is handled on both sides`() {
        val r = resolver.resolve(LocalDate.of(2026, 7, 1), listOf("US"), setOf(213), netflix, Region.US)
        // 00:00 PDT = 10:00 EEST
        assertEquals(LocalDateTime.of(2026, 7, 1, 10, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `HBO is treated as cable, not streaming`() {
        val r = resolver.resolve(LocalDate.of(2026, 11, 22), listOf("US"), setOf(49), max, Region.RO)
        assertEquals(LocalDateTime.of(2026, 11, 23, 4, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `UK show at 21 London is 23 in Romania`() {
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("GB"), emptySet(), null, null)
        assertEquals(LocalDateTime.of(2026, 11, 19, 23, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `licensed US broadcast show arrives next morning on a US app`() {
        // NBC (network 6) episode on Thursday 21:00 ET, on Peacock US: 03:00 ET Friday = 10:00 Romania.
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("US"), setOf(6), peacock, Region.US)
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 0), r.at.toLocalDateTime())
        assertTrue(r.delayed)
    }

    @Test
    fun `licensed US broadcast show arrives at noon on a RO app`() {
        // Original airing is 04:00 Romania on the 20th, so SkyShowtime RO gets it at 12:00 the same day.
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("US"), setOf(6), skyShowtime, Region.RO)
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `licensed UK evening show on a RO app moves to next day noon`() {
        // 21:00 London = 23:00 Romania, after noon, so the next day at 12:00.
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("GB"), emptySet(), skyShowtime, Region.RO)
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 0), r.at.toLocalDateTime())
    }

    @Test
    fun `own network is simultaneous in both regions`() {
        val us = resolver.resolve(LocalDate.of(2026, 11, 22), listOf("US"), setOf(49), max, Region.US)
        val ro = resolver.resolve(LocalDate.of(2026, 11, 22), listOf("US"), setOf(49), max, Region.RO)
        assertEquals(us.at, ro.at)
        assertEquals(false, ro.delayed)
    }

    @Test
    fun `Romanian show on a RO app is not delayed`() {
        val voyo = StreamingCatalog.byId("voyo")!!
        val r = resolver.resolve(LocalDate.of(2026, 11, 19), listOf("RO"), emptySet(), voyo, Region.RO)
        assertEquals(LocalDateTime.of(2026, 11, 19, 21, 0), r.at.toLocalDateTime())
        assertEquals(false, r.delayed)
    }
}
