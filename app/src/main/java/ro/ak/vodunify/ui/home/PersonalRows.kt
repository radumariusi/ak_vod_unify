package ro.ak.vodunify.ui.home

import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.TitleKind
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
)

enum class PersonalState { LOADING, NOT_CONFIGURED, DISCONNECTED, CONNECTED }

data class PersonalRows(
    val state: PersonalState,
    val continueWatching: List<PersonalItem> = emptyList(),
    val upNext: List<PersonalItem> = emptyList(),
    val watchlist: List<PersonalItem> = emptyList(),
) {
    val isEmpty: Boolean get() = continueWatching.isEmpty() && upNext.isEmpty() && watchlist.isEmpty()
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
