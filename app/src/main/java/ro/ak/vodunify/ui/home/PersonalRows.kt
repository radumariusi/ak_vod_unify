package ro.ak.vodunify.ui.home

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.calendar.format.episodeRange
import ro.ak.vodunify.core.catalog.MovieReleaseEvent
import ro.ak.vodunify.core.catalog.ReleaseChannel
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleOrigin
import ro.ak.vodunify.core.catalog.TitleReleases
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchlistEntity

/** Card din rândurile personale (Continuă / Următorul episod / De văzut). */
@Serializable
data class PersonalItem(
    val key: String,
    val kind: TitleKind,
    val tmdbId: Long?,
    val title: String,
    val subtitle: String?,
    val backdropUrl: String?,
    val posterUrl: String?,
    /** Bara de progres: 0–1; null = poziție necunoscută (nu 0%). */
    val progress: Float?,
    val showsProgress: Boolean,
    /** Descrierea din TMDb, pentru hero-ul din Acasă. */
    val overview: String = "",
    val rating: Double? = null,
    /** La filmele din „În curând” / „Noutăți”: lansarea arătată (cinema RO / US, streaming, digital). */
    val release: MovieReleaseEvent? = null,
    /**
     * Sigla de pe card: la Continuă vizionarea aplicația în care ai văzut ultima dată (acolo continui standard),
     * la o lansare pe streaming aplicația serviciului. Null = sigla obișnuită a titlului.
     */
    val appPackage: String? = null,
    /** Data lansării / episodului (Noutăți, În curând), ISO („2026-11-26”), scrisă complet în hero. */
    val eventDate: String? = null,
    /** Codul episodului („S01E02”) la Noutăți / În curând seriale. */
    val episodeCode: String? = null,
    /** Când ai văzut ultima dată (Continuă vizionarea), epoch ms. */
    val watchedAt: Long? = null,
    val origin: TitleOrigin = TitleOrigin(),
    val genreIds: List<Int> = emptyList(),
) {
    /** Titlul ca element de catalog (pentru meniul rapid de la OK ținut apăsat). */
    fun asCatalogItem(): CatalogItem? = tmdbId?.let {
        CatalogItem(id = it, kind = kind, title = title, year = null, overview = overview, posterUrl = posterUrl, backdropUrl = backdropUrl, rating = rating)
    }
}

enum class PersonalState { LOADING, NOT_CONFIGURED, DISCONNECTED, CONNECTED }

/**
 * Rândurile personale din Acasă, în ordinea lor: Continuă (filme / seriale, din AK Scrobbler),
 * Noutăți din Lista mea (filme apărute în ultima lună / episoade noi nevăzute) și În curând.
 */
@Serializable
data class PersonalRows(
    val state: PersonalState,
    val continueMovies: List<PersonalItem> = emptyList(),
    val continueShows: List<PersonalItem> = emptyList(),
    val newMovies: List<PersonalItem> = emptyList(),
    val newEpisodes: List<PersonalItem> = emptyList(),
    val soonMovies: List<PersonalItem> = emptyList(),
    val soonEpisodes: List<PersonalItem> = emptyList(),
) {
    val continueEmpty: Boolean get() = continueMovies.isEmpty() && continueShows.isEmpty()
}

/** Ce urmărești din Lista mea: titlul cu datele lui și (la seriale) ultimul episod văzut. */
data class FollowedTitle(
    val releases: TitleReleases,
    val lastSeen: Pair<Int, Int>? = null,
)

/** Alegerea titlurilor pentru „Noutăți” și „În curând” (pur, testabil). */
object HomeSections {
    const val NEW_DAYS = 30L
    const val SOON_MOVIE_DAYS = 90L
    const val SOON_EPISODE_DAYS = 60L

    private val dayFormat = DateTimeFormatter.ofPattern("dd.MM")

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun dayLabel(d: LocalDate): String = dayFormat.format(d)

