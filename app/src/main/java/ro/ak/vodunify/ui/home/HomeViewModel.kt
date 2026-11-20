package ro.ak.vodunify.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import javax.inject.Inject

/** Ecranul principal: rânduri din TMDb pentru piața și limba din Setări. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
) : ViewModel() {

    private val reload = MutableStateFlow(0)

    val state: StateFlow<LoadState<HomeCatalog>> = combine(prefs.settings, reload) { s, _ -> s }
        .flatMapLatest { settings ->
            flow {
                emit(LoadState.Loading)
                emit(loadCatching { catalog.home(settings) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun retry() {
        reload.value++
    }
}
