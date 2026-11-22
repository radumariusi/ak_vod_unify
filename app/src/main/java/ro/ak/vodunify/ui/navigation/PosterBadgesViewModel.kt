package ro.ak.vodunify.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.imdb.TitleImdbRatings
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadges
import javax.inject.Inject

/** Dă posterelor sigla lor (platforma ta pe care e titlul sau playerul standard) și nota IMDb. */
@HiltViewModel
class PosterBadgesViewModel @Inject constructor(
    private val badges: PosterBadges,
    private val imdb: TitleImdbRatings,
) : ViewModel() {
    suspend fun badge(kind: TitleKind, id: Long): PosterBadge? = badges.badge(kind, id)

    suspend fun imdbRating(kind: TitleKind, id: Long): Double? = imdb.rating(kind, id)
}
