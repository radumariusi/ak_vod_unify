package ro.ak.vodunify.ui.library

import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformLatest
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
import ro.ak.vodunify.core.reconcile.LibraryEntry
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tracking.SeriesSummary
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
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
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
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

    private val snapshotsToSave = MutableSharedFlow<LibraryUiState>(replay = 1)
    private val settingsAndRefresh = combine(prefs.settings, flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30 * 60_000L)
        }
    }) { settings, _ -> settings }

    private val fresh = combine(
        tracking.reconciled,
        combine(filter, UiPrefs.libraryLayout, tracking.hiddenTitles) { f, l, h -> Triple(f, l, h) },
        if (ExternalTrackers.ENABLED) tracking.anyConnected else flowOf(true),
        settingsAndRefresh,
    ) { view, flh, connected, settings -> Input(view.library, view.series, flh.first, connected, settings, flh.second, flh.third) }
        .transformLatest { raw ->
            val initial = show(raw)
            emit(initial)
            if (!catalog.isConfigured) return@transformLatest
            val shown = (initial.items + initial.rows.flatMap { it.items }).mapNotNull { item ->
                item.tmdbId?.let { ArtKey(item.kind, it, raw.settings.apiLanguage) }
            }
            val series = raw.series.mapNotNull { (key, entry) ->
                entry.tmdbId?.takeUnless { HiddenTitles.isHidden(raw.hidden, key, entry.lastAt) }
                    ?.let { ArtKey(TitleKind.TV, it, raw.settings.apiLanguage) }
            }
            (shown + series).distinct().filterNot { artCache[it]?.isFresh() == true }.chunked(24).forEach { batch ->
                val changed = coroutineScope { batch.map { key -> async { refreshArt(key, raw.settings) } }.awaitAll() }
                if (changed.any { it }) emit(show(raw))
            }
        }
        .distinctUntilChanged()
        .onEach {
            computed = true
            if (it.filter == LibraryFilter()) snapshotsToSave.tryEmit(it)
        }
        .flowOn(Dispatchers.Default)

    private fun show(raw: Input): LibraryUiState {
        val totals = raw.series.mapNotNull { (key, series) ->
            art(TitleKind.TV, series.tmdbId, raw.settings)?.episodeCount?.let { key to it }
        }.toMap()
        val input = raw.copy(
            entries = LibraryLogic.mergeSeries(raw.entries, raw.series, totals)
                .filterNot { HiddenTitles.isHidden(raw.hidden, it.titleKey, it.lastActivityAt) },
        )
        fun toItems(list: List<LibraryEntry>, tab: LibraryTab): List<LibraryItem> = list.map { e ->
            val kind = if (e.ref.type == "MOVIE") TitleKind.MOVIE else TitleKind.TV
            val art = art(kind, e.ref.tmdbId, input.settings)
            val series = input.series[e.titleKey]?.takeIf { kind == TitleKind.TV && e.resume == null }
            val total = totals[e.titleKey]
            val stage = LibraryLogic.posterStage(e)
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
        return LibraryUiState(
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

    /** Pe loc ultimul ecran salvat (din memorie sau de pe disc), apoi cel calculat acum. */
    val state: StateFlow<LibraryUiState> = merge(
        flow { snapshots.load()?.let { if (!computed) emit(it) } },
        fresh,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), snapshots.memory ?: LibraryUiState())

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private data class Input(
        val entries: List<LibraryEntry>,
        val series: Map<String, SeriesSummary>,
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
        viewModelScope.launch { snapshotsToSave.debounce(400).collect(snapshots::save) }
    }

    private data class ArtKey(val kind: TitleKind, val id: Long, val language: String)

    private data class CachedArt(val item: CatalogItem, val loadedAt: Long) {
        fun isFresh() = System.currentTimeMillis() - loadedAt < TmdbClient.DEFAULT_TTL
    }

    private fun art(kind: TitleKind, tmdbId: Long?, settings: CatalogSettings): CatalogItem? =
        tmdbId?.let { artCache[ArtKey(kind, it, settings.apiLanguage)]?.item }

    private suspend fun refreshArt(key: ArtKey, settings: CatalogSettings): Boolean = artGate.withPermit {
        val previous = artCache[key]
        if (previous?.isFresh() == true) return@withPermit false
        try {
            val item = catalog.basic(key.kind, key.id, settings)
            artCache.put(key, CachedArt(item, System.currentTimeMillis()))
            item != previous?.item
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        /** Pe tot procesul: o redeschidere a Listei mele nu mai cere din nou posterele. */
        val artCache = LruCache<ArtKey, CachedArt>(2_000)
        val artGate = Semaphore(6)

        const val LOCAL_LABEL = "AK Scrobbler"

        /** Afișăm primele 300; restul se văd filtrând. */
        const val MAX_ITEMS = 300

        /** Câte titluri arată un rând (cele mai recente). */
        const val ROW_ITEMS = 60
    }
}
