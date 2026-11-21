package ro.ak.vodunify.core.calendar.tmdb

import java.time.LocalDate

/** The TMDB calls the calendar needs. Implemented by [TmdbCalendarApi] over the app's TmdbClient. */
interface TmdbApi {
    /** Shows that have at least one episode airing between [from] and [to] (inclusive). */
    suspend fun discoverTvAiring(from: LocalDate, to: LocalDate, page: Int): PagedResponse<TvSummaryDto>

    /** Shows whose first episode airs between [from] and [to], most popular first. */
    suspend fun discoverTvPremieres(from: LocalDate, to: LocalDate, page: Int): PagedResponse<TvSummaryDto>

    /** Show details with `watch/providers` and `external_ids` appended. */
    suspend fun tvDetails(showId: Int): TvDetailsDto

    suspend fun season(showId: Int, seasonNumber: Int): SeasonDto

    suspend fun tvWatchProviders(showId: Int): WatchProvidersDto

    /** All TV providers TMDB knows for a region, for Settings. */
    suspend fun tvProviderList(region: String): ProviderListDto

    /**
     * Movies with a release of one of [releaseTypes] (TMDB types, e.g. 2|3 cinema,
     * 4 digital) in [region] between [from] and [to], most popular first.
     */
    suspend fun discoverMovieReleases(
        region: String,
        releaseTypes: Set<Int>,
        from: LocalDate,
        to: LocalDate,
        page: Int,
    ): PagedResponse<MovieSummaryDto>

    /** Movies whose first release anywhere is between [from] and [to], most popular first. */
    suspend fun discoverMoviePremieres(from: LocalDate, to: LocalDate, page: Int): PagedResponse<MovieSummaryDto>

    /** Movie details with `release_dates`, `watch/providers` and `external_ids` appended. */
    suspend fun movieDetails(movieId: Int): MovieDetailsDto

    /** Movie search by title (and year, when known), to find a film from a Romanian cinema program. */
    suspend fun searchMovie(query: String, year: Int?): PagedResponse<MovieSummaryDto>

}


class TmdbException(message: String, val httpCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

object CalendarImages {
    private const val BASE = "https://image.tmdb.org/t/p/"
    fun poster(path: String?): String? = path?.let { BASE + "w342" + it }
    fun still(path: String?): String? = path?.let { BASE + "w300" + it }
    fun logo(path: String?): String? = path?.let { BASE + "w92" + it }
    fun backdrop(path: String?): String? = path?.let { BASE + "w1280" + it }
    fun profile(path: String?): String? = path?.let { BASE + "w185" + it }
}
