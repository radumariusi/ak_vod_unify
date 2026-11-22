package ro.ak.vodunify.ui.calendar

import ro.ak.vodunify.core.calendar.format.badges
import ro.ak.vodunify.core.calendar.format.movieBadges
import ro.ak.vodunify.core.calendar.format.movieKindLabel
import ro.ak.vodunify.core.calendar.format.movieRatingLabel
import ro.ak.vodunify.core.calendar.format.movieTimeLabel
import ro.ak.vodunify.core.calendar.format.movieWhere
import ro.ak.vodunify.core.calendar.format.ratingLabel
import ro.ak.vodunify.core.calendar.format.timeLabel
import ro.ak.vodunify.core.calendar.format.whereToWatch
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import java.time.LocalDate

/** Un rând din calendar, comun pentru episoade și filme, ca ecranul să fie unul singur. */
data class CalendarItem(
    val key: String,
    val tmdbId: Long,
    val isMovie: Boolean,
    val title: String,
    /** „S02E05 · Titlul episodului” sau „Streaming RO”. */
    val subtitle: String,
    /** „≈ 10:00” sau „în cinema”. */
    val time: String,
    val where: String,
    val posterUrl: String?,
    val details: List<String>,
    val date: LocalDate,
    val passed: Boolean,
    val followed: Boolean,
    val watched: Boolean,
    /** Pentru grila pe lună: „Serial S01E02” / „Film (Streaming RO)”. */
    val shortLabel: String,
    val episode: EpisodeEntry? = null,
    val movie: MovieEntry? = null,
    /** Eticheta de jos de pe poster: „S02E05 · 21:00” (ora României) sau ora / tipul la filme. */
    val posterLabel: String = "",
    /** Serviciul tău (id din calendar, ex. „netflix”) pe care îl găsești; null = nu e pe platformele tale. */
    val watchAppId: String? = null,
    /** „US” / „RO” la lansările în cinema (sigla de cinema de pe poster). */
    val cinema: String? = null,
)

fun EpisodeEntry.toItem(watched: Boolean) = CalendarItem(
    key = key,
    tmdbId = showId.toLong(),
    isMovie = false,
    title = showName,
    subtitle = code,
    time = timeLabel(this),
    where = whereToWatch(this),
    posterUrl = posterUrl,
    details = buildList {
        addAll(badges(this@toItem))
        ratingLabel(this@toItem).takeIf { it.isNotBlank() }?.let { add(it) }
        categories.forEach { add(it.label) }
        if (!verdict.passed) add("Filtrat: ${verdict.label}")
    },
    date = releaseDate,
    passed = verdict.passed,
    followed = isFollowed,
    watched = watched,
    shortLabel = "$showName $code",
    episode = this,
    posterLabel = "$code · ${timeLabel(this)}",
    watchAppId = watchOn?.id,
)

fun MovieEntry.toItem(watched: Boolean) = CalendarItem(
    key = key,
    tmdbId = movieId.toLong(),
    isMovie = true,
    title = title,
    subtitle = movieKindLabel(this),
    time = movieTimeLabel(this),
    where = movieWhere(this),
    posterUrl = posterUrl,
    details = buildList {
        addAll(movieBadges(this@toItem))
        movieRatingLabel(this@toItem).takeIf { it.isNotBlank() }?.let { add(it) }
        categories.forEach { add(it.label) }
        if (!verdict.passed) add("Filtrat: ${verdict.movieLabel}")
    },
    date = releaseDate,
    passed = verdict.passed,
    followed = isFollowed,
    watched = watched,
    shortLabel = "$title (${movieKindLabel(this)})",
    movie = this,
    posterLabel = when (kind) {
        MovieReleaseKind.CINEMA_US, MovieReleaseKind.CINEMA_RO -> if (limited) "Limitat" else ""
        MovieReleaseKind.DIGITAL -> "Digital ${region.code}"
        MovieReleaseKind.STREAMING -> movieTimeLabel(this)
    },
    watchAppId = watchOn?.id,
    cinema = when (kind) {
        MovieReleaseKind.CINEMA_US -> "US"
        MovieReleaseKind.CINEMA_RO -> "RO"
        else -> null
    },
)
