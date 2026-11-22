package ro.ak.vodunify.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.DiscoverFilters
import ro.ak.vodunify.core.catalog.DiscoverService
import ro.ak.vodunify.core.catalog.Genre
import ro.ak.vodunify.core.catalog.SearchResult
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.ui.components.CatalogError
import ro.ak.vodunify.ui.components.toCatalogError
import javax.inject.Inject

data class DiscoverUiState(
    val filters: DiscoverFilters = DiscoverFilters(),
    val genres: List<Genre> = emptyList(),
    /** Serviciile din registru, fiecare în biblioteca ta (RO / US); cele instalate primele. */
    val services: List<DiscoverService> = emptyList(),
    val items: List<CatalogItem> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val loading: Boolean = false,
    val error: CatalogError? = null,
) {
    val canLoadMore: Boolean get() = !loading && page in 1 until totalPages
}

/** Descoperă: răsfoirea catalogului TMDb cu filtre (tip, ordine, gen, perioadă, serviciu, notă, țară, limbă). */
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
    playback: PlaybackPreferences,
    private val resolver: TargetResolver,
) : ViewModel() {

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private var settings = CatalogSettings()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            combine(prefs.settings, playback.settings) { c, p -> c to p }.collect { (c, p) ->
                settings = c
                val installed = withContext(Dispatchers.IO) { runCatching { resolver.installedApps().keys }.getOrDefault(emptySet()) }
                val services = StreamingService.active.map { s ->
                    val app = TargetApp.byId(s.appId)
                    DiscoverService(s.displayName, s.tmdbProviderIds, p.marketOf(s), owned = app != null && app in installed)
                }.sortedBy { !it.owned }
                _state.update { it.copy(services = services) }
                loadGenres()
                reload()
            }
        }
    }

    private fun loadGenres() {
        val kind = _state.value.filters.kind
        viewModelScope.launch {
            runCatching { catalog.genresFor(kind, settings) }.onSuccess { g -> _state.update { it.copy(genres = g) } }
        }
    }

    fun setKind(kind: TitleKind) {
        if (kind == _state.value.filters.kind) return
        // Genurile diferă între filme și seriale.
        setFilters { it.copy(kind = kind, genreId = null) }
        loadGenres()
    }

    fun setFilters(change: (DiscoverFilters) -> DiscoverFilters) {
        _state.update { it.copy(filters = change(it.filters)) }
        reload()
    }

    fun reset() = setFilters { DiscoverFilters(kind = it.kind) }

    fun reload() {
        job?.cancel()
        job = viewModelScope.launch { fetch(1, append = false) }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        job?.cancel()
        job = viewModelScope.launch { fetch(s.page + 1, append = true) }
    }

    private suspend fun fetch(page: Int, append: Boolean) {
        val filters = _state.value.filters
        _state.update { it.copy(loading = true, error = null, items = if (append) it.items else emptyList()) }
        try {
            val r = catalog.discoverTitles(filters, settings, page)
            val items = r.results.filterIsInstance<SearchResult.Title>().map { it.item }
            _state.update {
                it.copy(
                    items = if (append) (it.items + items).distinctBy { i -> i.mediaKey } else items,
                    page = r.page,
                    totalPages = r.totalPages,
                    loading = false,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.toCatalogError()) }
        }
    }
}
