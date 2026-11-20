package ro.ak.vodunify.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.Genre
import ro.ak.vodunify.core.catalog.SearchFilters
import ro.ak.vodunify.core.catalog.SearchResult
import ro.ak.vodunify.core.catalog.SearchType
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.ui.components.CatalogError
import ro.ak.vodunify.ui.components.toCatalogError
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val genres: List<Genre> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val loading: Boolean = false,
    val error: CatalogError? = null,
) {
    val canLoadMore: Boolean get() = !loading && page in 1 until totalPages
    val isIdle: Boolean get() = query.isBlank() && filters.isEmpty
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var settings = CatalogSettings()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            prefs.settings.collect { s ->
                settings = s
                loadGenres()
                runSearch(debounce = false)
            }
        }
    }

    private fun loadGenres() {
        viewModelScope.launch {
            runCatching { catalog.genres(settings) }
                .onSuccess { g -> _state.update { it.copy(genres = g) } }
        }
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        runSearch(debounce = true)
    }

    fun onTypeChange(type: SearchType) = updateFilters { it.copy(type = type) }
    fun onGenreChange(genreId: Int?) = updateFilters { it.copy(genreId = genreId) }
    fun onYearChange(year: Int?) = updateFilters { it.copy(year = year) }

    private fun updateFilters(change: (SearchFilters) -> SearchFilters) {
        _state.update { it.copy(filters = change(it.filters)) }
        runSearch(debounce = false)
    }

    fun submit() = runSearch(debounce = false)

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { fetch(s.query, s.filters, s.page + 1, append = true) }
    }

    private fun runSearch(debounce: Boolean) {
        searchJob?.cancel()
        val s = _state.value
        if (s.isIdle) {
            _state.update { it.copy(results = emptyList(), page = 0, totalPages = 0, loading = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            if (debounce) delay(450)
            fetch(s.query, s.filters, 1, append = false)
        }
    }

    private suspend fun fetch(query: String, filters: SearchFilters, page: Int, append: Boolean) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val result = catalog.search(query, filters, settings, page)
            _state.update {
                it.copy(
                    results = if (append) (it.results + result.results).distinct() else result.results,
                    page = result.page,
                    totalPages = result.totalPages,
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
