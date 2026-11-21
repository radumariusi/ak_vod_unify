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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.Episode
import ro.ak.vodunify.core.catalog.mediaKey
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.tracking.TitleTrackState
import ro.ak.vodunify.core.tracking.TrackingRepository
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
    private val tracking: TrackingRepository,
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
                flowOf<LoadState<Season>?>(null)
            } else {
                flow<LoadState<Season>?> {
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

    // ---------- De văzut / Văzut (local; plus trackerii externi când sunt activi) ----------

    /** True când „De văzut” / „Văzut” se pot marca: mereu cu baza locală, altfel doar cu un tracker conectat. */
    val trackerConnected: StateFlow<Boolean> =
        (if (ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) tracking.anyConnected else kotlinx.coroutines.flow.flowOf(true))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), !ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED)

    val trackState: StateFlow<TitleTrackState?> = tracking.titleState(mediaKey(kind, id))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _showProgress = MutableStateFlow<ShowProgress?>(null)
    val showProgress: StateFlow<ShowProgress?> = _showProgress.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var progressLoadedFor: String? = null

    private fun ref(details: TitleDetails) = MediaRef(
        type = if (kind == TitleKind.MOVIE) MediaType.MOVIE else MediaType.SHOW,
        ids = ExternalIds(tmdb = details.item.id, imdb = details.imdbId),
        title = details.item.title,
        year = details.item.year,
    )

    /** Încarcă episoadele văzute (o singură dată pe titlu, sau forțat după o modificare). */
    fun loadShowProgress(details: TitleDetails, force: Boolean = false) {
        if (kind != TitleKind.TV) return
        if (!force && progressLoadedFor == details.item.mediaKey) return
        progressLoadedFor = details.item.mediaKey
        viewModelScope.launch {
            if (!tracking.hasConnectedTracker()) return@launch
            runCatching { tracking.showProgress(ref(details)) }
                .onSuccess { _showProgress.value = it }
                .onFailure { _message.value = "Nu am putut citi progresul: ${it.message}" }
        }
    }

    fun toggleWatchlist(details: TitleDetails) {
        val inList = trackState.value?.inWatchlist == true
        act(if (inList) "Scos din „De văzut”" else "Adăugat în „De văzut”") {
            tracking.setInWatchlist(ref(details), !inList)
        }
    }

    fun toggleWatched(details: TitleDetails) {
        val watched = trackState.value?.watched == true
        act(if (watched) "Marcat ca nevăzut" else "Marcat ca văzut") {
            tracking.setWatched(ref(details), !watched)
        }
    }

    fun toggleEpisode(details: TitleDetails, episode: Episode) {
        val number = EpisodeNumber(episode.season, episode.number)
        val current = _showProgress.value
        val watched = current?.watched?.contains(number) == true
        val show = ref(details)
        val media = show.copy(type = MediaType.EPISODE, season = episode.season, episode = episode.number, episodeTitle = episode.name)
        act(if (watched) "S${episode.season}E${episode.number} marcat ca nevăzut" else "S${episode.season}E${episode.number} marcat ca văzut") {
            tracking.setWatched(media, !watched)
            // Actualizare imediată în UI, apoi starea reală din sursă.
            current?.let { p ->
                _showProgress.value = p.copy(watched = if (watched) p.watched - number else p.watched + number)
            }
            tracking.refreshUpNext(show)
            _showProgress.value = runCatching { tracking.showProgress(show) }.getOrNull() ?: _showProgress.value
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun act(success: String, block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            _message.value = try {
                block()
                success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }
}
