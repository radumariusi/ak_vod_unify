package ro.ak.vodunify.ui.calendar

import ro.ak.vodunify.core.calendar.model.CalendarResult
import ro.ak.vodunify.core.calendar.model.MovieCalendarResult
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.calendar.android.CachedCalendar
import ro.ak.vodunify.core.calendar.android.CalendarWorker
import ro.ak.vodunify.core.calendar.android.WatchedSet
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.SavedShow
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.playback.PlaybackRequest
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.tracking.TrackingRepository
import ro.ak.vodunify.ui.components.toCatalogError
import ro.ak.vodunify.ui.components.CatalogError
import ro.ak.vodunify.ui.watch.playbackRequest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.calendar.android.CALENDAR_TO_SERVICE
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver

/** Pachetele pentru siglele de pe posterele din calendar. */
data class CalendarIcons(val apps: Map<String, String> = emptyMap(), val player: String? = null)

enum class CalendarTab(val label: String, val noun: String) {
    SHOWS("Seriale", "episoade"), MOVIES("Filme", "lansări"), ALL("Filme și seriale", "lansări");

    val includesMovies get() = this != SHOWS
    val includesEpisodes get() = this != MOVIES
}

enum class CalendarMode(val label: String) {
    TODAY("Azi"), DAY("Zi"), WEEK("Săptămână"), MONTH("Lună"), UPCOMING("Următoare");

    val isDay get() = this == DAY || this == TODAY
}

/** Cât de departe se uită „Următoare” (lansările viitoare). */
const val UPCOMING_DAYS = 60L

data class CalendarSummary(
    val count: Int = 0,
    val rejected: Int = 0,
    val hiddenByCategory: Int = 0,
    val hiddenByUser: Int = 0,
    val notOnMyApps: Int = 0,
    val usedImdb: Boolean = false,
)

data class CalendarUiState(
    val tab: CalendarTab = CalendarTab.SHOWS,
    val mode: CalendarMode = CalendarMode.TODAY,
    val from: LocalDate = RomaniaTime.today(),
    val to: LocalDate = RomaniaTime.today(),
    val loading: Boolean = true,
    val passed: List<CalendarItem>? = null,
    val rejected: List<CalendarItem> = emptyList(),
    val summary: CalendarSummary = CalendarSummary(),
    val error: CatalogError? = null,
    val hasMyApps: Boolean = true,
    val onlyMyApps: Boolean = false,
    val movieKinds: Set<MovieReleaseKind> = MovieReleaseKind.entries.toSet(),
    val showRejected: Boolean = false,
    /** Doar titlurile urmărite (din calendar sau din Lista mea). */
    val onlyFollowed: Boolean = false,
) {
    val visible: List<CalendarItem>
        get() {
            val main = passed ?: return emptyList()
            val all = if (showRejected) (main + rejected).sortedBy { it.date } else main
            return if (onlyFollowed) all.filter { it.followed } else all
        }

    val days: List<LocalDate> get() = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()
}

private data class Query(val tab: CalendarTab, val mode: CalendarMode, val anchor: LocalDate, val settings: CalendarSettings, val refresh: Int)

