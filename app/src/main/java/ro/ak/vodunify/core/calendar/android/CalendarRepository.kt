package ro.ak.vodunify.core.calendar.android

import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver
import ro.ak.vodunify.core.calendar.model.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.calendar.cinema.CinemaCityRoSource
import ro.ak.vodunify.core.calendar.cinema.OrangeFilmSource
import ro.ak.vodunify.core.calendar.cinema.RoCinemaProgram
import ro.ak.vodunify.core.calendar.engine.EpisodeCalendarRepository
import ro.ak.vodunify.core.calendar.engine.MovieCalendarRepository
import ro.ak.vodunify.core.calendar.model.CalendarResult
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieCalendarResult
import ro.ak.vodunify.core.calendar.model.MovieRelease
import ro.ak.vodunify.core.calendar.model.SavedShow
import ro.ak.vodunify.core.calendar.omdb.OmdbClient
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.calendar.tmdb.TmdbCalendarApi
import ro.ak.vodunify.core.calendar.tmdb.TmdbFetch
import ro.ak.vodunify.core.calendar.today.TodayDigest
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Id-ul aplicației din Calendar → id-ul serviciului din registrul aplicației (Setări → Aplicații și servicii). */
internal val CALENDAR_TO_SERVICE = mapOf(
    "netflix" to "netflix",
    "max" to "hbomax",
    "disney" to "disney",
    "prime" to "prime",
    "appletv" to "appletv",
    "skyshowtime" to "skyshowtime",
    "peacock" to "peacock",
    "voyo" to "voyo",
)

/** Răspunsul OMDb pentru o notă IMDb: [rating] null = fără notă (dacă [answered]) sau necunoscut. */
data class OmdbAnswer(val rating: Double?, val answered: Boolean)

/** Ce ai văzut deja, după trackere: episoade ("showId-sezon-episod") și filme (id TMDb). */
data class WatchedSet(val episodes: Set<String> = emptySet(), val movies: Set<Int> = emptySet())

/**
 * Calendarul (din AK VOD Calendar) legat de restul aplicației:
 * - TMDb prin [TmdbClient] (aceeași cheie, același cache Room, mod offline);
 * - „ce urmărești” din Trakt / SIMKL (watchlist, seriale în curs / următorul episod), prin baza
 *   de date a [TrackingRepository], plus urmărite / ascunse local;
 * - „văzut” din istoricul sincronizat.
 */
