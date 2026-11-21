package ro.ak.vodunify.core.calendar.tmdb

import kotlinx.serialization.KSerializer
import java.time.LocalDate

/**
 * How the calendar fetches a TMDb path. In the app this is [ro.ak.vodunify.core.tmdb.TmdbClient.get]
 * (same key, same Room cache, offline fallback as the catalog); tests plug in a mock server.
 */
interface TmdbFetch {
    suspend fun <T> get(path: String, params: Map<String, String?>, serializer: KSerializer<T>, ttlMillis: Long): T
}

/**
 * The calendar's TMDb calls (discover by air/release date, details with release dates,
 * seasons), built on the app's existing TMDb client instead of a second HTTP client.
 */
class TmdbCalendarApi(
    private val fetch: TmdbFetch,
    private val language: String? = "en-US",
    /** Discover lists change during the day; details and seasons can stay longer. */
    private val listTtlMillis: Long = 3 * HOUR,
    private val detailsTtlMillis: Long = 6 * HOUR,
) : TmdbApi {

    private suspend fun <T> get(path: String, serializer: KSerializer<T>, ttl: Long, params: Map<String, String?> = emptyMap()): T =
        fetch.get(path, params + ("language" to language), serializer, ttl)

    override suspend fun discoverTvAiring(from: LocalDate, to: LocalDate, page: Int): PagedResponse<TvSummaryDto> =
        get(
            "discover/tv", PagedResponse.serializer(TvSummaryDto.serializer()), listTtlMillis,
            mapOf(
                "air_date.gte" to from.toString(),
                "air_date.lte" to to.toString(),
                "timezone" to "Europe/Bucharest",
                "sort_by" to "popularity.desc",
                "include_null_first_air_dates" to "false",
                "page" to page.toString(),
            ),
        )

    override suspend fun discoverTvPremieres(from: LocalDate, to: LocalDate, page: Int): PagedResponse<TvSummaryDto> =
        get(
            "discover/tv", PagedResponse.serializer(TvSummaryDto.serializer()), listTtlMillis,
            mapOf(
                "first_air_date.gte" to from.toString(),
                "first_air_date.lte" to to.toString(),
                "sort_by" to "popularity.desc",
                "page" to page.toString(),
            ),
        )

    override suspend fun tvDetails(showId: Int): TvDetailsDto =
        get("tv/$showId", TvDetailsDto.serializer(), detailsTtlMillis, mapOf("append_to_response" to "watch/providers,external_ids"))

    override suspend fun season(showId: Int, seasonNumber: Int): SeasonDto =
        get("tv/$showId/season/$seasonNumber", SeasonDto.serializer(), detailsTtlMillis)

    override suspend fun tvWatchProviders(showId: Int): WatchProvidersDto =
        get("tv/$showId/watch/providers", WatchProvidersDto.serializer(), detailsTtlMillis)

    override suspend fun tvProviderList(region: String): ProviderListDto =
        get("watch/providers/tv", ProviderListDto.serializer(), 7 * 24 * HOUR, mapOf("watch_region" to region))

    override suspend fun discoverMovieReleases(
        region: String,
        releaseTypes: Set<Int>,
        from: LocalDate,
        to: LocalDate,
        page: Int,
    ): PagedResponse<MovieSummaryDto> =
        get(
            "discover/movie", PagedResponse.serializer(MovieSummaryDto.serializer()), listTtlMillis,
            mapOf(
                "region" to region,
                "with_release_type" to releaseTypes.sorted().joinToString("|"),
                "release_date.gte" to from.toString(),
                "release_date.lte" to to.toString(),
                "sort_by" to "popularity.desc",
                "include_adult" to "false",
                "include_video" to "false",
                "page" to page.toString(),
            ),
        )

    override suspend fun discoverMoviePremieres(from: LocalDate, to: LocalDate, page: Int): PagedResponse<MovieSummaryDto> =
        get(
            "discover/movie", PagedResponse.serializer(MovieSummaryDto.serializer()), listTtlMillis,
            mapOf(
                "primary_release_date.gte" to from.toString(),
                "primary_release_date.lte" to to.toString(),
                "sort_by" to "popularity.desc",
                "include_adult" to "false",
                "include_video" to "false",
                "page" to page.toString(),
            ),
        )

    override suspend fun movieDetails(movieId: Int): MovieDetailsDto =
        get("movie/$movieId", MovieDetailsDto.serializer(), detailsTtlMillis, mapOf("append_to_response" to "release_dates,watch/providers,external_ids,translations"))

    override suspend fun searchMovie(query: String, year: Int?): PagedResponse<MovieSummaryDto> =
        get(
            "search/movie", PagedResponse.serializer(MovieSummaryDto.serializer()), detailsTtlMillis,
            mapOf("query" to query, "year" to year?.toString(), "include_adult" to "false", "page" to "1"),
        )

    private companion object {
        const val HOUR = 60 * 60 * 1000L
    }
}
