package ro.ak.vodunify.core.calendar.engine

import ro.ak.vodunify.core.calendar.anticipated.AnticipatedShowsSource
import ro.ak.vodunify.core.calendar.anticipated.TmdbAnticipatedSource
import ro.ak.vodunify.core.calendar.filter.ContentClassifier
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.CalendarResult
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.ImdbRating
import ro.ak.vodunify.core.calendar.model.ProviderAvailability
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingApp
import ro.ak.vodunify.core.calendar.omdb.ImdbRatings
import ro.ak.vodunify.core.calendar.quality.QualityGate
import ro.ak.vodunify.core.calendar.quality.QualityInput
import ro.ak.vodunify.core.calendar.quality.QualityVerdict
import ro.ak.vodunify.core.calendar.time.ReleaseTimeResolver
import ro.ak.vodunify.core.calendar.time.ResolvedRelease
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.calendar.tmdb.EpisodeDto
import ro.ak.vodunify.core.calendar.tmdb.TmdbApi
import ro.ak.vodunify.core.calendar.tmdb.TmdbException
import ro.ak.vodunify.core.calendar.tmdb.CalendarImages
import ro.ak.vodunify.core.calendar.tmdb.TvDetailsDto
import ro.ak.vodunify.core.calendar.tmdb.TvSummaryDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Builds the series calendar from TMDB.
 *
 * 1. `discover/tv` with `air_date` filters finds shows with an episode in the window.
 * 2. Hidden categories (anime, Turkish, …) and shows the user hid are dropped
 *    before any extra request.
 * 3. For each show: details + watch providers, then the seasons of the last/next
 *    episode to get every episode in the window, plus the IMDb rating (OMDb).
 * 4. Match the user's app and estimate the original broadcast in Romania time.
 * 5. The quality gate keeps returning shows above the rating thresholds and new
 *    shows from the anticipated list.
 */
