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
)

fun EpisodeEntry.toItem(watched: Boolean) = CalendarItem(
    key = key,
    tmdbId = showId.toLong(),
    isMovie = false,
    title = showName,
    subtitle = listOfNotNull(code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
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
)
