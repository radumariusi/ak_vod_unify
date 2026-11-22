package ro.ak.vodunify.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.imdb.TitleImdbRatings
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadges
import ro.ak.vodunify.core.playback.ServiceLogos
import ro.ak.vodunify.core.playback.StreamingService
import javax.inject.Inject

/** Dă posterelor sigla lor (platforma ta pe care e titlul sau playerul standard), siglele pătrate ale serviciilor și nota IMDb (OMDb). */
@HiltViewModel
class PosterBadgesViewModel @Inject constructor(
    private val badges: PosterBadges,
    private val imdb: TitleImdbRatings,
    private val logos: ServiceLogos,
) : ViewModel() {
    suspend fun serviceLogoForPackage(packageName: String): String? = logos.forPackage(packageName)

    suspend fun serviceLogo(serviceId: String): String? = StreamingService.byId(serviceId)?.let { logos.forService(it) }

    suspend fun badge(kind: TitleKind, id: Long): PosterBadge? = badges.badge(kind, id)

    suspend fun imdbRating(kind: TitleKind, id: Long): Double? = imdb.rating(kind, id)
}
