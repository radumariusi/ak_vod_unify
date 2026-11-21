package ro.ak.vodunify.core.calendar.format

import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieRelease
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

val romanian: Locale = Locale.forLanguageTag("ro-RO")
private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", romanian)
private val shortDayFormat = DateTimeFormatter.ofPattern("d MMM", romanian)
private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", romanian)
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", romanian)

fun dayLabel(date: LocalDate): String {
    val today = RomaniaTime.today()
    val prefix = when (date) {
        today -> "Azi · "
        today.plusDays(1) -> "Mâine · "
        today.minusDays(1) -> "Ieri · "
        else -> ""
    }
    return prefix + date.format(dayFormat).replaceFirstChar { it.titlecase(romanian) }
}

fun weekLabel(from: LocalDate, to: LocalDate): String =
    "${from.format(shortDayFormat)} – ${to.format(shortDayFormat)} ${to.year}"

fun timeLabel(entry: EpisodeEntry): String =
    (if (entry.timeIsApproximate) "≈ " else "") + entry.releaseAt.format(timeFormat)

fun whereToWatch(entry: EpisodeEntry): String {
    val app = entry.watchOn
    if (app != null) return "${app.name} (${entry.watchRegion?.code})"
    if (entry.availability.isEmpty()) return "Fără info de streaming"
    val elsewhere = entry.availability.take(3).joinToString(", ") { "${it.providerName} ${it.region.code}" }
    return "Nu ai aplicația · $elsewhere"
}

fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(romanian, n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(romanian, n / 1_000.0)
    else -> n.toString()
}

fun ratingLabel(entry: EpisodeEntry): String = buildList {
    entry.imdb?.let { add("IMDb %.1f (%s)".format(romanian, it.rating, compact(it.votes))) }
    if (entry.tmdbVotes > 0) add("TMDB %.1f (%s)".format(romanian, entry.tmdbRating, compact(entry.tmdbVotes)))
}.joinToString(" · ")

/** Short tags: new show, premiere, finale, anticipated, followed. */
fun badges(entry: EpisodeEntry): List<String> = buildList {
    when {
        entry.isNewShow && entry.isSeasonPremiere -> add("Serial nou")
        entry.isSeasonPremiere -> add("Premieră de sezon")
    }
    if (entry.isFinale) add("Final de sezon")
    if (entry.isAnticipated && entry.isNewShow) add("Mult așteptat")
    if (entry.isFollowed) add("Urmărești")
}

fun monthLabel(date: LocalDate): String = date.format(monthFormat).replaceFirstChar { it.titlecase(romanian) }

private val movieTimeFormat = DateTimeFormatter.ofPattern("HH:mm", romanian)

/** "≈ 10:00" for streaming and digital, "în cinema" for cinema (no hour). */
fun movieTimeLabel(entry: MovieEntry): String = when {
    !entry.hasTime -> if (entry.limited) "în cinema (limitat)" else "în cinema"
    else -> (if (entry.timeIsApproximate) "≈ " else "") + entry.releaseAt.format(movieTimeFormat)
}

/** Short kind tag with its region, e.g. "Streaming RO", "Digital US", "Cinema RO". */
fun movieKindLabel(entry: MovieEntry): String = when (entry.kind) {
    MovieReleaseKind.STREAMING -> "Streaming ${entry.region.code}"
    MovieReleaseKind.DIGITAL -> "Digital ${entry.region.code}"
    MovieReleaseKind.CINEMA_US -> "Cinema US"
    MovieReleaseKind.CINEMA_RO -> "Cinema RO"
}

fun movieWhere(entry: MovieEntry): String = when (entry.kind) {
    MovieReleaseKind.STREAMING -> {
        val app = entry.watchOn
        val service = entry.service
        when {
            app != null -> "${app.name} (${entry.watchRegion?.code})"
            service != null -> "${service.name} ${entry.region.code} · nu ai aplicația"
            else -> "Streaming ${entry.region.code}"
        }
    }
    MovieReleaseKind.DIGITAL -> "Închiriere / cumpărare ${entry.region.code} (Apple TV, Google TV, Prime Video…)"
    MovieReleaseKind.CINEMA_US -> "Cinematografe din SUA"
    MovieReleaseKind.CINEMA_RO -> "Cinematografe din România"
}

fun movieRatingLabel(entry: MovieEntry): String = buildList {
    entry.imdb?.let { add("IMDb %.1f (%s)".format(romanian, it.rating, compact(it.votes))) }
    if (entry.tmdbVotes > 0) add("TMDB %.1f (%s)".format(romanian, entry.tmdbRating, compact(entry.tmdbVotes)))
}.joinToString(" · ")

fun movieBadges(entry: MovieEntry): List<String> = buildList {
    if (entry.isAnticipated) add("Mult așteptat")
    if (entry.isFollowed) add("Urmărești")
    if (entry.limited) add("Lansare limitată")
    entry.cinemaSource?.let { add("Dată confirmată: $it") }
}

fun movieReleaseLine(release: MovieRelease): String {
    val what = when (release.kind) {
        MovieReleaseKind.STREAMING -> {
            val service = release.service?.name ?: "streaming"
            "$service ${release.region.code}" + if (release.onMyApp) " (aplicația ta)" else ""
        }
        MovieReleaseKind.DIGITAL -> "Digital ${release.region.code} (închiriere / cumpărare)"
        MovieReleaseKind.CINEMA_US -> "Cinema SUA" + if (release.limited) " (limitat)" else ""
        MovieReleaseKind.CINEMA_RO -> "Cinema România" + when {
            release.source != null -> " (${release.source})"
            release.limited -> " (limitat)"
            else -> ""
        }
    }
    val year = if (release.date.year != RomaniaTime.today().year) " ${release.date.year}" else ""
    return "$what: " + dayLabel(release.date) + year
}
