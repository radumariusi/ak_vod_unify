package ro.ak.vodunify.ui.person

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
import kotlinx.coroutines.flow.stateIn
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.PersonDetails
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
) : ViewModel() {
    private val id: Long = savedState.get<Long>("id") ?: 0L
    private val reload = MutableStateFlow(0)

    val state: StateFlow<LoadState<PersonDetails>> = combine(prefs.settings, reload) { s, _ -> s }
        .flatMapLatest { s ->
            flow {
                emit(LoadState.Loading)
                emit(loadCatching { catalog.person(id, s) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun retry() {
        reload.value++
    }
}
