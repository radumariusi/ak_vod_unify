package ro.ak.vodunify.core.calendar.anticipated

import ro.ak.vodunify.core.calendar.tmdb.TmdbApi
import java.time.LocalDate

/**
 * Where the "most anticipated movies" list comes from. Like
 * [AnticipatedShowsSource], Trakt is not used and Simkl (or another service)
 * can be plugged in later.
 */
interface AnticipatedMoviesSource {
    val label: String

    /** TMDB ids of movies that count as anticipated around [today]. */
    suspend fun movieIds(tmdb: TmdbApi, today: LocalDate): Set<Int>
}

/**
 * Anticipated = among the most popular TMDB movies whose first release is in a
 * window around today: the top [upcomingCount] coming out in the next
 * [lookAheadDays] days plus the top [recentCount] that came out in the last
 * [lookBackDays] days, so a hyped movie stays in from its cinema release until
 * its digital and streaming releases a few months later.
 *
 * Once a movie has enough votes the rating thresholds decide instead, so this
 * list mostly matters for cinema releases and fresh streaming premieres.
 */
class TmdbAnticipatedMoviesSource(
    private val upcomingCount: Int = 60,
    private val recentCount: Int = 80,
    private val lookAheadDays: Long = 180,
    private val lookBackDays: Long = 270,
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) : AnticipatedMoviesSource {

    override val label: String = "TMDB: cele mai populare filme noi"

    @Volatile private var cached: Triple<LocalDate, Long, Set<Int>>? = null

    override suspend fun movieIds(tmdb: TmdbApi, today: LocalDate): Set<Int> {
        cached?.let { (day, at, ids) -> if (day == today && now() - at < ttlMillis) return ids }
        val upcoming = top(tmdb, today, today.plusDays(lookAheadDays), upcomingCount)
        val recent = top(tmdb, today.minusDays(lookBackDays), today.minusDays(1), recentCount)
        val ids = upcoming + recent
        cached = Triple(today, now(), ids)
        return ids
    }

    private suspend fun top(tmdb: TmdbApi, from: LocalDate, to: LocalDate, count: Int): Set<Int> {
        val ids = LinkedHashSet<Int>()
        var page = 1
        while (ids.size < count) {
            val response = tmdb.discoverMoviePremieres(from, to, page)
            response.results.forEach { if (ids.size < count && !it.adult) ids += it.id }
            if (page >= response.totalPages || response.results.isEmpty()) break
            page++
        }
        return ids
    }
}

/** For tests and for when no list is wanted. */
class FixedAnticipatedMoviesSource(
    private val ids: Set<Int>,
    override val label: String = "listă fixă",
) : AnticipatedMoviesSource {
    override suspend fun movieIds(tmdb: TmdbApi, today: LocalDate): Set<Int> = ids
}
