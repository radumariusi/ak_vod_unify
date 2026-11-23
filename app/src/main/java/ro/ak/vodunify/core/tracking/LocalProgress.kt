package ro.ak.vodunify.core.tracking

import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.db.WatchEventEntity
import java.time.Instant

/** Pe scurt, cât ai văzut dintr-un serial (din istoricul local). */
data class SeriesSummary(
    val title: String,
    val tmdbId: Long?,
    val imdbId: String?,
    val watched: Int,
    val lastSeason: Int,
    val lastEpisode: Int,
    val lastAt: Long,
)

/**
 * Progresul pe seriale calculat din istoricul local (AK Scrobbler, marcări manuale, importuri),
 * fără Trakt/SIMKL. Funcții pure, testabile.
 */
object LocalProgress {

    private fun WatchEventEntity.number(): EpisodeNumber? {
        if (ref.type != MediaType.EPISODE.name) return null
        val s = ref.season ?: return null
        val e = ref.episode ?: return null
        return if (s >= 0 && e > 0) EpisodeNumber(s, e) else null
    }

    /** Pe fiecare serial (cheia titlului): episoade distincte văzute și ultimul văzut. */
    fun summaries(events: List<WatchEventEntity>): Map<String, SeriesSummary> =
        events.filter { (it.number()?.season ?: 0) > 0 }.groupBy { it.ref.mediaKey }.mapValues { (_, list) ->
            val last = list.maxBy { it.watchedAt }
            SeriesSummary(
                title = last.ref.title,
                tmdbId = list.firstNotNullOfOrNull { it.ref.tmdbId },
                imdbId = list.firstNotNullOfOrNull { it.ref.imdbId },
                watched = list.mapNotNull { it.number() }.toSet().size,
                lastSeason = last.ref.season ?: 0,
                lastEpisode = last.ref.episode ?: 0,
                lastAt = last.watchedAt,
            )
        }

    /**
     * Progresul complet al unui serial. [seasonCounts]: sezon → număr de episoade (fără „Speciale”).
     * Următorul episod = cel de după ultimul văzut (în ordinea sezoanelor), dacă există.
     */
    fun show(show: MediaRef, events: List<WatchEventEntity>, seasonCounts: Map<Int, Int>, source: String): ShowProgress {
        val mine = events.filter { it.ref.mediaKey == show.titleKey }
        val watched = mine.mapNotNull { it.number() }.toSet()
        val counts = seasonCounts.filterKeys { it > 0 }.toSortedMap()
        val inRange = watched.count { (counts[it.season] ?: 0) >= it.episode }
        val last = mine.filter { (it.number()?.season ?: 0) > 0 }.maxByOrNull { it.watchedAt }
        val next = last?.number()?.let { nextAfter(it, counts, watched) }
            ?: if (watched.none { it.season > 0 }) counts.keys.firstOrNull()?.let { EpisodeNumber(it, 1) } else null
        return ShowProgress(
            show = show,
            aired = counts.values.sum(),
            completed = inRange,
            watched = watched,
            next = next?.let { n -> show.copy(type = MediaType.EPISODE, season = n.season, episode = n.episode, episodeTitle = null) },
            lastWatchedAt = last?.let { Instant.ofEpochMilli(it.watchedAt) },
            source = source,
        )
    }

    /** Primul episod nevăzut de după [from]. */
    private fun nextAfter(from: EpisodeNumber, counts: Map<Int, Int>, watched: Set<EpisodeNumber>): EpisodeNumber? {
        var s = from.season
        var e = from.episode + 1
        while (true) {
            val count = counts[s]
            if (count == null) {
                s = counts.keys.firstOrNull { it > s } ?: return null
                e = 1
                continue
            }
            if (e > count) {
                s = counts.keys.firstOrNull { it > s } ?: return null
                e = 1
                continue
            }
            val n = EpisodeNumber(s, e)
            if (n !in watched) return n
            e++
        }
    }
}
