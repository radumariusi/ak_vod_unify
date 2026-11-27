package ro.ak.vodunify.core.calendar.anticipated

import ro.ak.vodunify.core.calendar.tmdb.TmdbApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate

/**
 * Where the "most anticipated new series" list comes from.
 *
 * Trakt is deliberately not used. The default is [TmdbAnticipatedSource]; a
 * Simkl (or other) source can be added later by implementing this interface,
 * since those services also return TMDB ids.
 */
interface AnticipatedShowsSource {
    /** Shown in the UI, e.g. "TMDB: cele mai populare premiere". */
    val label: String

    /** TMDB ids of new shows (season 1) that count as anticipated around [today]. */
    suspend fun showIds(tmdb: TmdbApi, today: LocalDate): Set<Int>
}

/**
 * Anticipated = among the most popular TMDB series premiering in a window around
 * today: the top [perWindow] shows premiering in the next [lookAheadDays] days
 * plus the top [perWindow] that premiered in the last [lookBackDays] days (so a
 * new show stays in for its whole first season).
 *
 * TMDB popularity is driven by page views, watchlists and votes on TMDB, so it
 * tracks hype well, but it is global: category filters (anime, K-drama, …)
 * still apply on top.
 */
class TmdbAnticipatedSource(
    private val perWindow: Int = 40,
    private val lookBackDays: Long = 150,
    private val lookAheadDays: Long = 120,
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) : AnticipatedShowsSource {

    override val label: String = "TMDB: cele mai populare premiere"

    @Volatile private var cached: Triple<LocalDate, Long, Set<Int>>? = null

    override suspend fun showIds(tmdb: TmdbApi, today: LocalDate): Set<Int> = coroutineScope {
        cached?.let { (day, at, ids) -> if (day == today && now() - at < ttlMillis) return@coroutineScope ids }
        val upcoming = async { top(tmdb, today, today.plusDays(lookAheadDays)) }
        val recent = async { top(tmdb, today.minusDays(lookBackDays), today.minusDays(1)) }
        val ids = upcoming.await() + recent.await()
        cached = Triple(today, now(), ids)
        ids
    }

    private suspend fun top(tmdb: TmdbApi, from: LocalDate, to: LocalDate): Set<Int> {
        val ids = LinkedHashSet<Int>()
        var page = 1
        while (ids.size < perWindow) {
            val response = tmdb.discoverTvPremieres(from, to, page)
            response.results.forEach { if (ids.size < perWindow) ids += it.id }
            if (page >= response.totalPages || response.results.isEmpty()) break
            page++
        }
        return ids
    }
}

/** For tests and for when no list is wanted. */
class FixedAnticipatedSource(private val ids: Set<Int>, override val label: String = "listă fixă") : AnticipatedShowsSource {
    override suspend fun showIds(tmdb: TmdbApi, today: LocalDate): Set<Int> = ids
}
