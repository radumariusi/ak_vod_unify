package ro.ak.vodunify.core.calendar.model

import ro.ak.vodunify.core.calendar.quality.QualityVerdict
import java.time.LocalDate
import java.time.ZonedDateTime

/** Where a title can be watched, according to TMDB / JustWatch. */
data class ProviderAvailability(
    val providerId: Int,
    val providerName: String,
    val region: Region,
    val logoUrl: String?,
)

/** IMDb rating as reported by OMDb. */
data class ImdbRating(val rating: Double, val votes: Int)

/** One episode in the calendar, with its release time already in Romania time. */
data class EpisodeEntry(
    val showId: Int,
    val showName: String,
    val posterUrl: String?,
    val stillUrl: String?,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String?,
    val overview: String?,
    /** Release moment in Europe/Bucharest. */
    val releaseAt: ZonedDateTime,
    /** True when the hour is estimated (TMDB only gives a date). */
    val timeIsApproximate: Boolean,
    /** The installed app (and its region) where the user can watch it, if any. */
    val watchOn: StreamingApp?,
    val watchRegion: Region?,
    val availability: List<ProviderAvailability>,
    val tmdbRating: Double,
    val tmdbVotes: Int,
    val imdbId: String?,
    val categories: Set<ContentFilter>,
    val isSeasonPremiere: Boolean,
    val isNewShow: Boolean,
    val showOverview: String? = null,
    val backdropUrl: String? = null,
    val networkNames: List<String> = emptyList(),
    val genreNames: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val isFinale: Boolean = false,
    val imdb: ImdbRating? = null,
    val isAnticipated: Boolean = false,
    val isFollowed: Boolean = false,
    val verdict: QualityVerdict = QualityVerdict.PASS_RATING,
    /** True when the user's app gets the episode later than the original airing (licensed show). */
    val delayedOnApp: Boolean = false,
    /** Wikidata item of the show, used to find its id on each streaming service. */
    val wikidataId: String? = null,
) {
    val code: String get() = "S%02dE%02d".format(seasonNumber, episodeNumber)
    val releaseDate: LocalDate get() = releaseAt.toLocalDate()
    val key: String get() = "$showId-$seasonNumber-$episodeNumber"
}

/**
 * What the calendar shows for a period, plus what was left out and why, so the
 * user can see when thresholds are too strict.
 */
data class CalendarResult(
    val entries: List<EpisodeEntry>,
    /** Episodes that failed the quality gate, kept so the UI can show them on demand. */
    val rejected: List<EpisodeEntry> = emptyList(),
    /** Shows dropped because of a hidden category (anime, Turkish, …). */
    val hiddenByCategory: Int = 0,
    /** Shows the user hid by hand. */
    val hiddenByUser: Int = 0,
    /** Episodes dropped because they're not on an installed app ("only my apps"). */
    val notOnMyApps: Int = 0,
    /** True when IMDb ratings were used (an OMDb key is set). */
    val usedImdb: Boolean = false,
    /** Name of the anticipated-list source, for the UI. */
    val anticipatedSource: String = "",
) {
    fun byDay(): Map<LocalDate, List<EpisodeEntry>> = entries.groupBy { it.releaseDate }
}
