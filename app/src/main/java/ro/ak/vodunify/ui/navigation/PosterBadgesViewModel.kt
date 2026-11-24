package ro.ak.vodunify.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.imdb.TitleImdbRatings
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.PosterActivity
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterStage
import ro.ak.vodunify.core.playback.PosterBadgeRequest
import ro.ak.vodunify.core.playback.PosterBadgeSettings
import ro.ak.vodunify.core.playback.PosterBadges
import ro.ak.vodunify.core.playback.ServiceLogos
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject

/**
 * Dă posterelor sigla lor (platforma ta pe care e titlul sau playerul standard), siglele pătrate ale serviciilor
 * și nota IMDb (OMDb). `peek*` = ce se știe deja, fără așteptare (primul cadru al posterului); restul se află
 * în fundal, nu pe firul interfeței.
 */
@HiltViewModel
class PosterBadgesViewModel @Inject constructor(
    private val badges: PosterBadges,
    playback: PlaybackPreferences,
    tracking: TrackingRepository,
    private val imdb: TitleImdbRatings,
    private val logos: ServiceLogos,
) : ViewModel() {
    val playbackSettings = playback.settings
    val activity = combine(tracking.reconciled, tracking.completedPlaybackApps) { view, history ->
        val byTitle = view.library.associateBy { it.titleKey }
        val series = view.series
        buildMap {
            (byTitle.keys + series.keys + history.keys).forEach { key ->
                val entry = byTitle[key]
                val summary = series[key]
                val finished = history[key]
                val resume = entry?.resume?.winner
                val lastPlayback = resume?.takeIf { it.appPackage != null && it.updatedAt >= (finished?.at ?: 0L) }
                    ?.appPackage ?: finished?.appPackage
                val status = entry?.currentStatus
                val stage = when {
                    status == LibraryStatus.IN_PROGRESS || status == LibraryStatus.UP_NEXT || summary != null -> PosterStage.IN_PROGRESS
                    status == LibraryStatus.WATCHED -> PosterStage.WATCHED
                    else -> PosterStage.UNWATCHED
                }
                if (stage != PosterStage.UNWATCHED) {
                    put(key, PosterActivity(stage, lastPlayback, finished?.appPackage, summary?.watched, resume != null))
                }
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun peekBadges(request: PosterBadgeRequest, playback: PlaybackSettings, prefs: PosterBadgeSettings): List<PosterBadge>? =
        badges.peek(request, playback, prefs)

    suspend fun badges(request: PosterBadgeRequest, playback: PlaybackSettings, prefs: PosterBadgeSettings): List<PosterBadge> =
        withContext(Dispatchers.Default) { badges.badges(request, playback, prefs) }

    fun peekEpisodeTotal(id: Long): Int? = badges.peekEpisodeTotal(id)

    suspend fun episodeTotal(id: Long): Int? = withContext(Dispatchers.Default) { badges.episodeTotal(id) }

    fun peekImdbRating(kind: TitleKind, id: Long): Double? = imdb.peek(kind, id)

    suspend fun imdbRating(kind: TitleKind, id: Long): Double? = imdb.rating(kind, id)

    fun peekServiceLogoForPackage(packageName: String): String? = logos.peekPackage(packageName)

    suspend fun serviceLogoForPackage(packageName: String): String? =
        withContext(Dispatchers.Default) { logos.forPackage(packageName) }

    fun peekServiceLogo(serviceId: String): String? = StreamingService.byId(serviceId)?.let { logos.peekService(it) }

    suspend fun serviceLogo(serviceId: String): String? =
        withContext(Dispatchers.Default) { StreamingService.byId(serviceId)?.let { logos.forService(it) } }
}