class EpisodeCalendarRepository(
    private val apiFactory: (apiKey: String) -> TmdbApi,
    private val imdbRatings: ImdbRatings? = null,
    private val anticipatedSource: AnticipatedShowsSource = TmdbAnticipatedSource(),
    private val resolver: ReleaseTimeResolver = ReleaseTimeResolver(),
    private val maxDiscoverPagesPerDay: Int = 4,
    private val maxDiscoverPages: Int = 15,
    private val concurrency: Int = 8,
    private val today: () -> LocalDate = { RomaniaTime.today() },
) {

    suspend fun episodesBetween(
        from: LocalDate,
        to: LocalDate,
        settings: CalendarSettings,
        apiKey: String,
        omdbKey: String = "",
    ): CalendarResult = coroutineScope {
        require(!to.isBefore(from)) { "to < from" }
        val api = apiFactory(apiKey)
        // The first airing can move to the adjacent date when converted to Bucharest time.
        val windowFrom = from.minusDays(1)
        val windowTo = to.plusDays(1)

        val anticipatedDeferred = async {
            try {
                anticipatedSource.showIds(api, today())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptySet()
            }
        }

        val days = ChronoUnit.DAYS.between(windowFrom, windowTo).toInt() + 1
        val discovered = discoverShows(api, windowFrom, windowTo, minOf(maxDiscoverPages, maxDiscoverPagesPerDay * days))
        var hiddenByCategory = 0
        var hiddenByUser = 0
        val shows = discovered.filter { show ->
            when {
                settings.isFollowed(show.id) -> true
                settings.isHidden(show.id) -> false.also { hiddenByUser++ }
                ContentClassifier.classify(show.originalLanguage, show.originCountry, show.genreIds)
                    .any { it in settings.hiddenCategories } -> false.also { hiddenByCategory++ }
                else -> true
            }
        }
        val anticipated = anticipatedDeferred.await()
        val useImdb = imdbRatings != null && omdbKey.isNotBlank()

        val semaphore = Semaphore(concurrency)
        // Followed shows are always checked, even when discover ranks them too low.
        val showIds = (shows.map { it.id } + settings.followedShowIds()).distinct()
        val results = showIds.map { showId ->
            async {
                semaphore.withPermit {
                    try {
                        Result.success(episodesForShow(api, showId, windowFrom, windowTo, settings, anticipated, omdbKey.takeIf { useImdb }))
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
            .filter { !it.releaseDate.isBefore(from) && !it.releaseDate.isAfter(to) }
            .distinctBy { it.key }
            .sortedWith(compareBy({ it.releaseAt }, { it.showName }, { it.seasonNumber }, { it.episodeNumber }))

        // Details can reveal a category discover did not (e.g. TMDB type "Talk Show").
        val (categoryHidden, visible) = inRange.partition { e ->
            !e.isFollowed && e.categories.any { it in settings.hiddenCategories }
        }
        hiddenByCategory += categoryHidden.distinctBy { it.showId }.size

        val (onMyApps, notOnMyApps) = if (settings.onlyInstalledApps) {
            visible.partition { it.watchOn != null || it.isFollowed }
        } else {
            visible to emptyList()
        }
        val (passed, rejected) = onMyApps.partition { it.verdict.passed }

        CalendarResult(
            entries = passed,
            rejected = rejected,
            hiddenByCategory = hiddenByCategory,
            hiddenByUser = hiddenByUser,
            notOnMyApps = notOnMyApps.size,
            usedImdb = useImdb,
            anticipatedSource = anticipatedSource.label,
        )
    }

    private suspend fun discoverShows(api: TmdbApi, from: LocalDate, to: LocalDate, maxPages: Int): List<TvSummaryDto> {
        val first = api.discoverTvAiring(from, to, page = 1)
        val lastPage = minOf(first.totalPages, maxPages)
        val rest = (2..lastPage).flatMap { page -> api.discoverTvAiring(from, to, page).results }
        return (first.results + rest).distinctBy { it.id }
    }

    private suspend fun episodesForShow(
        api: TmdbApi,
        showId: Int,
        from: LocalDate,
        to: LocalDate,
        settings: CalendarSettings,
        anticipated: Set<Int>,
        omdbKey: String?,
    ): List<EpisodeEntry> {
        val details = api.tvDetails(showId)
        val anchors = listOfNotNull(details.lastEpisodeToAir, details.nextEpisodeToAir)
        // Nothing can air in the window when the last aired episode is before it and the
        // next one is after it (or unknown). Saves the season request for most followed
        // shows (e.g. a long Simkl "Watching" list full of ended series).
        val lastDate = details.lastEpisodeToAir?.parsedAirDate()
        val nextDate = details.nextEpisodeToAir?.parsedAirDate()
        val lastBefore = details.lastEpisodeToAir == null || (lastDate != null && lastDate.isBefore(from))
        val nextAfter = details.nextEpisodeToAir == null || (nextDate != null && nextDate.isAfter(to))
        if (lastBefore && nextAfter) return emptyList()
        val seasonNumbers = anchors.map { it.seasonNumber }.filter { it > 0 }.distinct()

        val episodes = seasonNumbers.flatMap { season ->
            try {
                api.season(showId, season).episodes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                anchors.filter { it.seasonNumber == season }
            }
        }.ifEmpty { anchors }

        val dated = episodes
            .mapNotNull { episode -> episode.parsedAirDate()?.let { episode to it } }
            .filter { (_, date) -> !date.isBefore(from) && !date.isAfter(to) }
        if (dated.isEmpty()) return emptyList()

        val categories = ContentClassifier.classify(
            details.originalLanguage,
            details.originCountry,
            details.genres.map { it.id },
            details.type,
        )
        val (app, region) = matchInstalledApp(details, settings)
        val availability = availability(details)
        val networkIds = details.networks.map { it.id }.toSet()
        val originCountries = details.networks.mapNotNull { it.originCountry } + details.originCountry
        val lastEpisodeOfSeason = episodes.groupBy { it.seasonNumber }.mapValues { (_, list) -> list.maxOf { it.episodeNumber } }
        val imdbId = details.externalIds?.imdbId?.takeIf { it.isNotBlank() }
        val imdb = if (omdbKey != null && imdbId != null) imdbRatings?.rating(imdbId, omdbKey) else null
        val followed = settings.isFollowed(details.id)

        return dated.map { (episode, date) ->
            val release = resolver.original(date, originCountries, networkIds)
            val isNewShow = episode.seasonNumber == 1
            val isAnticipated = details.id in anticipated
            val verdict = QualityGate.evaluate(
                QualityInput(
                    isNewShow = isNewShow,
                    isAnticipated = isAnticipated,
                    isFollowed = followed,
                    tmdbRating = details.voteAverage,
                    tmdbVotes = details.voteCount,
                    imdb = imdb,
                ),
                settings.thresholds,
            )
            toEntry(
                details, episode, release, app, region, availability, categories,
                isFinale = episode.episodeType == "finale" ||
                    (details.status == "Ended" && lastEpisodeOfSeason[episode.seasonNumber] == episode.episodeNumber),
                imdb = imdb,
                isNewShow = isNewShow,
                isAnticipated = isAnticipated,
                followed = followed,
                verdict = verdict,
            )
        }
    }

    private fun toEntry(
        details: TvDetailsDto,
        episode: EpisodeDto,
        release: ResolvedRelease,
        app: StreamingApp?,
        region: Region?,
        availability: List<ProviderAvailability>,
        categories: Set<ContentFilter>,
        isFinale: Boolean,
        imdb: ImdbRating?,
        isNewShow: Boolean,
        isAnticipated: Boolean,
        followed: Boolean,
        verdict: QualityVerdict,
    ) = EpisodeEntry(
        showId = details.id,
        showName = details.name,
        posterUrl = CalendarImages.poster(details.posterPath),
        stillUrl = CalendarImages.still(episode.stillPath),
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        episodeTitle = episode.name,
        overview = ro.ak.vodunify.core.catalog.TmdbMappers.cleanOverview(episode.overview),
        releaseAt = release.at,
        timeIsApproximate = release.approximate,
        watchOn = app,
        watchRegion = region,
        availability = availability,
        tmdbRating = details.voteAverage,
        tmdbVotes = details.voteCount,
        imdbId = details.externalIds?.imdbId,
        categories = categories,
        isSeasonPremiere = episode.episodeNumber == 1,
        isNewShow = isNewShow,
        showOverview = ro.ak.vodunify.core.catalog.TmdbMappers.cleanOverview(details.overview),
        backdropUrl = CalendarImages.backdrop(details.backdropPath),
        networkNames = details.networks.map { it.name }.filter { it.isNotBlank() },
        genreNames = details.genres.map { it.name }.filter { it.isNotBlank() },
        runtimeMinutes = episode.runtime ?: details.episodeRunTime.firstOrNull(),
        isFinale = isFinale,
        imdb = imdb,
        isAnticipated = isAnticipated,
        isFollowed = followed,
        verdict = verdict,
        hasTime = release.hasTime,
        wikidataId = details.externalIds?.wikidataId?.takeIf { it.isNotBlank() },
    )

    companion object {
        /**
         * First installed app (in the user's order) that carries the show in the
         * region the user set for that app. Falls back to the show's original
         * network when TMDB has no provider data yet (common for brand-new shows).
         */
        fun matchInstalledApp(details: TvDetailsDto, settings: CalendarSettings): Pair<StreamingApp?, Region?> {
            val providers = details.watchProviders?.results.orEmpty()
            val installed = settings.installedApps()
            installed.firstOrNull { (app, region) ->
                providers[region.code]?.streaming.orEmpty().any { it.providerId in app.tmdbProviderIds }
            }?.let { return it.first to it.second }

            val networkIds = details.networks.map { it.id }.toSet()
            installed.firstOrNull { (app, _) -> app.tmdbNetworkIds.any { it in networkIds } }
                ?.let { return it.first to it.second }
            return null to null
        }

        fun availability(details: TvDetailsDto): List<ProviderAvailability> {
            val providers = details.watchProviders?.results.orEmpty()
            return Region.entries.flatMap { region ->
                providers[region.code]?.streaming.orEmpty()
                    .distinctBy { it.providerId }
                    .sortedBy { it.displayPriority }
                    .map { ProviderAvailability(it.providerId, it.providerName, region, CalendarImages.logo(it.logoPath)) }
            }
        }

        private fun EpisodeDto.parsedAirDate(): LocalDate? = airDate?.takeIf { it.isNotBlank() }?.let {
            try {
                LocalDate.parse(it)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }
}