    private val roLocale = Locale("ro", "RO")
    private val fullDayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", roLocale)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", roLocale)

    /** Data completă, cu ziua săptămânii: „joi, 27 noiembrie 2026”. */
    fun fullDayLabel(d: LocalDate): String = fullDayFormat.format(d)

    /** Când ai văzut: „Văzut duminică, 22 noiembrie 2026, 21:30”. */
    fun watchedLabel(epochMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val t = java.time.Instant.ofEpochMilli(epochMs).atZone(zone)
        return "Văzut ${fullDayLabel(t.toLocalDate())}, ${timeFormat.format(t)}"
    }

    /**
     * Lansările unui film care contează în Acasă: cea în cinema (România bate SUA: dacă are dată în RO, nu mai
     * arătăm SUA) și cea „acasă” (pe un serviciu de streaming, cu ale tale întâi; altfel digital, SUA întâi).
     * Un film poate apărea astfel și în „Noutăți” (a ieșit în cinema) și în „În curând” (vine digital).
     */
    fun homeReleases(t: TitleReleases): List<MovieReleaseEvent> {
        val all = t.movieReleases.filter { date(it.date) != null }
        if (all.isEmpty()) {
            return listOfNotNull(t.releaseDate?.takeIf { date(it) != null }?.let { MovieReleaseEvent(ReleaseChannel.UNKNOWN, "", it) })
        }
        val cinema = all.filter { it.channel == ReleaseChannel.CINEMA }.let { c -> c.firstOrNull { it.region == "RO" } ?: c.firstOrNull { it.region == "US" } }
        val streaming = all.filter { it.channel == ReleaseChannel.STREAMING }
            .sortedWith(compareBy({ !it.mine }, { it.region != "US" }, { it.date }))
            .firstOrNull()
        val digital = all.filter { it.channel == ReleaseChannel.DIGITAL }
            .sortedWith(compareBy({ it.region != "US" }, { it.date }))
            .firstOrNull()
        return listOfNotNull(cinema, streaming ?: digital)
    }

    /** Filme apărute în ultima lună (în cinema sau acasă), cele mai noi întâi; fiecare cu ultima lui lansare. */
    fun newMovies(list: List<FollowedTitle>, today: LocalDate, prefix: String = "new"): List<PersonalItem> =
        list.mapNotNull { f ->
            homeReleases(f.releases)
                .mapNotNull { r -> date(r.date)?.let { r to it } }
                .filter { (_, d) -> !d.isAfter(today) && !d.isBefore(today.minusDays(NEW_DAYS)) }
                .maxByOrNull { it.second }
                ?.let { (r, d) -> Triple(f, r, d) }
        }
            .sortedByDescending { it.third }
            .map { (f, r, d) -> item(f, prefix, dayLabel(d), r, d) }

    /** Filme care apar în următoarele luni, cele mai apropiate întâi; fiecare cu următoarea lui lansare. */
    fun soonMovies(list: List<FollowedTitle>, today: LocalDate, prefix: String = "soon"): List<PersonalItem> =
        list.mapNotNull { f ->
            homeReleases(f.releases)
                .mapNotNull { r -> date(r.date)?.let { r to it } }
                .filter { (_, d) -> d.isAfter(today) && !d.isAfter(today.plusDays(SOON_MOVIE_DAYS)) }
                .minByOrNull { it.second }
                ?.let { (r, d) -> Triple(f, r, d) }
        }
            .sortedBy { it.third }
            .map { (f, r, d) -> item(f, prefix, dayLabel(d), r, d) }

    /** Seriale cu un episod nou (ultima lună) pe care nu l-ai văzut încă. */
    fun newEpisodes(list: List<FollowedTitle>, today: LocalDate, days: Long = NEW_DAYS): List<PersonalItem> =
        list.mapNotNull { f ->
            val ep = f.releases.lastEpisode ?: return@mapNotNull null
            val d = date(ep.airDate) ?: return@mapNotNull null
            if (d.isAfter(today) || d.isBefore(today.minusDays(days))) return@mapNotNull null
            val seen = f.lastSeen
            if (seen != null && compareValues(seen.first, ep.season).let { it > 0 || (it == 0 && seen.second >= ep.episode) }) {
                return@mapNotNull null
            }
            Triple(f, ep, d)
        }
            .sortedByDescending { it.third }
            .map { (f, ep, d) -> item(f, "new", "${ep.code} · ${dayLabel(d)}", date = d, code = ep.code) }

    internal fun withEpisodeRange(card: PersonalItem, aired: List<EpisodeAir>, lastSeen: Pair<Int, Int>?): PersonalItem {
        val day = date(card.eventDate) ?: return card
        val episodes = aired.filter { ep ->
            ep.airDate == card.eventDate && (lastSeen == null || ep.season > lastSeen.first ||
                (ep.season == lastSeen.first && ep.episode > lastSeen.second))
        }
        val code = episodeRange(episodes.map { EpisodeNumber(it.season, it.episode) }).ifEmpty { return card }
        return card.copy(episodeCode = code, subtitle = "$code · ${dayLabel(day)}")
    }

    /** Seriale cu episodul următor în perioada care vine. */
    fun soonEpisodes(list: List<FollowedTitle>, today: LocalDate): List<PersonalItem> =
        list.mapNotNull { f ->
            val ep = f.releases.nextEpisode ?: return@mapNotNull null
            val d = date(ep.airDate) ?: return@mapNotNull null
            if (d.isBefore(today) || d.isAfter(today.plusDays(SOON_EPISODE_DAYS))) return@mapNotNull null
            Triple(f, ep, d)
        }
            .sortedBy { it.third }
            .map { (f, ep, d) -> item(f, "soon", "${ep.code} · ${dayLabel(d)}", date = d, code = ep.code) }

    private fun item(
        f: FollowedTitle,
        prefix: String,
        label: String,
        release: MovieReleaseEvent? = null,
        date: LocalDate? = null,
        code: String? = null,
    ): PersonalItem {
        val i = f.releases.item
        return PersonalItem(
            key = "$prefix:${i.mediaKey}",
            kind = i.kind,
            tmdbId = i.id,
            title = i.title,
            subtitle = label,
            backdropUrl = i.backdropUrl,
            posterUrl = i.posterUrl,
            progress = null,
            showsProgress = false,
            overview = i.overview,
            rating = i.rating,
            origin = i.origin,
            genreIds = i.genreIds,
            release = release?.takeIf { it.channel != ReleaseChannel.UNKNOWN },
            eventDate = date?.toString(),
            episodeCode = code,
        )
    }
}

