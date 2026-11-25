package ro.ak.vodunify.core.tracking

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.SeasonSummary
import ro.ak.vodunify.core.catalog.TitleDetails
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.prefs.CatalogPreferences
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Marchează un sezon întreg sau tot serialul văzut / nevăzut (episoadele apărute deja, din TMDb). */
@Singleton
class SeriesMarker @Inject constructor(
    private val catalog: CatalogRepository,
    private val tracking: TrackingRepository,
    private val prefs: CatalogPreferences,
) {
    /** [seasons] = null: toate sezoanele (fără „Speciale”). Întoarce câte episoade a marcat. */
    suspend fun mark(
        tvId: Long,
        title: String,
        year: Int?,
        imdb: String?,
        watched: Boolean,
        seasons: List<Int>? = null,
        details: TitleDetails? = null,
    ): Int {
        val show = MediaRef(MediaType.SHOW, ExternalIds(tmdb = tvId, imdb = imdb), title, year)
        if (!watched) return tracking.clearSeriesHistory(show, seasons)
        val settings = prefs.settings.first()
        val titleDetails = details ?: catalog.details(TitleKind.TV, tvId, settings)
        val counts = airedCounts(titleDetails)
        val numbers = seasons ?: titleDetails.seasons.map { it.number }.filter { it > 0 }
        val list = if (titleDetails.lastAiredEpisode != null && numbers.all { it > 0 }) {
            numbers.flatMap { season -> (1..counts.getOrDefault(season, 0)).map { EpisodeNumber(season, it) } }
        } else {
            coroutineScope {
                numbers.map { n -> async { aired(catalog.season(tvId, n, settings).episodes) } }
                    .awaitAll().flatten().map { EpisodeNumber(it.season, it.number) }
            }
        }
        check(list.isNotEmpty()) { "TMDb nu indică niciun episod apărut." }
        tracking.markEpisodesWatched(show, list, wholeShow = seasons == null)
        return list.size
    }

    companion object {
        /** Numărul total de episoade pe fiecare sezon normal (fără „Speciale”). */
        fun totalCounts(details: TitleDetails): Map<Int, Int> =
            details.seasons.asSequence()
                .filter { it.number > 0 && it.episodeCount > 0 }
                .associate { it.number to it.episodeCount }

        /** TMDb include și episoadele viitoare în totalul sezonului. */
        fun airedCounts(details: TitleDetails): Map<Int, Int> = airedCounts(details.seasons, details.lastAiredEpisode)

        internal fun airedCounts(
            seasons: List<SeasonSummary>,
            lastAiredEpisode: EpisodeAir?,
            today: LocalDate = LocalDate.now(),
        ): Map<Int, Int> {
            val last = lastAiredEpisode?.takeIf { it.season > 0 && it.episode > 0 }
            return seasons.asSequence().filter { it.number > 0 }.mapNotNull { season ->
                val startsLater = season.airDate?.let { runCatching { LocalDate.parse(it).isAfter(today) }.getOrDefault(false) } == true
                val count = when {
                    startsLater -> 0
                    last == null || season.number < last.season -> season.episodeCount
                    season.number == last.season -> minOf(last.episode, season.episodeCount)
                    else -> 0
                }
                if (count > 0) season.number to count else null
            }.toMap()
        }

        /** Doar episoadele apărute (cu dată până azi): cele viitoare nu se pot vedea încă. */
        fun aired(episodes: List<Episode>, today: LocalDate = LocalDate.now()): List<Episode> = episodes.filter { e ->
            e.airDate?.let { runCatching { !LocalDate.parse(it).isAfter(today) }.getOrDefault(false) } == true
        }
    }
}
