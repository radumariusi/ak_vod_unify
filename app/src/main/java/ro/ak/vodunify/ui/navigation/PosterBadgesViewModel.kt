package ro.ak.vodunify.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadges
import javax.inject.Inject

/** Dă posterelor sigla lor (platforma ta pe care e titlul sau playerul standard). */
@HiltViewModel
class PosterBadgesViewModel @Inject constructor(private val badges: PosterBadges) : ViewModel() {
    suspend fun badge(kind: TitleKind, id: Long): PosterBadge? = badges.badge(kind, id)
}
