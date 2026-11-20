package ro.ak.vodunify.ui.library

import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.reconcile.LibraryEntry
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.ui.home.PersonalMapper

/** Filele bibliotecii. */
enum class LibraryTab(val label: String, val status: LibraryStatus?) {
    ALL("Toate", null),
    IN_PROGRESS("În curs", LibraryStatus.IN_PROGRESS),
    UP_NEXT("Următorul episod", LibraryStatus.UP_NEXT),
    WATCHLIST("De văzut", LibraryStatus.WATCHLIST),
    WATCHED("Văzute", LibraryStatus.WATCHED),
}

/** O sursă în filtru: id + nume afișat. */
data class SourceOption(val id: String, val label: String)

data class LibraryFilter(val tab: LibraryTab = LibraryTab.ALL, val source: String? = null)

/** Card din bibliotecă. */
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
                if (r.type == "EPISODE") PersonalMapper.episodeLabel(r.season, r.episode, null)?.let(parts::add)
                parts += PersonalMapper.progressLabel(resume.progress)
            }
            (tab == LibraryTab.UP_NEXT || tab == LibraryTab.ALL) && next != null ->
                PersonalMapper.episodeLabel(next.ref.season, next.ref.episode, null)?.let { parts += "Urmează $it" }
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
