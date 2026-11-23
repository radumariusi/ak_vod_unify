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
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver
import ro.ak.vodunify.core.playback.WatchOptions
import ro.ak.vodunify.core.playback.WatchOptionsBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import javax.inject.Inject
import ro.ak.vodunify.core.calendar.android.CalendarRepository

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DetailsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val tracking: TrackingRepository,
    prefs: CatalogPreferences,
    playbackPrefs: PlaybackPreferences,
    private val resolver: TargetResolver,
    private val calendar: CalendarRepository,
    private val seriesMarker: ro.ak.vodunify.core.tracking.SeriesMarker,
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

    /** Aplicațiile instalate (citite la deschiderea fișei și la revenirea în aplicație). */
    private val installed = MutableStateFlow<Set<TargetApp>?>(null)

    fun refreshInstalled() {
        viewModelScope.launch {
            installed.value = withContext(Dispatchers.IO) { runCatching { resolver.installedApps().keys }.getOrDefault(emptySet()) }
        }
    }

    /** Unde vezi titlul: serviciile tale (în biblioteca fiecăruia), altfel unde se găsește + playerele. */
    val watchOptions: StateFlow<WatchOptions?> = combine(state, playbackPrefs.settings, installed) { st, p, apps ->
        val d = (st as? LoadState.Data)?.value ?: return@combine null
        if (apps == null) return@combine null
        WatchOptionsBuilder.build(d.availability, p, apps)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
                    val season = loadCatching { catalog.season(id, n, s) }
                    emit(season)
                    // Apoi notele IMDb ale episoadelor (o cerere OMDb pe sezon), dacă avem cheia.
                    val data = (season as? LoadState.Data)?.value ?: return@flow
                    val imdbId = (state.value as? LoadState.Data)?.value?.imdbId ?: return@flow
                    val ratings = runCatching { calendar.episodeImdbRatings(imdbId, n) }.getOrDefault(emptyMap())
                    if (ratings.isNotEmpty()) {
                        emit(LoadState.Data(data.copy(episodes = data.episodes.map { it.copy(imdbRating = ratings[it.number]) })))
                    }
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

    init {
        refreshInstalled()
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
            runCatching { progressFor(details) }
                .onSuccess { _showProgress.value = it }
                .onFailure { _message.value = "Nu am putut citi progresul: ${it.message}" }
        }
    }

    /** Progresul pe serial: din istoricul local (implicit) sau din trackerii externi, când sunt activi. */
    private suspend fun progressFor(details: TitleDetails): ShowProgress? {
        val show = ref(details)
        if (!ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
            val counts = details.seasons.filter { it.number > 0 }.associate { it.number to it.episodeCount }
            return tracking.localShowProgress(show, counts)
        }
        if (!tracking.hasConnectedTracker()) return null
        return tracking.showProgress(show)
    }

    fun toggleWatchlist(details: TitleDetails) {
        val inList = trackState.value?.inMyList == true
        act(if (inList) "Scos din Lista mea" else "Adăugat în Lista mea (De văzut)") {
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
            _showProgress.value = runCatching { progressFor(details) }.getOrNull() ?: _showProgress.value
        }
    }

    /** Tot sezonul (episoadele apărute) văzut / nevăzut. */
    fun toggleSeason(details: TitleDetails, season: Int, watched: Boolean) {
        act(if (watched) "Sezonul $season marcat ca nevăzut" else "Sezonul $season marcat ca văzut") {
            seriesMarker.mark(details.item.id, details.item.title, details.item.year, details.imdbId, !watched, listOf(season))
            _showProgress.value = runCatching { progressFor(details) }.getOrNull() ?: _showProgress.value
        }
    }

    /** Tot serialul (episoadele apărute) văzut / nevăzut. */
    fun toggleSeries(details: TitleDetails, watched: Boolean) {
        act(if (watched) "Serialul marcat ca nevăzut" else "Serialul marcat ca văzut") {
            seriesMarker.mark(details.item.id, details.item.title, details.item.year, details.imdbId, !watched)
            _showProgress.value = runCatching { progressFor(details) }.getOrNull() ?: _showProgress.value
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
