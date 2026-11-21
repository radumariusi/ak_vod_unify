package ro.ak.vodunify.core.calendar.cinema

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer
import java.time.LocalDate

/** A film in a Romanian cinema program, with the day it opens in Romania. */
data class RoCinemaRelease(
    /** Title as the cinema writes it (Romanian, maybe cut short). */
    val title: String,
    val originalTitle: String? = null,
    /** Production year, when the source gives it. */
    val year: Int? = null,
    /** Romanian premiere ("Premiera" / release date in the program). */
    val date: LocalDate,
    /** Who says so, shown in the app: "Cinema City", "Orange Film". */
    val source: String,
    /** The source cuts long titles (Cinema City stops at ~40 characters): match by prefix. */
    val truncated: Boolean = false,
)

/** One place that lists Romanian cinema premieres. */
interface RoCinemaSource {
    val name: String

    /** Films in the program around [today] (running now and, where the source has them, coming soon). */
    suspend fun releases(today: LocalDate): List<RoCinemaRelease>
}

/**
 * The Romanian cinema program from every source, merged and cached.
 *
 * TMDb stays the source for dates far ahead; this one confirms or corrects the
 * real Romanian premiere once a film is in a cinema's program (Cinema City lists
 * its films about a month ahead; Orange Film has the current week in every chain).
 */
class RoCinemaProgram(
    private val sources: List<RoCinemaSource>,
    private val ttlMillis: Long = 6 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    @Volatile private var cached: Triple<LocalDate, Long, List<RoCinemaRelease>>? = null

    /** All films, deduplicated: one per title, the earliest premiere (a source that failed is skipped). */
    suspend fun load(today: LocalDate): List<RoCinemaRelease> = mutex.withLock {
        cached?.let { (day, at, list) -> if (day == today && now() - at < ttlMillis) return@withLock list }
        val all = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        source.releases(today)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
        }
        if (all.all { it == null } && cached != null) return@withLock cached!!.third
        val merged = merge(all.filterNotNull().flatten())
        cached = Triple(today, now(), merged)
        merged
    }

    companion object {
        internal fun merge(list: List<RoCinemaRelease>): List<RoCinemaRelease> {
            val out = mutableListOf<RoCinemaRelease>()
            // Full titles first, so a cut one from Cinema City merges into them.
            for (r in list.sortedWith(compareBy({ it.truncated }, { it.date }))) {
                val i = out.indexOfFirst { o -> sameFilm(o, r) }
                if (i < 0) {
                    out += r
                } else {
                    val o = out[i]
                    out[i] = o.copy(
                        originalTitle = o.originalTitle ?: r.originalTitle,
                        year = o.year ?: r.year,
                        date = minOf(o.date, r.date),
                        source = if (r.date < o.date) r.source else o.source,
                    )
                }
            }
            return out
        }

        private fun sameFilm(a: RoCinemaRelease, b: RoCinemaRelease): Boolean =
            titlesMatch(a.title, b.title, a.truncated || b.truncated) ||
                (a.originalTitle != null && b.originalTitle != null && titlesMatch(a.originalTitle, b.originalTitle, false))

        /**
         * The film among [program] that a TMDb movie is, by any of its [titles]
         * (shown, original, Romanian translation) and its [year] (±1, when both are known).
         */
        fun find(program: List<RoCinemaRelease>, titles: List<String>, year: Int?): RoCinemaRelease? {
            val wanted = titles.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()
            if (wanted.isEmpty()) return null
            return program
                .filter { r -> year == null || r.year == null || kotlin.math.abs(r.year - year) <= 1 }
                // A film from years ago is a re-run (or another film with the same name), not this premiere.
                .filter { r -> year == null || year >= r.date.year - 3 }
                .firstOrNull { r ->
                    val names = listOfNotNull(r.title, r.originalTitle)
                    names.any { n -> wanted.any { w -> titleMatches(n, w, r.truncated && n == r.title) } }
                }
        }

        private fun titlesMatch(a: String, b: String, prefix: Boolean): Boolean {
            val na = normalize(a)
            val nb = normalize(b)
            if (na.isEmpty() || nb.isEmpty()) return false
            if (na == nb) return true
            if (!prefix) return false
            val (short, long) = if (na.length <= nb.length) na to nb else nb to na
            return short.length >= MIN_PREFIX && long.startsWith(short)
        }

        /** [cinemaName] from the program against an already normalized TMDb title. */
        private fun titleMatches(cinemaName: String, normalizedTmdb: String, truncated: Boolean): Boolean {
            val n = normalize(cinemaName)
            if (n.isEmpty()) return false
            if (n == normalizedTmdb) return true
            return truncated && n.length >= MIN_PREFIX && normalizedTmdb.startsWith(n)
        }

        private const val MIN_PREFIX = 12

        private val formatWords = setOf("dub", "dublat", "dublaj", "sub", "subtitrat", "2d", "3d", "4dx", "imax", "screenx", "vip", "ro")

        /** Lower case, no diacritics or punctuation, no trailing format words ("dub", "3D", …). */
        fun normalize(title: String): String {
            val plain = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace("&", " si ")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
            val words = plain.split(' ').filter { it.isNotEmpty() }.toMutableList()
            while (words.size > 1 && words.last() in formatWords) words.removeAt(words.lastIndex)
            return words.joinToString(" ")
        }

        /** The title with format words and brackets dropped, for a TMDb search. */
        fun searchQuery(title: String): String {
            var t = title.replace(Regex("\\([^)]*\\)"), " ").trim()
            while (true) {
                val last = t.substringAfterLast(' ', t)
                if (t.contains(' ') && last.lowercase().trim('-', '.') in formatWords) t = t.substringBeforeLast(' ').trim() else break
            }
            return t.trimEnd(' ', '-', ':', ',')
        }
    }
}
