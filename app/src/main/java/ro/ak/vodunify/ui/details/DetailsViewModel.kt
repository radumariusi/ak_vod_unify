package ro.ak.vodunify.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.Season
import ro.ak.vodunify.core.catalog.TitleDetails
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
) : ViewModel() {

    val kind: TitleKind = TitleKind.fromPath(savedState.get<String>("kind")) ?: TitleKind.MOVIE
    private val id: Long = savedState.get<Long>("id") ?: 0L

    private val reload = MutableStateFlow(0)
    private val settings = prefs.settings

    val state: StateFlow<LoadState<TitleDetails>> = combine(settings, reload) { s, _ -> s }
        .flatMapLatest { s ->
            flow {
                emit(LoadState.Loading)
                emit(loadCatching { catalog.details(kind, id, s) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Sezonul selectat (null = primul sezon normal, ales automat de UI). */
    private val selectedSeason = MutableStateFlow<Int?>(null)
    val season: StateFlow<Int?> = selectedSeason

    val episodes: StateFlow<LoadState<Season>?> = combine(settings, selectedSeason) { s, n -> s to n }
        .flatMapLatest { (s, n) ->
            if (kind != TitleKind.TV || n == null) {
                flowOf(null)
            } else {
                flow {
                    emit(LoadState.Loading)
                    emit(loadCatching { catalog.season(id, n, s) })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectSeason(number: Int) {
        selectedSeason.value = number
    }

    fun retry() {
        reload.value++
    }
}
