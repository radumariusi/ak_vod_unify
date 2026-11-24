package ro.ak.vodunify.ui.calendar

import ro.ak.vodunify.core.calendar.format.badges
import ro.ak.vodunify.core.calendar.format.episodeRange
import ro.ak.vodunify.core.calendar.format.movieBadges
import ro.ak.vodunify.core.calendar.format.movieKindLabel
import ro.ak.vodunify.core.calendar.format.compact
import ro.ak.vodunify.core.calendar.format.movieTimeLabel
import ro.ak.vodunify.core.calendar.format.movieWhere
import ro.ak.vodunify.core.calendar.format.timeLabel
import ro.ak.vodunify.core.calendar.format.whereToWatch
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.connector.EpisodeNumber
import java.time.Instant
import java.time.LocalDate

private fun voteCounts(tmdb: Int, imdb: Int?): String? =
    listOfNotNull(
        tmdb.takeIf { it > 0 }?.let { "${compact(it)} voturi TMDb" },
        imdb?.takeIf { it > 0 }?.let { "${compact(it)} voturi IMDb" },
    ).joinToString(" · ").ifBlank { null }

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
    val releaseAt: Instant,
    /** Pentru grila pe lună: „Serial S01E02” / „Film (Streaming RO)”. */
    val shortLabel: String,
    val episode: EpisodeEntry? = null,
    val movie: MovieEntry? = null,
    /** Eticheta de jos de pe poster: codul episodului sau tipul lansării. */
    val posterLabel: String = "",
    /** Ora României („≈ 04:00”), arătată separat, sus în dreapta posterului, ca să încapă întreagă. */
    val posterTime: String = "",
    /** Serviciul tău (id din calendar, ex. „netflix”) pe care îl găsești; null = nu e pe platformele tale. */
    val watchAppId: String? = null,
    /** „US” / „RO” la lansările în cinema (sigla de cinema de pe poster). */
    val cinema: String? = null,
    /** Imaginea panoramică și descrierea, pentru panoul de sus din vederea pe zi. */
    val backdropUrl: String? = null,
    val overview: String = "",
    val inProgress: Boolean = false,
    val episodeMembers: List<CalendarItem> = emptyList(),
) {
    fun upcomingPosterTime(now: Instant): String =
        if (episodeMembers.isEmpty()) posterTime.takeIf { now.isBefore(releaseAt) }.orEmpty()
        else episodeMembers.filter { now.isBefore(it.releaseAt) }.minByOrNull { it.releaseAt }?.posterTime.orEmpty()
}

/** O singură copertă pe serial și zi. Intrările originale rămân disponibile pentru acțiuni. */
internal fun groupSameDayEpisodes(items: List<CalendarItem>): List<CalendarItem> =
    items.groupBy { item ->
        if (item.episode == null) item.key else "episodes:${item.date}:${item.tmdbId}"
    }.map { (key, entries) ->
        val first = entries.first()
        if (entries.size == 1) return@map first
        val members = entries.sortedWith(compareBy({ it.episode?.seasonNumber }, { it.episode?.episodeNumber }))
        val label = episodeRange(members.mapNotNull { it.episode?.let { ep -> EpisodeNumber(ep.seasonNumber, ep.episodeNumber) } })
        first.copy(
            key = key,
            subtitle = label,
            posterLabel = label,
            shortLabel = "${first.title} $label",
            overview = first.episode?.showOverview?.takeIf { it.isNotBlank() } ?: first.overview,
            episodeMembers = members,
            passed = members.any { it.passed },
            followed = members.any { it.followed },
            watched = members.all { it.watched },
            inProgress = members.any { it.watched || it.inProgress },
            releaseAt = members.minOf { it.releaseAt },
        )
    }

/** Prioritatea în grila lunară se decide pentru titlu, folosind toate lansările sale din zi. */
internal fun monthPreview(items: List<CalendarItem>, now: Instant): List<CalendarItem> =
    items.groupBy { it.isMovie to it.tmdbId }.values
        .sortedWith(compareBy<List<CalendarItem>>(
            { entries -> !entries.any { it.followed && !it.watched && !now.isBefore(it.releaseAt) } },
            { entries -> !entries.any { it.followed } },
            { entries -> entries.minOf { it.releaseAt } },
            { entries -> entries.first().title },
        ))
        .map { entries -> entries.firstOrNull { it.posterUrl != null } ?: entries.first() }

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
        voteCounts(tmdbVotes, imdb?.votes)?.let(::add)
        categories.forEach { add(it.label) }
        if (!verdict.passed) add("Filtrat: ${verdict.label}")
    },
    date = releaseDate,
    passed = verdict.passed,
    followed = isFollowed,
    watched = watched,
    releaseAt = releaseAt.toInstant(),
    shortLabel = "$showName $code",
    episode = this,
    posterLabel = code,
    posterTime = timeLabel(this),
    watchAppId = watchOn?.id,
    backdropUrl = backdropUrl,
    overview = overview?.takeIf { it.isNotBlank() } ?: showOverview.orEmpty(),
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
        voteCounts(tmdbVotes, imdb?.votes)?.let(::add)
        categories.forEach { add(it.label) }
        if (!verdict.passed) add("Filtrat: ${verdict.movieLabel}")
    },
    date = releaseDate,
    passed = verdict.passed,
    followed = isFollowed,
    watched = watched,
    releaseAt = releaseAt.toInstant(),
    shortLabel = "$title (${movieKindLabel(this)})",
    movie = this,
    posterLabel = when (kind) {
        MovieReleaseKind.CINEMA_US, MovieReleaseKind.CINEMA_RO -> if (limited) "Limitat" else ""
        MovieReleaseKind.DIGITAL -> "Digital ${region.code}"
        MovieReleaseKind.STREAMING -> ""
    },
    posterTime = if (hasTime) movieTimeLabel(this) else "",
    watchAppId = watchOn?.id,
    cinema = when (kind) {
        MovieReleaseKind.CINEMA_US -> "US"
        MovieReleaseKind.CINEMA_RO -> "RO"
        else -> null
    },
    backdropUrl = backdropUrl,
    overview = overview.orEmpty(),
)
