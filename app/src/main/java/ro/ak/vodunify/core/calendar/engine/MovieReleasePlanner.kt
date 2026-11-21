package ro.ak.vodunify.core.calendar.engine

import ro.ak.vodunify.core.calendar.cinema.RoCinemaRelease
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.MovieRelease
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingApp
import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.calendar.tmdb.MovieDetailsDto
import ro.ak.vodunify.core.calendar.tmdb.ReleaseDateDto
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/**
 * Works out a movie's releases (cinema, digital, streaming; RO and US) from the
 * TMDB release dates and watch providers, for the user's apps.
 *
 * TMDB release types: 2 limited cinema, 3 cinema, 4 digital. A digital release
 * counts as a streaming premiere when:
 * - its note names a streaming service ("Netflix", "Disney+", "Max", …), or
 * - the movie had no wide cinema release (in that country or the US) and a
 *   catalog service already streams it there (a streaming original).
 *
 * The date follows the region the user set for the app (RO or US). Services that
 * release worldwide at once ([StreamingApp.globalDrop]: Netflix, Disney+, …) also
 * count on an app set to RO when TMDB only lists the premiere in another country.
 */
object MovieReleasePlanner {

    private const val LIMITED = 2
    private const val THEATRICAL = 3
    private const val DIGITAL = 4

    /**
     * [roCinema]: the film in a Romanian cinema program (Cinema City, Orange Film). Its
     * premiere wins over TMDb's Romanian cinema date, which is often missing or out of date.
     */
    fun releases(details: MovieDetailsDto, settings: CalendarSettings, roCinema: RoCinemaRelease? = null): List<MovieRelease> {
        val byCountry: Map<String, List<ReleaseDateDto>> = details.releaseDates?.results.orEmpty()
            .groupBy({ it.country.uppercase() }, { it.releaseDates })
            .mapValues { (_, lists) -> lists.flatten() }
        fun entries(region: Region) = byCountry[region.code].orEmpty()
        val installed = settings.installedApps()
        val providers = details.watchProviders?.results.orEmpty()
        val out = mutableListOf<MovieRelease>()

        // Cinema: the wide release, or the limited one when there is no wide one.
        for (region in Region.entries) {
            val kind = if (region == Region.US) MovieReleaseKind.CINEMA_US else MovieReleaseKind.CINEMA_RO
            val wide = entries(region).earliest(THEATRICAL)
            val limited = entries(region).earliest(LIMITED)
            when {
                region == Region.RO && roCinema != null ->
                    out += MovieRelease(kind, region, roCinema.date, source = roCinema.source)
                wide != null -> out += MovieRelease(kind, region, wide)
                limited != null -> out += MovieRelease(kind, region, limited, limited = true)
            }
        }

        val usWideCinema = entries(Region.US).any { it.type == THEATRICAL }
        for (region in Region.entries) {
            val digital = entries(region).filter { it.type == DIGITAL && it.parsedDate() != null }
            if (digital.isEmpty()) continue
            val byNote = StreamingCatalog.all.filter { app -> digital.any { app.matchesNote(it.note) } }
            val streamingFirst = !usWideCinema && entries(region).none { it.type == THEATRICAL }
            val byProvider = if (streamingFirst) {
                StreamingCatalog.all.filter { app ->
                    providers[region.code]?.streaming.orEmpty().any { it.providerId in app.tmdbProviderIds }
                }
            } else {
                emptyList()
            }
            val services = (byNote + byProvider).distinct()
            val plainDigital = digital.filter { d -> services.none { it.matchesNote(d.note) } }.earliestDate()

            if (services.isEmpty()) {
                plainDigital?.let { out += MovieRelease(MovieReleaseKind.DIGITAL, region, it) }
                continue
            }
            val mine = installed.firstOrNull { (app, r) -> r == region && app in services }?.first
            val service = mine ?: byNote.firstOrNull() ?: services.first()
            val date = digital.filter { service.matchesNote(it.note) }.earliestDate() ?: digital.earliestDate()!!
            out += MovieRelease(MovieReleaseKind.STREAMING, region, date, service, onMyApp = mine != null)
            // A rent/buy release with no service in the note, before the streaming one.
            if (plainDigital != null && plainDigital.isBefore(date) && byNote.isNotEmpty()) {
                out += MovieRelease(MovieReleaseKind.DIGITAL, region, plainDigital)
            }
        }

        // Worldwide drops (Netflix, Disney+, …) listed only in another country.
        val allDigital = byCountry.flatMap { (country, list) -> list.filter { it.type == DIGITAL }.map { country to it } }
        fun globalDate(app: StreamingApp): LocalDate? {
            val matching = allDigital.filter { (_, d) -> app.matchesNote(d.note) }
            return matching.filter { it.first == Region.US.code }.map { it.second }.earliestDate()
                ?: matching.map { it.second }.earliestDate()
        }
        for ((app, region) in installed) {
            if (!app.globalDrop) continue
            // TMDB already has a streaming premiere in this region (maybe on another service).
            if (out.any { it.kind == MovieReleaseKind.STREAMING && it.region == region }) continue
            val date = (out.firstOrNull { it.kind == MovieReleaseKind.STREAMING && it.service == app }?.date)
                ?: globalDate(app)
                ?: continue
            out += MovieRelease(MovieReleaseKind.STREAMING, region, date, app, onMyApp = true)
        }
        if (out.none { it.kind == MovieReleaseKind.STREAMING }) {
            StreamingCatalog.all.filter { it.globalDrop }.firstNotNullOfOrNull { app -> globalDate(app)?.let { app to it } }
                ?.let { (app, date) -> out += MovieRelease(MovieReleaseKind.STREAMING, Region.US, date, app) }
        }

        return out
            .distinctBy { it.kind to it.region }
            .sortedWith(compareBy({ it.date }, { it.kind.ordinal }, { it.region.ordinal }))
    }

