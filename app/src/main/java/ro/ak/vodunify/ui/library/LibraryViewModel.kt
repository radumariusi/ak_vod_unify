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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import ro.ak.vodunify.core.tracking.HiddenTitles
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.prefs.LibraryLayout
import ro.ak.vodunify.core.prefs.UiPrefs
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@kotlinx.serialization.Serializable
data class LibraryUiState(
    val loading: Boolean = true,
    val connected: Boolean = false,
    val filter: LibraryFilter = LibraryFilter(),
    val sources: List<SourceOption> = emptyList(),
    @kotlinx.serialization.Transient val counts: Map<String?, Int> = emptyMap(),
    val items: List<LibraryItem> = emptyList(),
    val total: Int = 0,
    /** Aspectul ales în Setări → Interfață. */
    val layout: LibraryLayout = LibraryLayout.ROWS,
    /** La aspectul pe rânduri: câte un rând pe categorie și tip. */
    val rows: List<LibraryRow> = emptyList(),
)

/** Un rând din Lista mea (aspectul pe rânduri). */
@kotlinx.serialization.Serializable
data class LibraryRow(val title: String, val items: List<LibraryItem>) {
    val key: String get() = title.substringBefore(" (")
}

/** Lista mea: tot ce știe baza locală (AK Scrobbler, importuri; plus trackerii externi când sunt activi), reconciliat. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val tracking: TrackingRepository,
    private val catalog: CatalogRepository,
    prefs: CatalogPreferences,
    private val snapshots: LibrarySnapshotStore,
) : ViewModel() {

    private val filter = MutableStateFlow(LibraryFilter())
    private val names = tracking.trackers.associate { it.id to it.displayName } +
        ro.ak.vodunify.core.imports.ImportSources.labels +
        (ro.ak.vodunify.core.publish.LOCAL_SOURCE to LOCAL_LABEL)

    /** S-a calculat deja starea reală (atunci instantaneul de pe disc nu mai contează). */
    @Volatile private var computed = false

    private val fresh = combine(
        tracking.library,
        tracking.seriesSummaries,
        combine(filter, UiPrefs.libraryLayout, tracking.hiddenTitles) { f, l, h -> Triple(f, l, h) },
        if (ExternalTrackers.ENABLED) tracking.anyConnected else flowOf(true),
        prefs.settings,
    ) { entries, series, flh, connected, settings -> Input(entries, series, flh.first, connected, settings, flh.second, flh.third) }
        .mapLatest { raw ->
            // Serialele din istoricul local: numărul de episoade din TMDb decide „În curs” / „Văzute”.
            val totals = coroutineScope {
                raw.series.mapNotNull { (key, s) -> s.tmdbId?.let { id -> key to id } }
                    .map { (key, id) -> async { key to art(TitleKind.TV, id, raw.settings)?.episodeCount } }
                    .awaitAll()
                    .mapNotNull { (key, total) -> total?.let { key to it } }
                    .toMap()
            }
            // Titlurile scoase de mână din Lista mea nu se arată (până le vezi din nou).
            val input = raw.copy(
                entries = LibraryLogic.mergeSeries(raw.entries, raw.series, totals)
                    .filterNot { HiddenTitles.isHidden(raw.hidden, it.titleKey, it.lastActivityAt) },
            )
            suspend fun toItems(list: List<ro.ak.vodunify.core.reconcile.LibraryEntry>, tab: LibraryTab): List<LibraryItem> = coroutineScope {
                list.map { e ->
                    async {
                        val kind = if (e.ref.type == "MOVIE") TitleKind.MOVIE else TitleKind.TV
                        val art = art(kind, e.ref.tmdbId, input.settings)
                        val series = input.series[e.titleKey]?.takeIf { kind == TitleKind.TV }
                        val total = totals[e.titleKey]
                        val stage = LibraryLogic.posterStage(e, tab)
                        val seriesProgress = if (series != null && total != null && total > 0) minOf(1f, series.watched.toFloat() / total) else null
                        LibraryItem(
                            key = e.titleKey,
                            kind = kind,
                            tmdbId = e.ref.tmdbId,
                            title = art?.title ?: e.ref.title.ifBlank { "Titlu necunoscut" },
                            subtitle = series?.let { LibraryLogic.seriesLabel(it, total, stage) } ?: LibraryLogic.subtitle(e, tab),
                            posterUrl = art?.posterUrl,
                            progress = seriesProgress ?: LibraryLogic.progress(e, tab),
                            showsProgress = seriesProgress != null || LibraryLogic.showsProgress(e, tab),
                            sources = e.sources.sorted().map { names[it] ?: it },
                            posterStage = stage,
                            rating = art?.rating,
                        )
                    }
                }.awaitAll()
            }
            val filtered = LibraryLogic.filter(input.entries, input.filter)
            val rows = if (input.layout == LibraryLayout.ROWS) {
                // Câte un rând pe categorie și tip, ca în Acasă.
                LibraryTab.visible.filter { it != LibraryTab.ALL }.flatMap { tab ->
                    val inTab = LibraryLogic.filter(input.entries, LibraryFilter(tab, null))
                    listOf(TitleKind.MOVIE to "Filme", TitleKind.TV to "Seriale").mapNotNull { (kind, label) ->
                        val part = inTab.filter { (it.ref.type == "MOVIE") == (kind == TitleKind.MOVIE) }
                        if (part.isEmpty() && tab != LibraryTab.WATCHLIST) null else {
                            val title = if (tab == LibraryTab.WATCHLIST) "${tab.label} $label" else "${tab.label} · $label"
                            LibraryRow("$title (${part.size})", toItems(part.take(ROW_ITEMS), tab))
                        }
                    }
                }
            } else {
                emptyList()
            }
            val items = if (input.layout == LibraryLayout.GRID) toItems(filtered.take(MAX_ITEMS), input.filter.tab) else emptyList()
            val present = input.entries.flatMap { it.sources }.toSet()
            LibraryUiState(
                loading = false,
                connected = input.connected,
                filter = input.filter,
                sources = listOfNotNull(
                    SourceOption(ro.ak.vodunify.core.publish.LOCAL_SOURCE, LOCAL_LABEL)
                        .takeIf { it.id in present },
                ) +
                    tracking.trackers.filter { it.id in present }.map { SourceOption(it.id, it.displayName) } +
                    ro.ak.vodunify.core.imports.ImportSources.labels.filterKeys { it in present }.map { (id, label) -> SourceOption(id, label) },
                counts = LibraryLogic.countsBySource(input.entries, input.filter.tab),
                items = items,
                total = if (input.layout == LibraryLayout.ROWS) input.entries.size else filtered.size,
                layout = input.layout,
                rows = rows,
            )
        }
        .onEach {
            computed = true
            if (it.filter == LibraryFilter()) snapshots.save(it)
        }

    /** Pe loc ultimul ecran salvat (din memorie sau de pe disc), apoi cel calculat acum. */
    val state: StateFlow<LibraryUiState> = merge(
        flow { snapshots.load()?.let { if (!computed) emit(it) } },
        fresh,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshots.memory ?: LibraryUiState())

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private data class Input(
        val entries: List<ro.ak.vodunify.core.reconcile.LibraryEntry>,
        val series: Map<String, ro.ak.vodunify.core.tracking.SeriesSummary>,
        val filter: LibraryFilter,
        val connected: Boolean,
        val settings: CatalogSettings,
        val layout: LibraryLayout,
        val hidden: Map<String, Long>,
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


    private suspend fun art(kind: TitleKind, tmdbId: Long?, settings: CatalogSettings): CatalogItem? {
        if (tmdbId == null || !catalog.isConfigured) return null
        val key = "${kind.path}:$tmdbId:${settings.apiLanguage}"
        artCache[key]?.let { return it }
        return artGate.withPermit {
            runCatching { catalog.basic(kind, tmdbId, settings) }.getOrNull()?.also { artCache[key] = it }
        }
    }

    private companion object {
        /** Pe tot procesul: o redeschidere a Listei mele nu mai cere din nou posterele. */
        val artCache = ConcurrentHashMap<String, CatalogItem>()
        val artGate = Semaphore(6)

        const val LOCAL_LABEL = "AK Scrobbler"

        /** Afișăm primele 300; restul se văd filtrând. */
        const val MAX_ITEMS = 300

        /** Câte titluri arată un rând (cele mai recente). */
        const val ROW_ITEMS = 60
    }
}
