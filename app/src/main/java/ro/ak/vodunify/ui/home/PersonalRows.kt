package ro.ak.vodunify.ui.home

import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleReleases
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchlistEntity

/** Card din rândurile personale (Continuă / Următorul episod / De văzut). */
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

    private val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO"))

    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    fun dayLabel(d: LocalDate): String = dayFormat.format(d).replace(".", "")

    /** Filme din Lista mea apărute în ultima lună, cele mai noi întâi. */
    fun newMovies(list: List<FollowedTitle>, today: LocalDate): List<PersonalItem> =
        list.mapNotNull { f -> date(f.releases.releaseDate)?.let { f to it } }
            .filter { (_, d) -> !d.isAfter(today) && !d.isBefore(today.minusDays(NEW_DAYS)) }
            .sortedByDescending { it.second }
            .map { (f, d) -> item(f, "new", dayLabel(d)) }

    /** Filme din Lista mea care apar în următoarele luni, cele mai apropiate întâi. */
    fun soonMovies(list: List<FollowedTitle>, today: LocalDate): List<PersonalItem> =
        list.mapNotNull { f -> date(f.releases.releaseDate)?.let { f to it } }
            .filter { (_, d) -> d.isAfter(today) && !d.isAfter(today.plusDays(SOON_MOVIE_DAYS)) }
            .sortedBy { it.second }
            .map { (f, d) -> item(f, "soon", dayLabel(d)) }

    /** Seriale cu un episod nou (ultima lună) pe care nu l-ai văzut încă. */
    fun newEpisodes(list: List<FollowedTitle>, today: LocalDate): List<PersonalItem> =
        list.mapNotNull { f ->
            val ep = f.releases.lastEpisode ?: return@mapNotNull null
            val d = date(ep.airDate) ?: return@mapNotNull null
            if (d.isAfter(today) || d.isBefore(today.minusDays(NEW_DAYS))) return@mapNotNull null
            val seen = f.lastSeen
            if (seen != null && compareValues(seen.first, ep.season).let { it > 0 || (it == 0 && seen.second >= ep.episode) }) {
                return@mapNotNull null
            }
            Triple(f, ep, d)
        }
            .sortedByDescending { it.third }
            .map { (f, ep, d) -> item(f, "new", "${ep.code} · ${dayLabel(d)}") }

    /** Seriale cu episodul următor în perioada care vine. */
    fun soonEpisodes(list: List<FollowedTitle>, today: LocalDate): List<PersonalItem> =
        list.mapNotNull { f ->
            val ep = f.releases.nextEpisode ?: return@mapNotNull null
            val d = date(ep.airDate) ?: return@mapNotNull null
            if (d.isBefore(today) || d.isAfter(today.plusDays(SOON_EPISODE_DAYS))) return@mapNotNull null
            Triple(f, ep, d)
        }
            .sortedBy { it.third }
            .map { (f, ep, d) -> item(f, "soon", "${ep.code} · ${dayLabel(d)}") }

    private fun item(f: FollowedTitle, prefix: String, label: String): PersonalItem {
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
        )
    }
}

/** Construirea cardurilor din entitățile Room (pur, fără Android). */
object PersonalMapper {
    fun kind(ref: TrackedRef): TitleKind = if (ref.type == "MOVIE") TitleKind.MOVIE else TitleKind.TV

    fun episodeLabel(season: Int?, episode: Int?, title: String?): String? {
        if (season == null || episode == null) return title
        val code = "S%02dE%02d".format(season, episode)
        return if (title.isNullOrBlank()) code else "$code · $title"
    }

    fun progressLabel(progress: Float?): String =
        progress?.let { "${(it * 100).toInt()}% văzut" } ?: "Poziție necunoscută"

    fun resume(e: ResumePointEntity, art: CatalogItem?): PersonalItem {
        val k = kind(e.ref)
        val detail = if (e.ref.type == "EPISODE") episodeLabel(e.ref.season, e.ref.episode, e.ref.episodeTitle) else e.ref.year?.toString()
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
                episodeLabel(e.ref.season, e.ref.episode, e.ref.episodeTitle),
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
