package ro.ak.vodunify.ui.library

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import ro.ak.vodunify.core.tracking.SeriesSummary
import ro.ak.vodunify.core.reconcile.LibraryEntry
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.ui.home.PersonalMapper

/** Filele din Lista mea. */
enum class LibraryTab(val label: String, val status: LibraryStatus?) {
    ALL("Toate", null),
    WATCHLIST("De văzut", LibraryStatus.WATCHLIST),
    IN_PROGRESS("În curs", LibraryStatus.IN_PROGRESS),
    UP_NEXT("Următorul episod", LibraryStatus.UP_NEXT),
    WATCHED("Văzute", LibraryStatus.WATCHED),
    ;

    companion object {
        /** „Următorul episod” vine doar de la Trakt/SIMKL; local, serialele începute sunt „În curs”. */
        val visible: List<LibraryTab>
            get() = entries.filter { it != UP_NEXT || ExternalTrackers.ENABLED }
    }
}

/** O sursă în filtru: id + nume afișat. */
@Serializable
data class SourceOption(val id: String, val label: String)

@Serializable
data class LibraryFilter(val tab: LibraryTab = LibraryTab.ALL, val source: String? = null)

/** Card din bibliotecă. */
@Serializable
data class LibraryItem(
    val key: String,
    val kind: TitleKind,
    val tmdbId: Long?,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    /** 0–1; null = poziție necunoscută sau nu e cazul. */
    val progress: Float?,
    val showsProgress: Boolean,
    val sources: List<String>,
)

/** Logica pură a bibliotecii (testabilă fără Android). */
object LibraryLogic {

    /**
     * Adaugă serialele din istoricul local: „În curs” cât timp mai ai episoade, „Văzute” când le-ai văzut
     * pe toate ([totals]: cheia titlului → numărul de episoade din TMDb; lipsă = necunoscut, deci „În curs”).
     */
    fun mergeSeries(entries: List<LibraryEntry>, series: Map<String, SeriesSummary>, totals: Map<String, Int>): List<LibraryEntry> {
        if (series.isEmpty()) return entries
        val byKey = entries.associateBy { it.titleKey }
        val merged = entries.map { e ->
            val s = series[e.titleKey] ?: return@map e
            e.withSeries(seriesStatus(s, totals[e.titleKey]), s.lastAt)
        }
        val added = series.filterKeys { it !in byKey }.map { (key, s) ->
            val status = seriesStatus(s, totals[key])
            LibraryEntry(
                titleKey = key,
                ref = TrackedRef(key, "SHOW", s.title, null, null, null, null, s.tmdbId, s.imdbId, null, null),
                statuses = setOf(status),
                sources = setOf(LOCAL_SOURCE),
                sourcesByStatus = mapOf(status to setOf(LOCAL_SOURCE)),
                lastActivityAt = s.lastAt,
                resume = null,
                upNext = null,
            )
        }
        return (merged + added).sortedByDescending { it.lastActivityAt }
    }

    private fun seriesStatus(s: SeriesSummary, total: Int?): LibraryStatus =
        if (total != null && total > 0 && s.watched >= total) LibraryStatus.WATCHED else LibraryStatus.IN_PROGRESS

    private fun LibraryEntry.withSeries(status: LibraryStatus, at: Long): LibraryEntry = copy(
        statuses = statuses + status,
        sources = sources + LOCAL_SOURCE,
        sourcesByStatus = sourcesByStatus + (status to (sourcesByStatus[status].orEmpty() + LOCAL_SOURCE)),
        lastActivityAt = maxOf(lastActivityAt, at),
    )

    /** Textul de progres al unui serial: „S2E3 · 12/24 episoade”. */
    fun seriesLabel(s: SeriesSummary, total: Int?): String {
        val last = "S${s.lastSeason}E${s.lastEpisode}"
        return if (total != null && total > 0) "$last · ${minOf(s.watched, total)}/$total episoade" else "$last · ${s.watched} episoade văzute"
    }

    fun filter(entries: List<LibraryEntry>, f: LibraryFilter): List<LibraryEntry> = entries.filter { e ->
        val status = f.tab.status
        when {
            status == null && f.source == null -> true
            status == null -> f.source in e.sources
            f.source == null -> status in e.statuses
            else -> f.source in e.sourcesByStatus[status].orEmpty()
        }
    }

    /** Numărul de titluri pe sursă pentru fila curentă (afișat pe filtre). */
    fun countsBySource(entries: List<LibraryEntry>, tab: LibraryTab): Map<String?, Int> {
        val inTab = filter(entries, LibraryFilter(tab, null))
        val counts = mutableMapOf<String?, Int>(null to inTab.size)
        inTab.forEach { e ->
            val sources = tab.status?.let { e.sourcesByStatus[it].orEmpty() } ?: e.sources
            sources.forEach { s -> counts[s] = (counts[s] ?: 0) + 1 }
        }
        return counts
    }

    fun subtitle(e: LibraryEntry, tab: LibraryTab): String {
        val resume = e.resume
        val next = e.upNext
        val parts = mutableListOf<String>()
        when {
            (tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.ALL) && resume != null -> {
                val r = resume.winner.ref
                if (r.type == "EPISODE") PersonalMapper.episodeLabel(r.season, r.episode)?.let(parts::add)
                PersonalMapper.progressLabel(resume.progress)?.let(parts::add)
            }
            (tab == LibraryTab.UP_NEXT || tab == LibraryTab.ALL) && next != null ->
                PersonalMapper.episodeLabel(next.ref.season, next.ref.episode)?.let { parts += "Urmează $it" }
            tab == LibraryTab.WATCHLIST || (tab == LibraryTab.ALL && LibraryStatus.WATCHLIST in e.statuses && LibraryStatus.WATCHED !in e.statuses) ->
                parts += "De văzut"
            LibraryStatus.WATCHED in e.statuses -> parts += if (e.ref.type == "MOVIE") "Văzut" else "Început"
        }
        if (parts.isEmpty()) e.ref.year?.let { parts += it.toString() }
        return parts.joinToString(" · ")
    }

    fun progress(e: LibraryEntry, tab: LibraryTab): Float? = when {
        (tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.ALL) && e.resume != null -> e.resume.progress
        tab == LibraryTab.UP_NEXT && e.upNext != null && e.upNext.aired > 0 -> e.upNext.completed.toFloat() / e.upNext.aired
        else -> null
    }

    fun showsProgress(e: LibraryEntry, tab: LibraryTab): Boolean =
        (tab == LibraryTab.IN_PROGRESS || tab == LibraryTab.ALL) && e.resume != null
}
