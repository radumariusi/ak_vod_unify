package ro.ak.vodunify.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.imdb.TitleImdbRatings
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadges
import ro.ak.vodunify.core.playback.ServiceLogos
import ro.ak.vodunify.core.playback.StreamingService
import javax.inject.Inject

/**
 * Dă posterelor sigla lor (platforma ta pe care e titlul sau playerul standard), siglele pătrate ale serviciilor
 * și nota IMDb (OMDb). `peek*` = ce se știe deja, fără așteptare (primul cadru al posterului); restul se află
 * în fundal, nu pe firul interfeței.
 */
@HiltViewModel
class PosterBadgesViewModel @Inject constructor(
    private val badges: PosterBadges,
    private val imdb: TitleImdbRatings,
    private val logos: ServiceLogos,
) : ViewModel() {
    fun peekBadge(kind: TitleKind, id: Long): PosterBadge? = badges.peek(kind, id)

    suspend fun badge(kind: TitleKind, id: Long): PosterBadge? = withContext(Dispatchers.Default) { badges.badge(kind, id) }

    fun peekImdbRating(kind: TitleKind, id: Long): Double? = imdb.peek(kind, id)

    suspend fun imdbRating(kind: TitleKind, id: Long): Double? = imdb.rating(kind, id)

    fun peekServiceLogoForPackage(packageName: String): String? = logos.peekPackage(packageName)

    suspend fun serviceLogoForPackage(packageName: String): String? =
        withContext(Dispatchers.Default) { logos.forPackage(packageName) }

    fun peekServiceLogo(serviceId: String): String? = StreamingService.byId(serviceId)?.let { logos.peekService(it) }

    suspend fun serviceLogo(serviceId: String): String? =
        withContext(Dispatchers.Default) { StreamingService.byId(serviceId)?.let { logos.forService(it) } }
}
