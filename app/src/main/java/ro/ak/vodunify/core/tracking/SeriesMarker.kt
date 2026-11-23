package ro.ak.vodunify.core.tracking

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.Episode
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
    suspend fun mark(tvId: Long, title: String, year: Int?, imdb: String?, watched: Boolean, seasons: List<Int>? = null): Int {
        val settings = prefs.settings.first()
        val numbers = seasons ?: catalog.details(TitleKind.TV, tvId, settings).seasons.map { it.number }.filter { it > 0 }
        val episodes = coroutineScope {
            numbers.map { n -> async { runCatching { catalog.season(tvId, n, settings).episodes }.getOrThrow() } }.awaitAll().flatten()
        }
        val list = aired(episodes).map { EpisodeNumber(it.season, it.number) }
        val show = MediaRef(MediaType.SHOW, ExternalIds(tmdb = tvId, imdb = imdb), title, year)
        tracking.setEpisodesWatched(show, list, watched)
        return list.size
    }

    companion object {
        /** Doar episoadele apărute (cu dată până azi): cele viitoare nu se pot vedea încă. */
        fun aired(episodes: List<Episode>, today: LocalDate = LocalDate.now()): List<Episode> = episodes.filter { e ->
            e.airDate?.let { runCatching { !LocalDate.parse(it).isAfter(today) }.getOrDefault(false) } == true
        }
    }
}
