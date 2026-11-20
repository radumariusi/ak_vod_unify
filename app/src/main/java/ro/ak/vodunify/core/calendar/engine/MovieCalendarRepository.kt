package ro.ak.vodunify.core.calendar.engine

import ro.ak.vodunify.core.calendar.anticipated.AnticipatedMoviesSource
import ro.ak.vodunify.core.calendar.anticipated.TmdbAnticipatedMoviesSource
import ro.ak.vodunify.core.calendar.filter.ContentClassifier
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieCalendarResult
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.ProviderAvailability
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.omdb.ImdbRatings
import ro.ak.vodunify.core.calendar.quality.QualityGate
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.calendar.tmdb.MovieDetailsDto
import ro.ak.vodunify.core.calendar.tmdb.MovieSummaryDto
import ro.ak.vodunify.core.calendar.tmdb.TmdbApi
import ro.ak.vodunify.core.calendar.tmdb.TmdbException
import ro.ak.vodunify.core.calendar.tmdb.CalendarImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/**
 * Builds the movie calendar from TMDB.
 *
 * 1. `discover/movie` per region and release type finds candidates: cinema
 *    (types 2|3) in the US and in Romania, digital (type 4) in the US, and in
 *    Romania too when an app is set to RO. Most popular first, a few pages each.
 * 2. Hidden categories and movies the user hid are dropped before any extra request.
 * 3. For each movie: details with release dates, watch providers and the IMDb id,
 *    then [MovieReleasePlanner] works out its cinema / digital / streaming releases
 *    for the user's apps and regions, each turned into a moment in Romania time.
 * 4. The quality gate keeps movies above the rating thresholds and, while they
 *    have too few votes to judge, those on the anticipated list.
 */
