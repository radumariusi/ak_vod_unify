package ro.ak.vodunify.ui.home

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchlistEntity
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/** Ecranul principal: rânduri personale (Trakt) + rânduri din TMDb pentru piața și limba din Setări. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val tracking: TrackingRepository,
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

    private val configured = tracking.trackers.any { it.isConfigured }

    val personal: StateFlow<PersonalRows> = combine(
        tracking.anyConnected,
        tracking.continueWatching,
        tracking.upNext,
        tracking.watchlist,
        prefs.settings,
    ) { connected, resume, next, list, settings -> Snapshot(connected, resume, next, list, settings) }
        .mapLatest { s ->
            when {
                !configured -> PersonalRows(PersonalState.NOT_CONFIGURED)
                !s.connected -> PersonalRows(PersonalState.DISCONNECTED)
                else -> coroutineScope {
                    suspend fun <E> build(items: List<E>, map: suspend (E) -> PersonalItem) =
                        items.take(ROW_LIMIT).map { async { map(it) } }.awaitAll()
                    val resume = async {
                        build(s.resume) { PersonalMapper.resume(it, art(PersonalMapper.kind(it.ref), it.ref.tmdbId, s.settings)) }
                    }
                    val next = async {
                        build(s.next) { PersonalMapper.upNext(it, art(TitleKind.TV, it.ref.tmdbId, s.settings)) }
                    }
                    val list = async {
                        build(s.list) { PersonalMapper.watchlist(it, art(PersonalMapper.kind(it.ref), it.ref.tmdbId, s.settings)) }
                    }
                    PersonalRows(PersonalState.CONNECTED, resume.await(), next.await(), list.await())
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonalRows(PersonalState.DISCONNECTED))

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private data class Snapshot(
        val connected: Boolean,
        val resume: List<ResumePointEntity>,
        val next: List<UpNextEntity>,
        val list: List<WatchlistEntity>,
        val settings: CatalogSettings,
    )

    // Imagini și titluri localizate din TMDb, ținute în memorie cât trăiește ecranul.
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

    init {
        viewModelScope.launch { tracking.syncIfStale() }
    }

    fun syncNow() {
        viewModelScope.launch { tracking.sync() }
    }

    fun retry() {
        reload.value++
        syncNow()
    }

    private companion object {
        const val ROW_LIMIT = 30
    }
}