internal data class CalendarPosition(
    val mode: CalendarMode,
    val anchor: LocalDate,
    val month: LocalDate? = null,
    val openedDay: LocalDate? = null,
    val openedWeek: LocalDate? = null,
) {
    fun openDay(day: LocalDate) = CalendarPosition(CalendarMode.DAY, day, anchor, day)

    fun openWeek(monday: LocalDate) = CalendarPosition(CalendarMode.WEEK, monday, month = anchor, openedWeek = monday)

    val monthFocusKey get() = openedDay?.let(::monthDayKey) ?: openedWeek?.let(::monthWeekKey)

    fun back(today: LocalDate) = if (month != null) CalendarPosition(CalendarMode.MONTH, month)
        else CalendarPosition(CalendarMode.TODAY, today)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val calendar: CalendarRepository,
    private val catalog: CatalogRepository,
    private val catalogPrefs: CatalogPreferences,
    private val tracking: TrackingRepository,
    private val resolver: TargetResolver,
    private val playback: PlaybackPreferences,
) : ViewModel() {
    val logoLanguage = catalogPrefs.settings.map { it.logoLanguage }.distinctUntilChanged()

    suspend fun titleLogo(item: CalendarItem): String? = try {
        val kind = if (item.isMovie) TitleKind.MOVIE else TitleKind.TV
        catalog.logoUrl(kind, item.tmdbId, catalogPrefs.settings.first())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Siglele de pe postere: pachetul aplicației instalate pentru fiecare serviciu din calendar (ex. „netflix”)
     * și playerul standard, pentru titlurile care nu sunt pe platformele tale.
     */
    val icons: StateFlow<CalendarIcons> = playback.settings
        .map { p ->
            withContext(Dispatchers.IO) {
                val installed = runCatching { resolver.installedApps() }.getOrDefault(emptyMap())
                val apps = CALENDAR_TO_SERVICE.mapNotNull { (calendarId, serviceId) ->
                    val app = StreamingService.byId(serviceId)?.let { TargetApp.byId(it.appId) } ?: return@mapNotNull null
                    installed[app]?.let { calendarId to it.packageName }
                }.toMap()
                val player = p.defaultPlayerApp?.let { installed[it] }
                    ?: TargetApp.players.firstNotNullOfOrNull { installed[it] }
                CalendarIcons(apps, player?.packageName)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarIcons())

    private val tab = MutableStateFlow(savedState.get<String>("tab")?.let(CalendarTab::valueOf) ?: CalendarTab.SHOWS)
    private val position = MutableStateFlow(CalendarPosition(
        savedState.get<String>("mode")?.let(CalendarMode::valueOf) ?: CalendarMode.TODAY,
        savedDate("anchor") ?: RomaniaTime.today(),
        savedDate("month"),
        savedDate("openedDay"),
        savedDate("openedWeek"),
    ))
    private fun savedDate(key: String) = savedState.get<String>(key)?.let(LocalDate::parse)
    private val refresh = MutableStateFlow(0)
    private val showRejected = MutableStateFlow(false)
    private val onlyFollowed = MutableStateFlow(false)

    /** Ultima apăsare pe „Reîncarcă” deja tratată. */
    @Volatile private var handledRefresh = 0

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Cererea pentru „Alege unde vezi” (apăsare lungă), după ce s-au încărcat detaliile titlului. */
    private val _watch = MutableStateFlow<Pair<PlaybackRequest, List<MarketAvailability>>?>(null)
    val watch: StateFlow<Pair<PlaybackRequest, List<MarketAvailability>>?> = _watch.asStateFlow()

    private val loaded = combine(tab, position, calendar.settings, refresh) { t, p, s, r -> Query(t, p.mode, p.anchor, s, r) }
        .distinctUntilChanged()
        .flatMapLatest { load(it) }

    private val startedTitles = tracking.reconciled.map { view ->
        view.library.mapNotNull { entry ->
            val ref = entry.resume?.winner?.ref ?: return@mapNotNull null
            val id = ref.tmdbId ?: return@mapNotNull null
            if (ref.type == "MOVIE") return@mapNotNull "movie:$id"
            val season = ref.season ?: return@mapNotNull null
            val episode = ref.episode ?: return@mapNotNull null
            "$id-$season-$episode"
        }.toSet()
    }

    val state: StateFlow<CalendarUiState> =
        combine(loaded, calendar.watched, startedTitles, showRejected, onlyFollowed) { s, watched, started, rejected, followed ->
            s.withViewingStatus(watched, started).copy(showRejected = rejected, onlyFollowed = followed)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    val isConfigured: Boolean get() = calendar.isConfigured

    init {
        // La deschiderea Calendarului se reface și canalul „Azi apare”.
        runCatching { CalendarWorker.refreshNow(context) }
        viewModelScope.launch { runCatching { tracking.syncIfStale() } }
    }

    private fun CalendarUiState.withViewingStatus(w: WatchedSet, started: Set<String>): CalendarUiState {
        fun List<CalendarItem>.mark() = map { item ->
            val inProgress = (if (item.isMovie) "movie:${item.tmdbId}" else item.key) in started
            val watched = !inProgress && (if (item.isMovie) item.tmdbId.toInt() in w.movies else item.key in w.episodes)
            if (watched == item.watched && inProgress == item.inProgress) item
            else item.copy(watched = watched, inProgress = inProgress)
        }
        return copy(passed = passed?.mark(), rejected = rejected.mark())
    }

    private fun range(mode: CalendarMode, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (mode) {
        CalendarMode.TODAY, CalendarMode.DAY -> anchor to anchor
        CalendarMode.WEEK -> {
            val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            monday to monday.plusDays(6)
        }
        CalendarMode.MONTH -> anchor.withDayOfMonth(1) to anchor.with(TemporalAdjusters.lastDayOfMonth())
        CalendarMode.UPCOMING -> anchor to anchor.plusDays(UPCOMING_DAYS - 1)
    }

    private fun load(q: Query): Flow<CalendarUiState> {
        val (from, to) = range(q.mode, q.anchor)
        val base = CalendarUiState(
            tab = q.tab,
            mode = q.mode,
            from = from,
            to = to,
            hasMyApps = q.settings.installedApps().isNotEmpty(),
            onlyMyApps = q.settings.onlyInstalledApps,
            movieKinds = q.settings.movieKinds,
        )
        if (!calendar.isConfigured) {
            return kotlinx.coroutines.flow.flowOf(base.copy(loading = false, error = CatalogError.NotConfigured))
        }
        // „Reîncarcă” cere din nou; altfel rezultatul păstrat (de la ultima vizită sau pregătit de Acasă) apare pe loc.
        val force = q.refresh != handledRefresh
        handledRefresh = q.refresh
        fun episodesState(r: CalendarResult) = base.copy(
            loading = false,
            passed = r.entries.map { it.toItem(false) },
            rejected = r.rejected.map { it.toItem(false) },
            summary = CalendarSummary(r.entries.size, r.rejected.size, r.hiddenByCategory, r.hiddenByUser, r.notOnMyApps, r.usedImdb),
        )
        fun moviesState(r: MovieCalendarResult) = base.copy(
            loading = false,
            passed = r.entries.map { it.toItem(false) },
            rejected = r.rejected.map { it.toItem(false) },
            summary = CalendarSummary(r.entries.size, r.rejected.size, r.hiddenByCategory, r.hiddenByUser, r.notOnMyApps, r.usedImdb),
        )
        fun <T> source(
            stored: suspend () -> CachedCalendar<T>,
            map: (T) -> CalendarUiState,
            fetch: suspend (suspend (T) -> Unit) -> T,
        ): Flow<CalendarUiState> = flow {
            val cache = stored()
            var previous = cache.value?.let(map)
            if (cache.fresh && !force && previous != null) {
                emit(previous)
                return@flow
            }
            emit((previous ?: base).copy(loading = true))
            try {
                emit(map(fetch { partial ->
                    val display = map(partial)
                    previous = display
                    if (display.passed.orEmpty().isNotEmpty()) emit(display.copy(loading = true))
                }))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit((previous ?: base).copy(loading = false, error = e.toCatalogError()))
            }
        }

        val episodes = source(
            stored = { calendar.storedEpisodes(from, to, q.settings) },
            map = ::episodesState,
            fetch = { update -> calendar.episodes(from, to, q.settings, force, update) },
        )
        val movies = source(
            stored = { calendar.storedMovies(from, to, q.settings) },
            map = ::moviesState,
            fetch = { update -> calendar.movies(from, to, q.settings, force, update) },
        )
        return when (q.tab) {
            CalendarTab.SHOWS -> episodes
            CalendarTab.MOVIES -> movies
            CalendarTab.ALL -> combine(episodes, movies) { a, b -> mergeCalendarStates(base, a, b) }
        }
    }

    private fun mergeCalendarStates(
        base: CalendarUiState, a: CalendarUiState, b: CalendarUiState,
    ): CalendarUiState {
        fun ordered(first: List<CalendarItem>, second: List<CalendarItem>) =
            (second + first).distinctBy { it.key }.sortedWith(compareBy(
                { it.date }, { it.movie?.hasTime == false }, { it.releaseAt }, { it.title },
            ))
        val entries = if (a.passed == null && b.passed == null) null else ordered(a.passed.orEmpty(), b.passed.orEmpty())
        val visibleKeys = entries.orEmpty().mapTo(HashSet()) { it.key }
        val rejected = ordered(a.rejected, b.rejected).filterNot { it.key in visibleKeys }
        val left = a.summary
        val right = b.summary
        val summary = CalendarSummary(
            entries.orEmpty().size, rejected.size, left.hiddenByCategory + right.hiddenByCategory,
            left.hiddenByUser + right.hiddenByUser, left.notOnMyApps + right.notOnMyApps, left.usedImdb || right.usedImdb,
        )
        return base.copy(
            loading = a.loading || b.loading,
            passed = entries,
            rejected = rejected,
            summary = summary,
            error = a.error ?: b.error,
        )
    }

    private fun move(forward: Boolean) {
        val current = position.value
        val sign = if (forward) 1L else -1L
        val date = current.anchor
        val moved = when (current.mode) {
            CalendarMode.TODAY, CalendarMode.DAY -> date.plusDays(sign)
            CalendarMode.WEEK -> date.plusWeeks(sign)
            CalendarMode.MONTH -> date.withDayOfMonth(1).plusMonths(sign)
            CalendarMode.UPCOMING -> date.plusDays(sign * UPCOMING_DAYS)
        }
        updatePosition(current.copy(mode = if (current.mode == CalendarMode.TODAY) CalendarMode.DAY else current.mode, anchor = moved))
    }

    fun previous() = move(forward = false)
    fun next() = move(forward = true)
    fun goToToday() = setMode(CalendarMode.TODAY)
    fun refreshToday() {
        if (position.value.mode == CalendarMode.TODAY) goToToday()
    }
    fun setMode(m: CalendarMode) {
        val date = if (m == CalendarMode.TODAY || m == CalendarMode.UPCOMING) RomaniaTime.today() else position.value.anchor
        updatePosition(CalendarPosition(m, date))
    }
    fun setTab(t: CalendarTab) {
        savedState["tab"] = t.name
        tab.value = t
    }

    fun openDay(date: LocalDate) = updatePosition(position.value.openDay(date))

    fun openWeek(monday: LocalDate) = updatePosition(position.value.openWeek(monday))

    /** Back revine la ziua sau la butonul săptămânii din luna de origine. */
    fun back(): String? {
        val current = position.value
        updatePosition(current.back(RomaniaTime.today()))
        return current.monthFocusKey
    }

    private fun updatePosition(next: CalendarPosition) {
        savedState["mode"] = next.mode.name
        savedState["anchor"] = next.anchor.toString()
        savedState["month"] = next.month?.toString()
        savedState["openedDay"] = next.openedDay?.toString()
        savedState["openedWeek"] = next.openedWeek?.toString()
        position.value = next
    }

    fun toggleShowRejected() = showRejected.update { !it }

    fun toggleOnlyFollowed() = onlyFollowed.update { !it }

    fun toggleOnlyMyApps() = edit { it.copy(onlyInstalledApps = !it.onlyInstalledApps) }

    fun toggleMovieKind(kind: MovieReleaseKind) = edit {
        it.copy(movieKinds = if (kind in it.movieKinds) it.movieKinds - kind else it.movieKinds + kind)
    }

    fun reload() {
        refresh.update { it + 1 }
        viewModelScope.launch { runCatching { tracking.sync() } }
    }

    fun toggleFollow(item: CalendarItem) {
        val saved = SavedShow(item.tmdbId.toInt(), item.title)
        viewModelScope.launch {
            val current = calendar.settings.first()
            val onTracker = if (item.isMovie) current.trackedMovies.any { it.id == saved.id } else current.isTracked(saved.id)
            calendar.updateSettings { s ->
                when {
                    item.isMovie && item.followed -> s.unfollowMovie(saved.id)
                    item.isMovie -> s.followMovie(saved)
                    item.followed -> s.unfollow(saved.id)
                    else -> s.follow(saved)
                }
            }
            _message.value = when {
                !item.followed -> "Urmărești „${item.title}”: apare mereu în calendar"
                onTracker -> "„${item.title}” e în biblioteca ta, deci rămâne urmărit. Ca să dispară, folosește „Ascunde”."
                else -> "Nu mai urmărești „${item.title}”"
            }
        }
    }

    fun hide(item: CalendarItem) {
        val saved = SavedShow(item.tmdbId.toInt(), item.title)
        edit { s -> if (item.isMovie) s.hideMovie(saved) else s.hide(saved) }
        _message.value = "„${item.title}” ascuns din calendar (îl poți reafișa din Setări → Calendar)"
    }

    fun markWatched(item: CalendarItem) {
        val ep = item.episode
        val media = if (ep != null) {
            MediaRef(
                type = MediaType.EPISODE,
                ids = ExternalIds(tmdb = ep.showId.toLong(), imdb = ep.imdbId),
                title = ep.showName,
                season = ep.seasonNumber,
                episode = ep.episodeNumber,
                episodeTitle = ep.episodeTitle,
            )
        } else {
            val m = item.movie ?: return
            MediaRef(MediaType.MOVIE, ExternalIds(tmdb = m.movieId.toLong(), imdb = m.imdbId), m.title, m.releaseDate.year)
        }
        viewModelScope.launch {
            _message.value = try {
                tracking.setWatched(media, !item.watched)
                if (ep != null) runCatching { tracking.refreshUpNext(media.asShow()) }
                if (item.watched) "Marcat ca nevăzut" else "Marcat ca văzut"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED && !tracking.hasConnectedTracker()) "Conectează Trakt sau SIMKL în Setări ca să marchezi ce ai văzut"
                else "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Adaugă filmul / serialul în Lista mea (De văzut). */
    fun addToList(item: CalendarItem) {
        val type = if (item.isMovie) MediaType.MOVIE else MediaType.SHOW
        val media = MediaRef(type, ExternalIds(tmdb = item.tmdbId), item.episode?.showName ?: item.title)
        viewModelScope.launch {
            _message.value = try {
                tracking.setInWatchlist(media, true)
                "„${media.title}” adăugat în Lista mea"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /** Apăsare lungă: deschide „Alege unde vezi” pentru episod sau film. */
    fun chooseWhere(item: CalendarItem) {
        viewModelScope.launch {
            try {
                val settings = catalogPrefs.settings.first()
                val kind = if (item.isMovie) TitleKind.MOVIE else TitleKind.TV
                val details = catalog.details(kind, item.tmdbId, settings)
                val ep = item.episode
                val request = if (ep == null) {
                    details.playbackRequest()
                } else {
                    details.playbackRequest().copy(
                        type = PlaybackRequest.Type.EPISODE,
                        season = ep.seasonNumber,
                        episode = ep.episodeNumber,
                        episodeTitle = ep.episodeTitle,
                        runtimeMinutes = ep.runtimeMinutes ?: details.runtimeMinutes,
                    )
                }
                _watch.value = request to details.availability
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Nu am putut încărca titlul: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    fun consumeWatch() {
        _watch.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun edit(transform: (CalendarSettings) -> CalendarSettings) {
        viewModelScope.launch { calendar.updateSettings(transform) }
    }
}
