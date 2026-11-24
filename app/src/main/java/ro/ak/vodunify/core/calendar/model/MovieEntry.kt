package ro.ak.vodunify.core.calendar.model

import ro.ak.vodunify.core.calendar.quality.QualityVerdict
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZonedDateTime

/** The kinds of movie releases the calendar follows. */
@Serializable
enum class MovieReleaseKind(val label: String, val short: String) {
    /** Premiere on a subscription service (Netflix original, a film arriving on Max, …). */
    STREAMING("Streaming", "Streaming"),
    /** Digital rent/buy (PVOD, iTunes, Google, Prime rent): when it can be watched at home. */
    DIGITAL("Digital (închiriere / cumpărare)", "Digital"),
    CINEMA_US("Cinema SUA", "Cinema US"),
    CINEMA_RO("Cinema România", "Cinema RO"),
}

/** One release of a movie, as worked out from TMDB release dates and providers. */
@Serializable
data class MovieRelease(
    val kind: MovieReleaseKind,
    val region: Region,
    @Serializable(with = CalendarDateSerializer::class) val date: LocalDate,
    /** The streaming service, for [MovieReleaseKind.STREAMING]. */
    val service: StreamingApp? = null,
    /** True when [service] is installed and set to [region] in Settings. */
    val onMyApp: Boolean = false,
    /** Cinema only: a limited release (few cinemas), not a wide one. */
    val limited: Boolean = false,
    /** Cinema RO only: the cinema program that confirms the date ("Cinema City", "Orange Film"); null = TMDb. */
    val source: String? = null,
)

/** One movie release in the calendar, with its moment already in Romania time. */
@Serializable
data class MovieEntry(
    val movieId: Int,
    val title: String,
    val originalTitle: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val tagline: String? = null,
    val kind: MovieReleaseKind,
    val region: Region,
    /** Release moment in Europe/Bucharest; for cinema only the date matters. */
    @Serializable(with = CalendarMomentSerializer::class) val releaseAt: ZonedDateTime,
    /** False for cinema releases: TMDB only knows the day, there's no hour to show. */
    val hasTime: Boolean,
    /** True when the hour is estimated. */
    val timeIsApproximate: Boolean = true,
    /** Streaming service the movie lands on (installed or not). */
    val service: StreamingApp? = null,
    /** The installed app (and its region) where the user can watch it, if any. */
    val watchOn: StreamingApp? = null,
    val watchRegion: Region? = null,
    val limited: Boolean = false,
    /** Cinema RO: the Romanian cinema program that confirmed the date, null when it is TMDb's. */
    val cinemaSource: String? = null,
    val availability: List<ProviderAvailability> = emptyList(),
    val tmdbRating: Double = 0.0,
    val tmdbVotes: Int = 0,
    val imdbId: String? = null,
    val imdb: ImdbRating? = null,
    val categories: Set<ContentFilter> = emptySet(),
    val genreNames: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    /** All known releases (cinema, digital, streaming in RO and US), for the details screen. */
    val releases: List<MovieRelease> = emptyList(),
    val isAnticipated: Boolean = false,
    val isFollowed: Boolean = false,
    val verdict: QualityVerdict = QualityVerdict.PASS_RATING,
    val popularity: Double = 0.0,
    /** Wikidata item of the movie, used to find its id on each streaming service. */
    val wikidataId: String? = null,
) {
    val releaseDate: LocalDate get() = releaseAt.toLocalDate()
    val key: String get() = "movie-$movieId-$kind-${region.code}"
}

/** What the movie calendar shows for a period, plus what was left out and why. */
@Serializable
data class MovieCalendarResult(
    val entries: List<MovieEntry>,
    /** Releases that failed the quality gate, kept so the UI can show them on demand. */
    val rejected: List<MovieEntry> = emptyList(),
    /** Movies dropped because of a hidden category (anime, Indian, TV movies, …). */
    val hiddenByCategory: Int = 0,
    /** Movies the user hid by hand. */
    val hiddenByUser: Int = 0,
    /** Streaming releases dropped because they're not on an installed app ("only my apps"). */
    val notOnMyApps: Int = 0,
    /** True when IMDb ratings were used (an OMDb key is set). */
    val usedImdb: Boolean = false,
    /** Name of the anticipated-list source, for the UI. */
    val anticipatedSource: String = "",
    val complete: Boolean = true,
)