@Singleton
class CalendarRepository @Inject constructor(
    private val tmdb: TmdbClient,
    private val prefs: CalendarPreferences,
    private val catalogPrefs: CatalogPreferences,
    private val tracking: TrackingRepository,
    private val trackingDao: TrackingDao,
    private val playback: PlaybackPreferences,
    private val resolver: TargetResolver,
    http: OkHttpClient,
) {
    private val omdb = OmdbClient(http)

    /** Programul real din cinematografele din România: Cinema City (cu o lună înainte) + Orange Film (săptămâna curentă). */
    private val roCinema = RoCinemaProgram(listOf(CinemaCityRoSource(http), OrangeFilmSource(http)))

    private val fetch = object : TmdbFetch {
        override suspend fun <T> get(path: String, params: Map<String, String?>, serializer: KSerializer<T>, ttlMillis: Long): T =
            json.decodeFromString(serializer, tmdb.getRaw(path, params, ttlMillis))
    }

    private val episodeRepos = ConcurrentHashMap<String, EpisodeCalendarRepository>()
    private val movieRepos = ConcurrentHashMap<String, MovieCalendarRepository>()

    private fun episodeRepo(language: String) = episodeRepos.getOrPut(language) {
        EpisodeCalendarRepository(apiFactory = { TmdbCalendarApi(fetch, language) }, imdbRatings = omdb)
    }

    private fun movieRepo(language: String) = movieRepos.getOrPut(language) {
        MovieCalendarRepository(apiFactory = { TmdbCalendarApi(fetch, language) }, imdbRatings = omdb, roCinema = roCinema)
    }

    val isConfigured: Boolean get() = tmdb.isConfigured

    /** Setările salvate + listele din trackere (urmărite automat). */
    /**
     * Aplicațiile tale și țara fiecăreia vin din setările generale (aplicațiile instalate + biblioteca RO/US a
     * fiecărui serviciu, din Aplicații și servicii), nu dintr-o listă separată a Calendarului.
     */
    private val installedApps: Flow<Set<TargetApp>> =
        flow { emit(runCatching { resolver.installedApps().keys }.getOrDefault(emptySet())) }.flowOn(Dispatchers.IO)

    private fun withAppsFromPlayback(s: CalendarSettings, p: PlaybackSettings, installed: Set<TargetApp>): CalendarSettings =
        CALENDAR_TO_SERVICE.entries.fold(s) { acc, (calendarId, serviceId) ->
            val service = StreamingService.byId(serviceId) ?: return@fold acc
            val app = TargetApp.byId(service.appId)
            acc.updateApp(calendarId) {
                it.copy(
                    installed = app != null && app in installed,
                    region = Region.fromCode(p.marketOf(service).code) ?: Region.RO,
                )
            }
        }

    val settings: Flow<CalendarSettings> = combine(
        prefs.settings,
        playback.settings,
        installedApps,
    ) { s, p, installed -> withAppsFromPlayback(s, p, installed) }
        .combine(tracking.library) { s, library ->
        val shows = mutableListOf<SavedShow>()
        val movies = mutableListOf<SavedShow>()
        library.forEach { entry ->
            val id = entry.ref.tmdbId?.toInt() ?: return@forEach
            val followed = entry.statuses.any { it == LibraryStatus.WATCHLIST || it == LibraryStatus.IN_PROGRESS || it == LibraryStatus.UP_NEXT }
            if (!followed) return@forEach
            when (entry.ref.type) {
                MediaType.MOVIE.name -> if (LibraryStatus.WATCHLIST in entry.statuses) movies += SavedShow(id, entry.ref.title)
                else -> shows += SavedShow(id, entry.ref.title)
            }
        }
        s.copy(trackedShows = shows.distinctBy { it.id }, trackedMovies = movies.distinctBy { it.id })
    }.distinctUntilChanged()

    val watched: Flow<WatchedSet> = trackingDao.latestWatches().map { list ->
        val episodes = mutableSetOf<String>()
        val movies = mutableSetOf<Int>()
        list.filter { ro.ak.vodunify.core.connector.ExternalTrackers.isVisible(it.source) }.forEach { w ->
            val id = w.ref.tmdbId?.toInt() ?: return@forEach
            if (w.ref.season != null && w.ref.episode != null) {
                episodes += "$id-${w.ref.season}-${w.ref.episode}"
            } else if (w.ref.type == MediaType.MOVIE.name) {
                movies += id
            }
        }
        WatchedSet(episodes, movies)
    }.distinctUntilChanged()

    suspend fun updateSettings(transform: (CalendarSettings) -> CalendarSettings) = prefs.update(transform)

    fun omdbKey(s: CalendarSettings): String = s.omdbApiKey.trim().ifBlank { BuildConfig.OMDB_API_KEY }

    private suspend fun language(): String = catalogPrefs.settings.first().apiLanguage

    suspend fun episodes(from: LocalDate, to: LocalDate, s: CalendarSettings): CalendarResult =
        episodeRepo(language()).episodesBetween(from, to, s, apiKey = "", omdbKey = omdbKey(s))

    suspend fun movies(from: LocalDate, to: LocalDate, s: CalendarSettings): MovieCalendarResult =
        movieRepo(language()).moviesBetween(from, to, s, apiKey = "", omdbKey = omdbKey(s))

    /** Notele IMDb ale episoadelor unui sezon (episod → notă); gol fără cheie OMDb. */
    suspend fun episodeImdbRatings(seriesImdbId: String, season: Int): Map<Int, Double> =
        omdb.seasonRatings(seriesImdbId, season, omdbKey(prefs.settings.first()))

    /**
     * Nota IMDb a unui titlu („tt…”) prin OMDb, cu cheia din Setări → Calendar (sau secretul OMDB_API_KEY).
     * [answered] = OMDb a răspuns (titlul fără notă e un răspuns); false fără cheie, după limită sau fără rețea.
     */
    suspend fun imdbRating(imdbId: String): OmdbAnswer {
        val key = omdbKey(prefs.settings.first())
        if (key.isBlank()) return OmdbAnswer(null, answered = false)
        val r = omdb.rating(imdbId, key)
        return OmdbAnswer(r?.rating, answered = r != null || omdb.answered(imdbId))
    }

    /** Toate lansările unui film (cinema RO / US, streaming, digital), pentru „În curând” / „Noutăți” din Acasă. */
    suspend fun movieReleases(movieId: Long): List<MovieRelease> =
        movieRepo(language()).releasesOf(movieId.toInt(), settings.first())

    /** Ce apare azi (pentru notificare și canalul TV), fără ce ai văzut deja. Null fără cheie TMDb. */
    suspend fun today(): TodayDigest? {
        if (!tmdb.isConfigured) return null
        val s = settings.first()
        val day = RomaniaTime.today()
        var failure: Exception? = null
        val episodes = try {
            episodes(day, day, s).entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            emptyList()
        }
        val movies = if (!s.notifications.includeMovies) emptyList() else try {
            movies(day, day, s).entries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = failure ?: e
            emptyList()
        }
        if (episodes.isEmpty() && movies.isEmpty() && failure != null) throw failure
        val w = watched.first()
        return TodayDigest.build(day, episodes, movies, w.episodes, w.movies)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    }
}
