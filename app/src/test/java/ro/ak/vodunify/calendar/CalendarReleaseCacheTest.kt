package ro.ak.vodunify.calendar

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.calendar.android.CalendarPeriod
import ro.ak.vodunify.core.calendar.android.CalendarReleaseCache
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class CalendarReleaseCacheTest {
    @Serializable
    private data class Releases(val titles: List<String>, val complete: Boolean = true)

    private class TestCacheDao : TmdbCacheDao {
        private val rows = mutableMapOf<String, TmdbCacheEntity>()
        override suspend fun get(key: String): TmdbCacheEntity? = rows[key]
        override suspend fun window(first: String, last: String): List<TmdbCacheEntity> =
            rows.values.filter { it.key in first..last }.sortedBy { it.key }
        override suspend fun put(entry: TmdbCacheEntity) { rows[entry.key] = entry }
        override suspend fun putAll(entries: List<TmdbCacheEntity>) { entries.forEach { put(it) } }
        override suspend fun prune(olderThan: Long) { rows.values.removeAll { it.fetchedAt < olderThan } }
        override suspend fun clear() { rows.clear() }
    }

    private val dao = TestCacheDao()
    private var time = 10_000L
    private val day = LocalDate.of(2026, 11, 24)

    private fun cache() = CalendarReleaseCache(
        dao, Releases.serializer(),
        slice = { result, date -> result.copy(titles = result.titles.filter { it.startsWith("$date ") }) },
        merge = { values, _ -> Releases(values.flatMap { it.titles }.distinct(), values.all { it.complete }) },
        complete = Releases::complete,
        now = { time },
    )

    @Test fun sixtyDaysServeDayAndWeekFromDiskIncludingEmptyDates() = runTest {
        val upcoming = CalendarPeriod(day, day.plusDays(59))
        cache().load("episodes", upcoming) { period, _ ->
            assertEquals(upcoming, period)
            Releases(listOf("$day Primul titlu", "${day.plusDays(6)} Al doilea titlu"))
        }

        val reopened = cache()
        val week = CalendarPeriod(day, day.plusDays(6))
        val stored = reopened.read("episodes", week)
        assertTrue(stored.fresh)
        assertEquals(2, stored.value?.titles?.size)
        val emptyDay = CalendarPeriod(day.plusDays(2), day.plusDays(2))
        assertTrue(reopened.read("episodes", emptyDay).fresh)
        assertEquals(emptyList<String>(), reopened.load("episodes", emptyDay) { _, _ -> error("A venit o cerere pentru o zi deja încărcată") }.titles)
        assertFalse(reopened.read("movies", week).fresh)
    }

    @Test fun theMonthLoadsOnlyDatesNotCoveredByUpcomingResults() = runTest {
        val cache = cache()
        cache.load("episodes", CalendarPeriod(day, day.plusDays(59))) { _, _ -> Releases(listOf("$day Deja cunoscut")) }
        val month = CalendarPeriod(day.withDayOfMonth(1), day.withDayOfMonth(day.lengthOfMonth()))
        val requests = mutableListOf<CalendarPeriod>()
        val result = cache.load("episodes", month) { gap, _ ->
            requests += gap
            Releases(listOf("${month.from} Film nou"))
        }
        assertEquals(listOf(CalendarPeriod(month.from, day.minusDays(1))), requests)
        assertEquals(listOf("${month.from} Film nou", "$day Deja cunoscut"), result.titles)
        assertTrue(cache.read("episodes", month).fresh)
    }

    @Test fun staleDataIsVisibleAndAfterOneDayOnlyTheRequestedRangeIsRefreshed() = runTest {
        val cache = cache()
        cache.load("episodes", CalendarPeriod(day, day.plusDays(59))) { _, _ -> Releases(listOf("$day Titlu vechi")) }
        time += TimeUnit.DAYS.toMillis(1)
        val today = CalendarPeriod(day, day)
        val stale = cache.read("episodes", today)
        assertFalse(stale.fresh)
        assertEquals(listOf("$day Titlu vechi"), stale.value?.titles)
        val loaded = cache.load("episodes", today) { window, _ ->
            assertEquals(today, window)
            Releases(listOf("$day Titlu nou"))
        }
        assertEquals(listOf("$day Titlu nou"), loaded.titles)
    }

    @Test fun incompleteRefreshKeepsKnownTitlesAndCanRetrySooner() = runTest {
        val cache = cache()
        val today = CalendarPeriod(day, day)
        cache.load("episodes", today) { _, _ -> Releases(listOf("$day Titlu salvat")) }
        val incomplete = cache.load("episodes", today, force = true) { _, _ -> Releases(emptyList(), complete = false) }
        assertEquals(listOf("$day Titlu salvat"), incomplete.titles)
        time += TimeUnit.MINUTES.toMillis(16)
        assertFalse(cache.read("episodes", today).fresh)
        assertTrue(cache.load("episodes", today) { _, _ -> Releases(emptyList()) }.titles.isEmpty())
    }
}
