package ro.ak.vodunify.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
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
import ro.ak.vodunify.core.catalog.ServiceShelf
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.MovieReleaseEvent
import ro.ak.vodunify.core.catalog.ReleaseChannel
import ro.ak.vodunify.core.calendar.android.CALENDAR_TO_SERVICE
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.calendar.model.MovieRelease
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.tracking.SeriesSummary
import java.time.LocalDate
import ro.ak.vodunify.core.db.WatchlistEntity
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import android.content.Context
import coil.imageLoader
import coil.request.ImageRequest
import dagger.hilt.android.qualifiers.ApplicationContext

/** Ecranul principal: rânduri personale (baza locală, AK Scrobbler) + rânduri din TMDb pentru piața și limba din Setări. */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val tracking: TrackingRepository,
    private val prefs: CatalogPreferences,
    playbackPrefs: PlaybackPreferences,
    private val resolver: TargetResolver,
    private val calendar: CalendarRepository,
    @ApplicationContext private val context: Context,
    private val snapshots: HomeSnapshotStore,
) : ViewModel() {

    private val reload = MutableStateFlow(0)

    // Acasă apare pe loc cu ultimul ecran știut (din memorie sau de pe disc), apoi se reîmprospătează în fundal.
    // Fără „Se încarcă…” la fiecare revenire pe filă: rândurile rămân pe ecran cât se cer datele noi.
    private val _state = MutableStateFlow<LoadState<HomeCatalog>>(
        snapshots.memory?.catalog?.let { LoadState.Data(it) } ?: LoadState.Loading,
    )
    val state: StateFlow<LoadState<HomeCatalog>> = _state.asStateFlow()

    private val _personal = MutableStateFlow(snapshots.memory?.personal ?: PersonalRows(PersonalState.LOADING))
    val personal: StateFlow<PersonalRows> = _personal.asStateFlow()

    private val configured = tracking.trackers.any { it.isConfigured }

    private suspend fun personalRows(s: Snapshot): PersonalRows {
        // „Continuă vizionarea” vine din AK Scrobbler: se arată și fără Trakt/SIMKL conectat.
        if (ExternalTrackers.ENABLED && s.resume.isEmpty()) {
            if (!configured) return PersonalRows(PersonalState.NOT_CONFIGURED)
            if (!s.connected) return PersonalRows(PersonalState.DISCONNECTED)
        }
        return coroutineScope {
            val followed = async { followed(s) }
            val popular = async {
                if (!catalog.isConfigured) {
                    emptyList()
                } else {
                    snapshots.popular(s.settings.toString()) {
                        runCatching {
                            catalog.upcomingPopular(s.settings, HomeSections.SOON_MOVIE_DAYS, POPULAR_EPISODE_DAYS, POPULAR_RECENT_DAYS)
                        }.getOrDefault(emptyList())
                    }.map { FollowedTitle(it, null) }
                }
            }
            val packages = async { snapshots.packages { servicePackages() } }
            val resume = s.resume.take(ROW_LIMIT * 2).map { e ->
                async { PersonalMapper.resume(e, art(PersonalMapper.kind(e.ref), e.ref.tmdbId, s.settings)) }
            }.awaitAll()
            val today = LocalDate.now()
            val f = followed.await()
            val p = popular.await()
            // Filmele, cu lansările lor (cinema RO / US, streaming, digital) din calendar.
            val movies = withReleases(f.filter { it.releases.item.kind == TitleKind.MOVIE })
            val shows = f.filter { it.releases.item.kind == TitleKind.TV }
            val popularMovies = withReleases(p.filter { it.releases.item.kind == TitleKind.MOVIE })
            val popularShows = p.filter { it.releases.item.kind == TitleKind.TV }
            val apps = packages.await()
            fun List<PersonalItem>.withApps() = map { i ->
                val r = i.release
                val id = r?.serviceId?.takeIf { r.channel == ReleaseChannel.STREAMING }
                if (id != null) i.copy(appPackage = apps[id]) else i
            }
            // Întâi ce e din Lista mea, apoi ce e popular, ca rândurile să nu fie goale. Un titlu trece singur
            // din „În curând” în „Noutăți” la data lansării (aceleași titluri, altă fereastră de timp).
            PersonalRows(
                PersonalState.CONNECTED,
                continueMovies = resume.filter { it.kind == TitleKind.MOVIE }.take(ROW_LIMIT),
                continueShows = resume.filter { it.kind == TitleKind.TV }.take(ROW_LIMIT),
                newMovies = (HomeSections.newMovies(movies, today) + HomeSections.newMovies(popularMovies, today))
                    .distinctBy { it.key }.take(ROW_LIMIT).withApps(),
                newEpisodes = (
                    HomeSections.newEpisodes(shows, today) +
                        HomeSections.newEpisodes(popularShows, today, POPULAR_RECENT_EPISODE_DAYS)
                    ).distinctBy { it.key }.take(ROW_LIMIT),
                soonMovies = (HomeSections.soonMovies(movies, today) + HomeSections.soonMovies(popularMovies, today))
                    .distinctBy { it.key }.take(ROW_LIMIT).withApps(),
                soonEpisodes = (HomeSections.soonEpisodes(shows, today) + HomeSections.soonEpisodes(popularShows, today))
                    .distinctBy { it.key }.take(ROW_LIMIT),
            )
        }
    }

    /**
     * Lista mea pentru „Noutăți” și „În curând”: titlurile din De văzut plus serialele pe care le-ai început
     * (din istoricul local), fiecare cu datele lui de lansare din TMDb.
     */
    private suspend fun followed(s: Snapshot): List<FollowedTitle> {
        if (!catalog.isConfigured) return emptyList()
        val wanted = LinkedHashMap<String, Triple<TitleKind, Long, Pair<Int, Int>?>>()
        s.series.values.sortedByDescending { it.lastAt }.forEach { sum ->
            val id = sum.tmdbId ?: return@forEach
            wanted.getOrPut("tv:$id") { Triple(TitleKind.TV, id, sum.lastSeason to sum.lastEpisode) }
        }
        s.list.forEach { w ->
            val id = w.ref.tmdbId ?: return@forEach
            val kind = PersonalMapper.kind(w.ref)
            wanted.getOrPut("${kind.path}:$id") { Triple(kind, id, null) }
        }
        return coroutineScope {
            wanted.values.take(FOLLOW_LIMIT).map { (kind, id, seen) ->
                async {
                    snapshots.releases("${kind.path}:$id:${s.settings.apiLanguage}") {
                        artGate.withPermit { runCatching { catalog.releases(kind, id, s.settings) }.getOrNull() }
                    }?.let { FollowedTitle(it, seen) }
                }
            }.awaitAll().filterNotNull()
        }
    }

    /** Lansările fiecărui film (din calendar, păstrate în cache-ul TMDb); fără ele rămâne doar data. */
    private suspend fun withReleases(movies: List<FollowedTitle>): List<FollowedTitle> = coroutineScope {
        movies.take(RELEASES_LIMIT).map { f ->
            async {
                val list = snapshots.movieReleases(f.releases.item.id) {
                    artGate.withPermit { runCatching { calendar.movieReleases(f.releases.item.id) }.getOrNull() }?.map(::toEvent)
                }
                if (list.isNullOrEmpty()) f else f.copy(releases = f.releases.copy(movieReleases = list))
            }
        }.awaitAll() + movies.drop(RELEASES_LIMIT)
    }

    private fun toEvent(r: MovieRelease) = MovieReleaseEvent(
        channel = when (r.kind) {
            MovieReleaseKind.CINEMA_RO, MovieReleaseKind.CINEMA_US -> ReleaseChannel.CINEMA
            MovieReleaseKind.STREAMING -> ReleaseChannel.STREAMING
            MovieReleaseKind.DIGITAL -> ReleaseChannel.DIGITAL
        },
        region = r.region.code,
        date = r.date.toString(),
        serviceId = r.service?.id,
        serviceName = r.service?.name,
        mine = r.onMyApp,
    )

    /** Pachetul aplicației instalate pentru fiecare serviciu din calendar (ex. „netflix”), pentru siglă. */
    private suspend fun servicePackages(): Map<String, String> = withContext(Dispatchers.IO) {
        val installed = runCatching { resolver.installedApps() }.getOrDefault(emptyMap())
        CALENDAR_TO_SERVICE.mapNotNull { (calendarId, serviceId) ->
            val app = StreamingService.byId(serviceId)?.let { TargetApp.byId(it.appId) } ?: return@mapNotNull null
            installed[app]?.let { calendarId to it.packageName }
        }.toMap()
    }

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private data class Snapshot(
        val connected: Boolean,
        val resume: List<ResumePointEntity>,
        val list: List<WatchlistEntity>,
        val series: Map<String, SeriesSummary>,
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
        // Calendarul de azi se pregătește în fundal, ca fila Calendar să apară pe loc.
        viewModelScope.launch {
            delay(CALENDAR_PREWARM_DELAY_MS)
            runCatching { calendar.prewarmToday() }
        }
    }

    private val logoCache = ConcurrentHashMap<String, String>()

    @Volatile private var logoLanguage: String = "ro"

    /** Sigla titlului cu focus (hero); "" în cache = titlul nu are siglă. */
    suspend fun logo(kind: TitleKind, id: Long): String? {
        val settings = prefs.settings.first()
        logoLanguage = settings.logoLanguage
        val key = "${kind.path}:$id:${settings.logoLanguage}"
        logoCache[key]?.let { return it.ifEmpty { null } }
        val url = runCatching { catalog.logoUrl(kind, id, settings) }.getOrNull()
        logoCache[key] = url.orEmpty()
        return url
    }

    /** Sigla deja știută (fără așteptare): null = încă necerută, "" = titlul nu are siglă. */
    fun cachedLogo(kind: TitleKind, id: Long): String? = logoCache["${kind.path}:$id:$logoLanguage"]

    private val prefetched = ConcurrentHashMap.newKeySet<String>()

    /**
     * Sigle pregătite din timp pentru titlurile din rânduri: adresa (păstrată 7 zile în baza locală) și imaginea
     * (în cache-ul de imagini pe disc), ca hero-ul să le arate pe loc, nu să le încarce de fiecare dată.
     */
    fun prefetchLogos(titles: List<Pair<TitleKind, Long>>) {
        val todo = titles.filter { prefetched.add("${it.first.path}:${it.second}") }
        if (todo.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val loader = context.imageLoader
            todo.forEach { (kind, id) ->
                val url = logo(kind, id) ?: return@forEach
                loader.enqueue(ImageRequest.Builder(context).data(url).build())
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch { tracking.sync() }
    }

    fun retry() {
        snapshots.clearMemo()
        reload.value++
        syncNow()
    }

    init {
        viewModelScope.launch {
            snapshots.load()?.let { snap ->
                if (_state.value is LoadState.Loading) snap.catalog?.let { _state.value = LoadState.Data(it) }
                if (_personal.value.state == PersonalState.LOADING) snap.personal?.let { _personal.value = it }
            }
        }
        viewModelScope.launch {
            combine(prefs.settings, playbackPrefs.settings, reload) { s, p, _ -> s to p }.collectLatest { (settings, playback) ->
                val result = loadCatching {
                    val installed = withContext(Dispatchers.IO) { runCatching { resolver.installedApps().keys }.getOrDefault(emptySet()) }
                    val shelves = StreamingService.owned(installed).map {
                        ServiceShelf(it.displayName, it.tmdbProviderIds, playback.marketOf(it))
                    }
                    catalog.home(settings, shelves)
                }
                if (result is LoadState.Data) {
                    _state.value = result
                    snapshots.saveCatalog(result.value)
                } else if (_state.value !is LoadState.Data) {
                    // O eroare nu șterge rândurile deja afișate.
                    _state.value = result
                }
            }
        }
        viewModelScope.launch {
            combine(
                tracking.anyConnected,
                tracking.continueWatching,
                tracking.watchlist,
                tracking.seriesSummaries,
                prefs.settings,
            ) { connected, resume, list, series, settings -> Snapshot(connected, resume, list, series, settings) }
                // AK Scrobbler scrie des (fiecare minut văzut): adunăm schimbările apropiate într-una singură.
                .debounce(400)
                .distinctUntilChanged()
                .collectLatest { s ->
                    val rows = withContext(Dispatchers.Default) { personalRows(s) }
                    _personal.value = rows
                    snapshots.savePersonal(rows)
                }
        }
    }

    private companion object {
        const val ROW_LIMIT = 30

        /** Câte titluri din Lista mea verificăm pentru noutăți (cele mai recente întâi). */
        const val FOLLOW_LIMIT = 120
        const val POPULAR_EPISODE_DAYS = 14L

        /** Titlurile populare apărute de curând („Noutăți”): filme din ultima lună, episoade din ultima săptămână. */
        const val POPULAR_RECENT_DAYS = 30L
        const val POPULAR_RECENT_EPISODE_DAYS = 7L

        /** Câte filme primesc lansările detaliate (cinema / streaming / digital). */
        const val RELEASES_LIMIT = 80

        const val CALENDAR_PREWARM_DELAY_MS = 6_000L
    }
}