    /**
     * The releases that go into the calendar: each cinema release, the US digital
     * release (the RO one only when there's no US one) and one streaming release,
     * preferring the user's own apps.
     */
    fun calendarReleases(all: List<MovieRelease>, settings: CalendarSettings): List<MovieRelease> {
        val out = mutableListOf<MovieRelease>()
        all.filter { it.kind == MovieReleaseKind.CINEMA_US || it.kind == MovieReleaseKind.CINEMA_RO }.forEach { out += it }
        val digital = all.filter { it.kind == MovieReleaseKind.DIGITAL }
        (digital.firstOrNull { it.region == Region.US } ?: digital.firstOrNull())?.let { out += it }
        val streaming = all.filter { it.kind == MovieReleaseKind.STREAMING }
        val installedOrder = settings.installedApps()
        val mine = streaming.filter { it.onMyApp }
            .minByOrNull { r -> installedOrder.indexOfFirst { it.first == r.service && it.second == r.region } }
        (mine ?: streaming.firstOrNull { it.region == Region.US } ?: streaming.firstOrNull())?.let { out += it }
        return out.filter { it.kind in settings.movieKinds }
    }

    /** Release moment in Romania time and whether it has an hour at all. */
    fun moment(release: MovieRelease): Pair<ZonedDateTime, Boolean> = when (release.kind) {
        MovieReleaseKind.CINEMA_US, MovieReleaseKind.CINEMA_RO ->
            ZonedDateTime.of(release.date, LocalTime.MIDNIGHT, RomaniaTime.ZONE) to false
        // Rent/buy stores open digital releases at midnight Eastern in the US.
        MovieReleaseKind.DIGITAL -> local(release.date, zoneOf(release.region)) to true
        MovieReleaseKind.STREAMING -> {
            val zone = if (release.service?.globalDrop == true) PACIFIC else zoneOf(release.region)
            local(release.date, zone) to true
        }
    }

    private fun local(date: LocalDate, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.of(date, LocalTime.MIDNIGHT, zone).withZoneSameInstant(RomaniaTime.ZONE)

    private fun zoneOf(region: Region): ZoneId = if (region == Region.US) EASTERN else RomaniaTime.ZONE

    private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
    private val EASTERN: ZoneId = ZoneId.of("America/New_York")

    private fun List<ReleaseDateDto>.earliest(type: Int): LocalDate? = filter { it.type == type }.earliestDate()

    private fun List<ReleaseDateDto>.earliestDate(): LocalDate? = mapNotNull { it.parsedDate() }.minOrNull()

    /** TMDB gives "2026-11-21T00:00:00.000Z"; only the day is meaningful. */
    internal fun ReleaseDateDto.parsedDate(): LocalDate? {
        val raw = releaseDate?.takeIf { it.length >= 10 } ?: return null
        return try {
            LocalDate.parse(raw.substring(0, 10))
        } catch (e: DateTimeParseException) {
            null
        }
    }
}
