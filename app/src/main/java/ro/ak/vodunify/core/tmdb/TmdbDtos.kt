package ro.ak.vodunify.core.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DTO-uri TMDb (API v3). Un singur tip pentru rezultate de listă, pentru că
 * TMDb folosește aceleași câmpuri pentru filme (title) și seriale (name).
 */

@Serializable
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("total_results") val totalResults: Int = 0,
)

@Serializable
data class TmdbListItem(
    val id: Long,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val popularity: Double? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    val character: String? = null,
    val job: String? = null,
    val adult: Boolean = false,
    @SerialName("origin_country") val originCountry: List<String> = emptyList(),
)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbGenreList(val genres: List<TmdbGenre> = emptyList())

@Serializable
data class TmdbCompany(
    val id: Long,
    val name: String,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList(),
    val crew: List<TmdbCrew> = emptyList(),
)

@Serializable
data class TmdbCast(
    val id: Long,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val order: Int = 999,
)

@Serializable
data class TmdbCrew(
    val id: Long,
    val name: String,
    val job: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
data class TmdbProvider(
    @SerialName("provider_id") val providerId: Int,
    @SerialName("provider_name") val providerName: String,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("display_priority") val displayPriority: Int = 999,
    @SerialName("display_priorities") val displayPriorities: Map<String, Int> = emptyMap(),
)

@Serializable
data class TmdbProviderList(val results: List<TmdbProvider> = emptyList())

@Serializable
data class TmdbRegionProviders(
    val link: String? = null,
    val flatrate: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList(),
    val ads: List<TmdbProvider> = emptyList(),
    val rent: List<TmdbProvider> = emptyList(),
    val buy: List<TmdbProvider> = emptyList(),
)

@Serializable
data class TmdbWatchProviders(val results: Map<String, TmdbRegionProviders> = emptyMap())

@Serializable
data class TmdbExternalIds(
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("tvdb_id") val tvdbId: Long? = null,
)

@Serializable
data class TmdbVideo(
    val key: String,
    val site: String,
    val type: String,
    val official: Boolean = false,
)

@Serializable
data class TmdbVideoList(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
data class TmdbImageList(val logos: List<TmdbImage> = emptyList())

@Serializable
data class TmdbSeasonSummary(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
)

@Serializable
data class TmdbEpisodeRef(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String? = null,
    @SerialName("air_date") val airDate: String? = null,
)

/** Detalii film sau serial (append_to_response=credits,watch/providers,recommendations,external_ids,videos). */
@Serializable
data class TmdbTitleDetails(
    val id: Long,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    @SerialName("number_of_episodes") val numberOfEpisodes: Int? = null,
    val status: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    val networks: List<TmdbCompany> = emptyList(),
    val seasons: List<TmdbSeasonSummary> = emptyList(),
    @SerialName("next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeRef? = null,
    @SerialName("last_episode_to_air") val lastEpisodeToAir: TmdbEpisodeRef? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    @SerialName("external_ids") val externalIds: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    @SerialName("aggregate_credits") val aggregateCredits: TmdbCredits? = null,
    @SerialName("watch/providers") val watchProviders: TmdbWatchProviders? = null,
    val recommendations: TmdbPage<TmdbListItem>? = null,
    val videos: TmdbVideoList? = null,
    val images: TmdbImageList? = null,
)

@Serializable
data class TmdbEpisode(
    val id: Long,
    @SerialName("episode_number") val episodeNumber: Int,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("still_path") val stillPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
)

@Serializable
data class TmdbSeasonDetails(
    val id: Long? = null,
    @SerialName("season_number") val seasonNumber: Int,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("air_date") val airDate: String? = null,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@Serializable
data class TmdbCombinedCredits(
    val cast: List<TmdbListItem> = emptyList(),
    val crew: List<TmdbListItem> = emptyList(),
)

@Serializable
data class TmdbPersonDetails(
    val id: Long,
    val name: String,
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("combined_credits") val combinedCredits: TmdbCombinedCredits? = null,
)

@Serializable
data class TmdbStudioDetails(
    val id: Long,
    val name: String,
    val description: String? = null,
    val headquarters: String? = null,
    val homepage: String? = null,
    @SerialName("logo_path") val logoPath: String? = null,
    @SerialName("origin_country") val originCountry: String? = null,
)
