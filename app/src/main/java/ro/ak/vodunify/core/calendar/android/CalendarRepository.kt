package ro.ak.vodunify.core.calendar.android

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
import ro.ak.vodunify.core.calendar.engine.EpisodeCalendarRepository
import ro.ak.vodunify.core.calendar.engine.MovieCalendarRepository
import ro.ak.vodunify.core.calendar.model.CalendarResult
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieCalendarResult
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
    http: OkHttpClient,
) {
    private val omdb = OmdbClient(http)

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
        MovieCalendarRepository(apiFactory = { TmdbCalendarApi(fetch, language) }, imdbRatings = omdb)
    }

    val isConfigured: Boolean get() = tmdb.isConfigured

    /** Setările salvate + listele din trackere (urmărite automat). */
    val settings: Flow<CalendarSettings> = combine(prefs.settings, tracking.library) { s, library ->
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
        list.forEach { w ->
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
