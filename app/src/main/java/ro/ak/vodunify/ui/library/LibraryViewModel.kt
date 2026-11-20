package ro.ak.vodunify.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class LibraryUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val filter: LibraryFilter = LibraryFilter(),
    val sources: List<SourceOption> = emptyList(),
    val counts: Map<String?, Int> = emptyMap(),
    val items: List<LibraryItem> = emptyList(),
    val total: Int = 0,
)

/** Biblioteca: tot ce știu Trakt și SIMKL, reconciliat, cu filtru pe sursă și stare. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tracking: TrackingRepository,
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val names = tracking.trackers.associate { it.id to it.displayName } + ro.ak.vodunify.core.imports.ImportSources.labels

    val state: StateFlow<LibraryUiState> = combine(
        tracking.library,
        filter,
        tracking.anyConnected,
        prefs.settings,
    ) { entries, f, connected, settings -> Input(entries, f, connected, settings) }
        .mapLatest { input ->
            val filtered = LibraryLogic.filter(input.entries, input.filter)
            val items = coroutineScope {
                filtered.take(MAX_ITEMS).map { e ->
                    async {
                        val kind = if (e.ref.type == "MOVIE") TitleKind.MOVIE else TitleKind.TV
                        val art = art(kind, e.ref.tmdbId, input.settings)
                        LibraryItem(
                            key = e.titleKey,
                            kind = kind,
                            tmdbId = e.ref.tmdbId,
                            title = art?.title ?: e.ref.title.ifBlank { "Titlu necunoscut" },
                            subtitle = LibraryLogic.subtitle(e, input.filter.tab),
                            posterUrl = art?.posterUrl,
                            progress = LibraryLogic.progress(e, input.filter.tab),
                            showsProgress = LibraryLogic.showsProgress(e, input.filter.tab),
                            sources = e.sources.sorted().map { names[it] ?: it },
                        )
                    }
                }.awaitAll()
            }
            val present = input.entries.flatMap { it.sources }.toSet()
            LibraryUiState(
                loading = false,
                connected = input.connected,
                filter = input.filter,
                sources = tracking.trackers.filter { it.id in present }.map { SourceOption(it.id, it.displayName) } +
                    ro.ak.vodunify.core.imports.ImportSources.labels.filterKeys { it in present }.map { (id, label) -> SourceOption(id, label) },
                counts = LibraryLogic.countsBySource(input.entries, input.filter.tab),
                items = items,
                total = filtered.size,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private data class Input(
        val entries: List<ro.ak.vodunify.core.reconcile.LibraryEntry>,
        val filter: LibraryFilter,
        val connected: Boolean,
        val settings: CatalogSettings,
    )

    fun selectTab(tab: LibraryTab) {
        filter.value = filter.value.copy(tab = tab)
    }

    fun selectSource(source: String?) {
        filter.value = filter.value.copy(source = source)
    }

    val currentFilter: StateFlow<LibraryFilter> = filter.asStateFlow()

    fun syncNow() {
        viewModelScope.launch { tracking.sync() }
    }

    init {
        viewModelScope.launch { tracking.syncIfStale() }
    }

    private val artCache = ConcurrentHashMap<String, CatalogItem>()
    private val artGate = Semaphore(6)

    private suspend fun art(kind: TitleKind, tmdbId: Long?, settings: CatalogSettings): CatalogItem? {
        if (tmdbId == null || !catalog.isConfigured) return null
        val key = "${kind.path}:$tmdbId:${settings.apiLanguage}"
        artCache[key]?.let { return it }
        return artGate.withPermit {
            runCatching { catalog.basic(kind, tmdbId, settings) }.getOrNull()?.also { artCache[key] = it }
        }
    }

    private companion object {
        /** Afișăm primele 300; restul se văd filtrând. */
        const val MAX_ITEMS = 300
    }
}