/** Construirea cardurilor din entitățile Room (pur, fără Android). */
object PersonalMapper {
    fun kind(ref: TrackedRef): TitleKind = if (ref.type == "MOVIE") TitleKind.MOVIE else TitleKind.TV

    /** Pe carduri episodul apare doar ca sezon + număr („S01E02”); numele lui e doar în fișa serialului. */
    fun episodeLabel(season: Int?, episode: Int?): String? {
        if (season == null || episode == null) return null
        return "S%02dE%02d".format(season, episode)
    }

    /** Procentul văzut („42%”); null = poziție necunoscută (nu scriem 0%). */
    fun progressLabel(progress: Float?): String? = progress?.let { "${(it * 100).toInt()}%" }

    fun resume(e: ResumePointEntity, art: CatalogItem?): PersonalItem {
        val k = kind(e.ref)
        val detail = if (e.ref.type == "EPISODE") episodeLabel(e.ref.season, e.ref.episode) else null
        return PersonalItem(
            key = "resume:${e.id}",
            kind = k,
            tmdbId = e.ref.tmdbId,
            title = art?.title ?: e.ref.title,
            subtitle = listOfNotNull(detail, progressLabel(e.progress)).joinToString(" · "),
            backdropUrl = art?.backdropUrl,
            posterUrl = art?.posterUrl,
            overview = art?.overview.orEmpty(),
            rating = art?.rating,
            progress = e.progress,
            showsProgress = true,
            appPackage = e.appPackage,
            watchedAt = e.updatedAt.takeIf { it > 0 },
        )
    }

    fun upNext(e: UpNextEntity, art: CatalogItem?): PersonalItem {
        val left = (e.aired - e.completed).coerceAtLeast(0)
        return PersonalItem(
            key = "next:${e.id}",
            kind = TitleKind.TV,
            tmdbId = e.ref.tmdbId,
            title = art?.title ?: e.ref.title,
            subtitle = listOfNotNull(
                episodeLabel(e.ref.season, e.ref.episode),
                if (left > 0) "încă $left" else null,
            ).joinToString(" · "),
            backdropUrl = art?.backdropUrl,
            posterUrl = art?.posterUrl,
            overview = art?.overview.orEmpty(),
            progress = if (e.aired > 0) e.completed.toFloat() / e.aired else null,
            showsProgress = false,
        )
    }

    fun watchlist(e: WatchlistEntity, art: CatalogItem?): PersonalItem {
        val k = kind(e.ref)
        return PersonalItem(
            key = "list:${e.id}",
            kind = k,
            tmdbId = e.ref.tmdbId,
            title = art?.title ?: e.ref.title,
            subtitle = listOfNotNull(e.ref.year?.toString(), k.label).joinToString(" · "),
            backdropUrl = art?.backdropUrl,
            posterUrl = art?.posterUrl,
            overview = art?.overview.orEmpty(),
            progress = null,
            showsProgress = false,
        )
    }
}
