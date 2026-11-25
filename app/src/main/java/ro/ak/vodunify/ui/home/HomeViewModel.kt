package ro.ak.vodunify.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.ServiceShelf
import ro.ak.vodunify.core.catalog.ServiceCatalogStatus
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.playback.InstalledApp
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.MovieReleaseEvent
import ro.ak.vodunify.core.catalog.ReleaseChannel
import ro.ak.vodunify.core.calendar.android.CALENDAR_TO_SERVICE
import ro.ak.vodunify.core.calendar.android.CalendarPreferences
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.calendar.model.MovieRelease
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.reconcile.ReconciledView
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.tracking.SeriesSummary
import java.time.LocalDate
import ro.ak.vodunify.core.db.WatchlistEntity
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import ro.ak.vodunify.core.scrobble.PlaybackSourceRecovery
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.loadCatching
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.serialization.json.Json
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

/** Ecranul principal: rânduri personale (baza locală, AK Scrobbler) + rânduri din TMDb pentru piața și limba din Setări. */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val catalog: CatalogRepository,
    private val tracking: TrackingRepository,
    private val playbackSources: PlaybackSourceRecovery,
    private val prefs: CatalogPreferences,
    playbackPrefs: PlaybackPreferences,
    private val resolver: TargetResolver,
    private val calendar: CalendarRepository,
    @ApplicationContext private val context: Context,
    private val snapshots: HomeSnapshotStore,
    private val json: Json,
    calendarPreferences: CalendarPreferences,
) : ViewModel() {

    private val reload = MutableStateFlow(0)
    private val installed = MutableStateFlow<Map<TargetApp, InstalledApp>?>(null)
    private var refreshInstalled: Job? = null

    fun refreshApps() {
        refreshInstalled?.cancel()
        refreshInstalled = viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                runCatching { resolver.installedApps(includeServicesWithoutLauncher = true) }.getOrNull()
            }
            installed.value = apps ?: installed.value.orEmpty()
        }
    }

    fun openService(packageName: String): Boolean = resolver.launchApp(packageName)

    // Acasă apare pe loc cu ultimul ecran știut (din memorie sau de pe disc), apoi se reîmprospătează în fundal.
    // Fără „Se încarcă…” la fiecare revenire pe filă: rândurile rămân pe ecran cât se cer datele noi.
    private val _state = MutableStateFlow<LoadState<HomeCatalog>>(
        snapshots.memory?.catalog?.let { LoadState.Data(it.withoutServiceRows()) } ?: LoadState.Loading,
    )
    private val _personal = MutableStateFlow(snapshots.memory?.personal ?: PersonalRows(PersonalState.LOADING))
    private val chart = MutableStateFlow(HomePopularityChart())
    private val contentFilter = combine(
        calendarPreferences.settings.map { it.hiddenCategories }.distinctUntilChanged(), chart, ::HomeContentFilter,
    ).stateIn(viewModelScope, SharingStarted.Eagerly, HomeContentFilter())

    private fun filtered(state: LoadState<HomeCatalog>, filter: HomeContentFilter): LoadState<HomeCatalog> =
        if (state is LoadState.Data) LoadState.Data(filter.catalog(state.value)) else state

    val state: StateFlow<LoadState<HomeCatalog>> = combine(_state, contentFilter, ::filtered)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, filtered(_state.value, contentFilter.value))

    private val releaseViewing = tracking.reconciled.map(::newReleaseViewing)
        .distinctUntilChanged().flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val personal: StateFlow<PersonalRows> = combine(_personal, contentFilter, releaseViewing) { rows, filter, viewing ->
        filter.personal(viewing?.hideWatched(rows) ?: rows.copy(newMovies = emptyList(), newEpisodes = emptyList()))
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope, SharingStarted.Eagerly,
        contentFilter.value.personal(_personal.value.copy(newMovies = emptyList(), newEpisodes = emptyList())),
    )

    private fun newReleaseViewing(view: ReconciledView): NewReleaseViewing {
        val movies = view.library.mapNotNull { entry ->
            entry.ref.tmdbId.takeIf { entry.ref.type == "MOVIE" && entry.currentStatus == LibraryStatus.WATCHED }
        }.toSet()
        val started = view.library.mapNotNull { it.resume?.winner?.ref }.mapNotNull { ref ->
            val id = ref.tmdbId ?: return@mapNotNull null
            val season = ref.season ?: return@mapNotNull null
            val episode = ref.episode ?: return@mapNotNull null
            id to EpisodeNumber(season, episode)
        }.toSet()
        val episodes = mutableMapOf<Long, MutableSet<EpisodeNumber>>()
        view.latestWatches.forEach { entry ->
            val id = entry.ref.tmdbId ?: return@forEach
            val season = entry.ref.season ?: return@forEach
            val episode = entry.ref.episode ?: return@forEach
            val number = EpisodeNumber(season, episode)
            if ((id to number) !in started) episodes.getOrPut(id) { mutableSetOf() }.add(number)
        }
        return NewReleaseViewing(movies, episodes)
    }

    private val configured = tracking.trackers.any { it.isConfigured }

    private suspend fun releaseRows(s: ReleaseInput, publish: suspend (PersonalRows) -> Unit): Boolean = coroutineScope {
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
        val today = LocalDate.now()
        val f = followed.await()
        val p = popular.await()
        // Filmele, cu lansările lor (cinema RO / US, streaming, digital) din calendar.
        val movies = withReleases(f.filter { it.releases.item.kind == TitleKind.MOVIE })
        val shows = f.filter { it.releases.item.kind == TitleKind.TV }
        val popularMovies = withReleases(p.filter { it.releases.item.kind == TitleKind.MOVIE })
        val popularShows = p.filter { it.releases.item.kind == TitleKind.TV }
        val followedIds = shows.mapTo(HashSet()) { it.releases.item.id }
        val apps = packages.await()
        fun List<PersonalItem>.withApps() = map { i ->
            val r = i.release
            val id = r?.serviceId?.takeIf { r.channel == ReleaseChannel.STREAMING }
            if (id != null) i.copy(appPackage = apps[id]) else i
        }
        // Întâi ce e din Lista mea, apoi ce e popular, ca rândurile să nu fie goale. Un titlu trece singur
        // din „În curând” în „Noutăți” la data lansării (aceleași titluri, altă fereastră de timp).
        val rows = PersonalRows(
            PersonalState.CONNECTED,
            newMovies = (HomeSections.newMovies(movies, today) + HomeSections.newMovies(popularMovies, today))
                .distinctBy { it.key }.take(ROW_LIMIT).withApps(),
            newEpisodes = (
                HomeSections.newEpisodes(shows, today) +
                    HomeSections.newEpisodes(popularShows.filterNot { it.releases.item.id in followedIds }, today, POPULAR_RECENT_EPISODE_DAYS)
                ).distinctBy { it.key }.take(ROW_LIMIT),
            soonMovies = (HomeSections.soonMovies(movies, today) + HomeSections.soonMovies(popularMovies, today))
                .distinctBy { it.key }.take(ROW_LIMIT).withApps(),
            soonEpisodes = (HomeSections.soonEpisodes(shows, today) + HomeSections.soonEpisodes(popularShows, today))
                .distinctBy { it.key }.take(ROW_LIMIT),
        )
        publish(rows)
        val showById = (popularShows + shows).associateBy { it.releases.item.id }
        val enriched = rows.newEpisodes.map { card ->
            async {
                val show = showById[card.tmdbId] ?: return@async card
                val last = show.releases.lastEpisode ?: return@async card
                val day = last.airDate ?: return@async card
                val episodes = mutableListOf(last)
                try {
                    artGate.withPermit {
                        var number = last.season
                        while (number >= 1) {
                            val season = catalog.season(show.releases.item.id, number, s.settings)
                            val released = season.episodes.filter { it.airDate == day }
                            episodes += released.map { EpisodeAir(it.season, it.number, it.name, it.airDate) }
                            // O lansare poate cuprinde și finalul sezonului precedent.
                            if (released.none { it.number == 1 }) break
                            number--
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Păstrăm cel puțin episodul confirmat în fișa serialului.
                }
                HomeSections.withEpisodeRange(card, episodes, lastSeen = null)
            }
        }.awaitAll()
        if (enriched != rows.newEpisodes) publish(rows.copy(newEpisodes = enriched))
        listOf(rows.newMovies, rows.newEpisodes, rows.soonMovies, rows.soonEpisodes).any { it.isNotEmpty() }
    }

    /**
     * Lista mea pentru „Noutăți” și „În curând”: titlurile din De văzut plus serialele pe care le-ai început
     * (din istoricul local), fiecare cu datele lui de lansare din TMDb.
     */
    private fun followedTitles(list: List<WatchlistEntity>, series: Map<String, SeriesSummary>): List<Pair<TitleKind, Long>> {
        val wanted = LinkedHashMap<String, Pair<TitleKind, Long>>()
        series.values.sortedByDescending { it.lastAt }.forEach { sum ->
            val id = sum.tmdbId ?: return@forEach
            wanted.getOrPut("tv:$id") { TitleKind.TV to id }
        }
        list.forEach { w ->
            val id = w.ref.tmdbId ?: return@forEach
            val kind = PersonalMapper.kind(w.ref)
            wanted.getOrPut("${kind.path}:$id") { kind to id }
        }
        return wanted.values.take(FOLLOW_LIMIT)
    }

    private suspend fun followed(s: ReleaseInput): List<FollowedTitle> {
        if (!catalog.isConfigured) return emptyList()
        return coroutineScope {
            s.followed.map { (kind, id) ->
                async {
                    snapshots.releases("${kind.path}:$id:${s.settings.apiLanguage}") {
                        artGate.withPermit { runCatching { catalog.releases(kind, id, s.settings) }.getOrNull() }
                    }?.let { FollowedTitle(it) }
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

    private data class ReleaseInput(
        val followed: List<Pair<TitleKind, Long>>,
        val settings: CatalogSettings,
    ) {
        val cacheKey: String get() = "$settings|${LocalDate.now()}|" + followed.joinToString(",") { (kind, id) -> "${kind.path}:$id" }
    }

    // Imagini și titluri localizate din TMDb, ținute în memorie cât trăiește ecranul.
    private val artCache = ConcurrentHashMap<String, CatalogItem>()
    private val artGate = Semaphore(6)

    private fun artKey(kind: TitleKind, tmdbId: Long, settings: CatalogSettings) =
        "${kind.path}:$tmdbId:${settings.apiLanguage}"

    private fun cachedArt(item: ResumePointEntity, settings: CatalogSettings): CatalogItem? =
        item.ref.tmdbId?.let { artCache[artKey(PersonalMapper.kind(item.ref), it, settings)] }

    private suspend fun art(kind: TitleKind, tmdbId: Long?, settings: CatalogSettings): CatalogItem? {
        if (tmdbId == null || !catalog.isConfigured) return null
        val key = artKey(kind, tmdbId, settings)
        artCache[key]?.let { return it }
        return artGate.withPermit {
            artCache[key] ?: runCatching { catalog.basic(kind, tmdbId, settings) }.getOrNull()?.also { artCache[key] = it }
        }
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("home_popularity.json").bufferedReader().use {
                        json.decodeFromString<HomePopularityChart>(it.readText())
                    }
                }.getOrNull()
            }?.let { chart.value = it }
        }
        viewModelScope.launch(Dispatchers.IO) { tracking.syncIfStale() }
    }

    private val logoCache = ConcurrentHashMap<String, String>()

    @Volatile private var logoLanguage: String = "ro"

    /** Sigla titlului cu focus (hero); "" în cache = titlul nu are siglă. */
    suspend fun logo(kind: TitleKind, id: Long): String? {
        val settings = prefs.settings.first()
        logoLanguage = settings.logoLanguage
        val key = "${kind.path}:$id:${settings.logoLanguage}"
        logoCache[key]?.let { return it.ifEmpty { null } }
        val url = when (val result = loadCatching { catalog.logoUrl(kind, id, settings) }) {
            is LoadState.Data -> result.value
            else -> return null
        }
        logoCache[key] = url.orEmpty()
        return url
    }

    /** Sigla deja știută (fără așteptare): null = încă necerută, "" = titlul nu are siglă. */
    fun cachedLogo(kind: TitleKind, id: Long): String? = logoCache["${kind.path}:$id:$logoLanguage"]

    fun syncNow() {
        viewModelScope.launch { tracking.sync() }
    }

    fun retry() {
        snapshots.clearMemo()
        reload.value++
        syncNow()
    }

    init {
        viewModelScope.launch { AppRegistry.version.collectLatest { refreshApps() } }
        val initialSnapshot = viewModelScope.launch {
            snapshots.load()?.let { snap ->
                if (_state.value is LoadState.Loading) snap.catalog?.let { _state.value = LoadState.Data(it.withoutServiceRows()) }
                if (_personal.value.state == PersonalState.LOADING) snap.personal?.let { _personal.value = it }
            }
        }
        viewModelScope.launch {
            initialSnapshot.join()
            combine(prefs.settings, playbackPrefs.settings, installed.filterNotNull(), reload) { s, p, apps, _ ->
                val shelves = StreamingService.owned(apps.keys).mapNotNull { service ->
                    val app = TargetApp.byId(service.appId)?.let(apps::get) ?: return@mapNotNull null
                    ServiceShelf(service, app.packageName, p.marketOf(service))
                }
                s to shelves
            }.collectLatest { (settings, shelves) ->
                val previous = (_state.value as? LoadState.Data)?.value
                val pending = (snapshots.memory?.catalog ?: previous ?: HomeCatalog(emptyList(), emptyList()))
                    .withInstalledServices(shelves, ServiceCatalogStatus.CHECKING)
                if (previous != null || pending.rows.isNotEmpty()) _state.value = LoadState.Data(pending)

                val result = withContext(Dispatchers.Default) { loadCatching { catalog.home(settings) } }
                val general = if (result is LoadState.Data) mergeSavedCatalog(result.value, pending) else pending.withoutServiceRows()
                val serviceRows = pending.rows.filter { it.id.startsWith("service_") }
                if (general.rows.isNotEmpty() || general.hero.isNotEmpty()) {
                    _state.value = LoadState.Data(general.copy(rows = general.rows + serviceRows))
                }
                val services = withContext(Dispatchers.Default) {
                    loadCatching {
                        val catalogs = catalog.streamingCatalogs(shelves.map { it.library }, settings)
                        shelves.map { shelf ->
                            val page = catalogs.getValue(shelf.library)
                            shelf.row(page.items, page.status)
                        }
                    }
                }
                val visible = if (services is LoadState.Data) {
                    mergeSavedCatalog(general.copy(rows = general.rows + services.value), pending)
                } else general.copy(rows = general.rows + serviceRows)
                val complete = visible.withInstalledServices(shelves, ServiceCatalogStatus.UNAVAILABLE)
                if (complete.rows.isNotEmpty() || complete.hero.isNotEmpty()) {
                    _state.value = LoadState.Data(complete)
                    snapshots.saveCatalog(complete)
                } else _state.value = result
            }
        }
        viewModelScope.launch {
            initialSnapshot.join()
            combine(tracking.continueWatching, prefs.settings, tracking.anyConnected, ::Triple)
                .distinctUntilChanged()
                .collectLatest { (resume, settings, connected) ->
                    val status = when {
                        !ExternalTrackers.ENABLED || resume.isNotEmpty() -> PersonalState.CONNECTED
                        !configured -> PersonalState.NOT_CONFIGURED
                        !connected -> PersonalState.DISCONNECTED
                        else -> PersonalState.CONNECTED
                    }
                    val entries = playbackSources.restore(resume.take(ROW_LIMIT * 2))
                    val previous = (_personal.value.continueMovies + _personal.value.continueShows).associateBy { it.key }
                    val ready = entries.map { item ->
                        val art = cachedArt(item, settings) ?: previous["resume:${item.id}"]?.asCatalogItem()
                        PersonalMapper.resume(item, art)
                    }
                    publishResume(status, ready)
                    val enriched = withContext(Dispatchers.Default) {
                        entries.zip(ready).map { (item, fallback) ->
                            async {
                                art(PersonalMapper.kind(item.ref), item.ref.tmdbId, settings)
                                    ?.let { PersonalMapper.resume(item, it) } ?: fallback
                            }
                        }.awaitAll()
                    }
                    publishResume(status, enriched)
                }
        }
        viewModelScope.launch {
            initialSnapshot.join()
            combine(tracking.watchlist, tracking.seriesSummaries, prefs.settings) { list, series, settings ->
                ReleaseInput(followedTitles(list, series), settings)
            }.flowOn(Dispatchers.Default)
                .distinctUntilChanged()
                .combine(reload) { input, generation -> input to generation }
                .collectLatest { (input, _) ->
                    val key = input.cacheKey
                    if (snapshots.releasesAreFresh(key)) return@collectLatest
                    delay(RELEASE_REFRESH_DELAY_MS)
                    val refreshed = withContext(Dispatchers.Default) {
                        releaseRows(input) { rows ->
                            withContext(Dispatchers.Main.immediate) {
                                publishPersonal(_personal.value.copy(
                                    newMovies = rows.newMovies,
                                    newEpisodes = rows.newEpisodes,
                                    soonMovies = rows.soonMovies,
                                    soonEpisodes = rows.soonEpisodes,
                                ))
                            }
                        }
                    }
                    if (refreshed) snapshots.markReleasesRefreshed(key)
                }
        }
    }

    private fun publishResume(status: PersonalState, resume: List<PersonalItem>) {
        publishPersonal(_personal.value.copy(
            state = status,
            continueMovies = resume.filter { it.kind == TitleKind.MOVIE }.take(ROW_LIMIT),
            continueShows = resume.filter { it.kind == TitleKind.TV }.take(ROW_LIMIT),
        ))
    }

    private fun publishPersonal(rows: PersonalRows) {
        if (_personal.value == rows) return
        _personal.value = rows
        snapshots.savePersonal(rows)
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

        const val RELEASE_REFRESH_DELAY_MS = 2_000L
    }
}