class MovieCalendarRepository(
    private val apiFactory: (apiKey: String) -> TmdbApi,
    private val imdbRatings: ImdbRatings? = null,
    private val anticipatedSource: AnticipatedMoviesSource = TmdbAnticipatedMoviesSource(),
    private val pagesPerQueryForDay: Int = 1,
    private val pagesPerQuery: Int = 3,
    private val concurrency: Int = 8,
    private val today: () -> LocalDate = { RomaniaTime.today() },
) {

    suspend fun moviesBetween(
        from: LocalDate,
        to: LocalDate,
        settings: CalendarSettings,
        apiKey: String,
        omdbKey: String = "",
    ): MovieCalendarResult = coroutineScope {
        require(!to.isBefore(from)) { "to < from" }
        val api = apiFactory(apiKey)
        val kinds = settings.movieKinds
        if (kinds.isEmpty()) return@coroutineScope MovieCalendarResult(emptyList(), anticipatedSource = anticipatedSource.label)

        val anticipatedDeferred = async {
            try {
                anticipatedSource.movieIds(api, today())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }

        val maxPages = if (from == to) pagesPerQueryForDay else pagesPerQuery
        val discovered = discoverQueries(settings).map { (region, types) ->
            async { discover(api, region, types, from, to, maxPages) }
        }.awaitAll()
        if (discovered.isNotEmpty() && discovered.all { it.isFailure }) {
            throw discovered.first().exceptionOrNull() ?: TmdbException("TMDB: eroare necunoscută")
        }
        val candidates = discovered.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }

        var hiddenByUser = 0
        val hiddenByCategoryIds = mutableSetOf<Int>()
        val kept = candidates.filter { movie ->
            when {
                movie.adult -> false
                settings.isMovieFollowed(movie.id) -> true
                settings.isMovieHidden(movie.id) -> false.also { hiddenByUser++ }
                ContentClassifier.classify(movie.originalLanguage, emptyList(), movie.genreIds)
                    .any { it in settings.hiddenCategories } -> false.also { hiddenByCategoryIds += movie.id }
                else -> true
            }
        }
        val anticipated = anticipatedDeferred.await()
        val useImdb = imdbRatings != null && omdbKey.isNotBlank()

        val semaphore = Semaphore(concurrency)
        val ids = (kept.map { it.id } + settings.followedMovieIds()).distinct()
        val results = ids.map { id ->
            async {
                semaphore.withPermit {
                    try {
                        Result.success(entriesForMovie(api, id, from, to, settings, anticipated, omdbKey.takeIf { useImdb }))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }
        }.awaitAll()
        if (results.isNotEmpty() && results.all { it.isFailure }) {
            throw results.first().exceptionOrNull() ?: TmdbException("TMDB: eroare necunoscută")
        }

        val inRange = results.flatMap { it.getOrDefault(emptyList()) }
            .distinctBy { it.key }
            .sortedWith(movieOrder)

        // Details can reveal a category discover did not (origin country: Indian, Turkish, …).
        val (categoryHidden, visible) = inRange.partition { e ->
            !e.isFollowed && e.categories.any { it in settings.hiddenCategories }
        }
        hiddenByCategoryIds += categoryHidden.map { it.movieId }

        // "Only my apps" only applies to streaming: cinema and digital aren't tied to an app.
        val (onMyApps, notOnMyApps) = if (settings.onlyInstalledApps) {
            visible.partition { it.kind != MovieReleaseKind.STREAMING || it.watchOn != null || it.isFollowed }
        } else {
            visible to emptyList()
        }
        val (passed, rejected) = onMyApps.partition { it.verdict.passed }

        MovieCalendarResult(
            entries = passed,
            rejected = rejected,
            hiddenByCategory = hiddenByCategoryIds.size,
            hiddenByUser = hiddenByUser,
            notOnMyApps = notOnMyApps.size,
            usedImdb = useImdb,
            anticipatedSource = anticipatedSource.label,
        )
    }

    /** (region, TMDB release types) to ask `discover/movie` for. */
    private fun discoverQueries(settings: CalendarSettings): List<Pair<Region, Set<Int>>> = buildList {
        val kinds = settings.movieKinds
        if (MovieReleaseKind.CINEMA_US in kinds) add(Region.US to setOf(2, 3))
        if (MovieReleaseKind.CINEMA_RO in kinds) add(Region.RO to setOf(2, 3))
        if (MovieReleaseKind.DIGITAL in kinds || MovieReleaseKind.STREAMING in kinds) add(Region.US to setOf(4))
        val roApps = settings.installedApps().any { it.second == Region.RO }
        if (MovieReleaseKind.STREAMING in kinds && roApps) add(Region.RO to setOf(4))
    }

    private suspend fun discover(
        api: TmdbApi,
        region: Region,
        types: Set<Int>,
        from: LocalDate,
        to: LocalDate,
        maxPages: Int,
    ): Result<List<MovieSummaryDto>> = try {
        val first = api.discoverMovieReleases(region.code, types, from, to, page = 1)
        val lastPage = minOf(first.totalPages, maxPages)
        val rest = (2..lastPage).flatMap { page -> api.discoverMovieReleases(region.code, types, from, to, page).results }
        Result.success(first.results + rest)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun entriesForMovie(
        api: TmdbApi,
        movieId: Int,
        from: LocalDate,
        to: LocalDate,
        settings: CalendarSettings,
        anticipated: Set<Int>,
        omdbKey: String?,
    ): List<MovieEntry> {
        val details = api.movieDetails(movieId)
        val all = MovieReleasePlanner.releases(details, settings)
        val inWindow = MovieReleasePlanner.calendarReleases(all, settings)
            .map { it to MovieReleasePlanner.moment(it) }
            .filter { (_, moment) -> moment.first.toLocalDate().let { !it.isBefore(from) && !it.isAfter(to) } }
        if (inWindow.isEmpty()) return emptyList()

        val categories = ContentClassifier.classify(details.originalLanguage, details.countries, details.genres.map { it.id })
        val imdbId = (details.imdbId ?: details.externalIds?.imdbId)?.takeIf { it.isNotBlank() }
        val imdb = if (omdbKey != null && imdbId != null) imdbRatings?.rating(imdbId, omdbKey) else null
        val followed = settings.isMovieFollowed(details.id)
        val isAnticipated = details.id in anticipated
        val verdict = QualityGate.evaluateMovie(
            tmdbRating = details.voteAverage,
            tmdbVotes = details.voteCount,
            imdb = imdb,
            isAnticipated = isAnticipated,
            isFollowed = followed,
            t = settings.movieThresholds,
        )
        val availability = availability(details)

        return inWindow.map { (release, moment) ->
            val (at, hasTime) = moment
            MovieEntry(
                movieId = details.id,
                title = details.title.ifBlank { details.originalTitle },
                originalTitle = details.originalTitle.takeIf { it.isNotBlank() && it != details.title },
                posterUrl = CalendarImages.poster(details.posterPath),
                backdropUrl = CalendarImages.backdrop(details.backdropPath),
                overview = details.overview,
                tagline = details.tagline?.takeIf { it.isNotBlank() },
                kind = release.kind,
                region = release.region,
                releaseAt = at,
                hasTime = hasTime,
                service = release.service,
                watchOn = release.service.takeIf { release.onMyApp },
                watchRegion = release.region.takeIf { release.onMyApp },
                limited = release.limited,
                availability = availability,
                tmdbRating = details.voteAverage,
                tmdbVotes = details.voteCount,
                imdbId = imdbId,
                imdb = imdb,
                categories = categories,
                genreNames = details.genres.map { it.name }.filter { it.isNotBlank() },
                runtimeMinutes = details.runtime?.takeIf { it > 0 },
                releases = all,
                isAnticipated = isAnticipated,
                isFollowed = followed,
                verdict = verdict,
                popularity = details.popularity,
                wikidataId = details.externalIds?.wikidataId?.takeIf { it.isNotBlank() },
            )
        }
    }

    companion object {
        /** By moment; on the same day cinema (no hour) goes after the timed releases, then by popularity. */
        val movieOrder: Comparator<MovieEntry> = compareBy<MovieEntry>(
            { it.releaseDate },
            { !it.hasTime },
            { it.releaseAt },
            { -it.popularity },
            { it.title },
            { it.kind.ordinal },
        )

        fun availability(details: MovieDetailsDto): List<ProviderAvailability> {
            val providers = details.watchProviders?.results.orEmpty()
            return Region.entries.flatMap { region ->
                providers[region.code]?.streaming.orEmpty()
                    .distinctBy { it.providerId }
                    .sortedBy { it.displayPriority }
                    .map { ProviderAvailability(it.providerId, it.providerName, region, CalendarImages.logo(it.logoPath)) }
            }
        }
    }
}
