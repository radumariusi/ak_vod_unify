package ro.ak.vodunify.core.reconcile

import ro.ak.vodunify.core.db.OwnWriteAction
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.db.WatchedTitleEntity
import ro.ak.vodunify.core.db.WatchlistEntity
import java.util.concurrent.TimeUnit

/** O dovadă dintr-o sursă pentru un eveniment logic. */
data class Evidence(
    val source: String,
    val at: Long,
    /** Progres 0–1, sau null când sursa nu știe poziția (NU înseamnă 0%). */
    val progress: Float? = null,
    /** True dacă e ecoul unei scrieri făcute chiar de aplicație. */
    val echo: Boolean = false,
)

/** Un punct de reluare reconciliat: un singur element, oricâte surse îl raportează. */
data class ReconciledResume(
    val itemKey: String,
    val titleKey: String,
    /** Dovada câștigătoare (cea mai recentă), cu referința ei completă. */
    val winner: ResumePointEntity,
    val evidences: List<Evidence>,
) {
    val progress: Float? get() = winner.progress
    val updatedAt: Long get() = winner.updatedAt
}

/** O vizionare logică: aceeași vizionare raportată de mai multe surse. */
data class LogicalWatch(
    val itemKey: String,
    val titleKey: String,
    val ref: TrackedRef,
    /** Momentul vizionării: prima dovadă care nu e ecou (sau prima, dacă toate sunt ecouri). */
    val watchedAt: Long,
    val evidences: List<Evidence>,
) {
    val sources: Set<String> get() = evidences.map { it.source }.toSet()

    /** Toate dovezile sunt ecouri: vizionarea a fost marcată din aplicație. */
    val fromApp: Boolean get() = evidences.all { it.echo }
}

/** Starea unui titlu în bibliotecă, după reconciliere. */
enum class LibraryStatus { IN_PROGRESS, UP_NEXT, WATCHED, WATCHLIST }

data class LibraryEntry(
    val titleKey: String,
    /** Referința la titlu (film / serial), cea mai completă dintre surse. */
    val ref: TrackedRef,
    val statuses: Set<LibraryStatus>,
    /** Sursele care știu de titlu (oricare stare). */
    val sources: Set<String>,
    /** Sursele pe stare, pentru filtrare: ce sursă spune că e în curs, văzut etc. */
    val sourcesByStatus: Map<LibraryStatus, Set<String>>,
    val lastActivityAt: Long,
    /** Punctul de reluare câștigător, dacă există. */
    val resume: ReconciledResume?,
    /** „Următorul episod” câștigător, dacă există. */
    val upNext: UpNextEntity?,
)

/**
 * Reguli de reconciliere între Trakt, SIMKL și WeTrakr (pure, fără Android):
 *
 * 1. **Un eveniment logic, mai multe dovezi.** Aceeași vizionare raportată de mai multe
 *    surse (la câteva minute distanță) e un singur [LogicalWatch].
 * 2. **Câștigă cel mai recent eveniment valid**, nu procentul cel mai mare: dacă ai dat
 *    înapoi la 20% pe SIMKL după 70% pe Trakt, rămâne 20%.
 * 3. **Un import vechi venit târziu nu suprascrie unul nou**: o vizionare terminată după
 *    pauză închide punctul de reluare; o acțiune din aplicație (scos din listă, marcat
 *    nevăzut) ascunde dovezile mai vechi decât ea, chiar dacă sursa le trimite din nou.
 * 4. **Ecourile scrierilor proprii** (ce a trimis aplicația și a revenit prin sincronizare)
 *    sunt marcate și nu creează evenimente noi.
 * 5. **Lipsă ≠ 0%**: un progres necunoscut rămâne null și nu e tratat ca „neînceput”.
 */
object Reconciler {
    /** Dovezile aceleiași vizionări din surse diferite se încadrează în această fereastră. */
    val SAME_WATCH_WINDOW_MS: Long = TimeUnit.HOURS.toMillis(4)

    /** Cât de departe de scrierea proprie poate fi dovada ca să o considerăm ecou. */
    val ECHO_WINDOW_MS: Long = TimeUnit.MINUTES.toMillis(15)

    /** Peste acest progres considerăm elementul terminat. */
    const val FINISHED_PROGRESS = 0.95f

    private const val EPISODE = "EPISODE"
    private const val MOVIE = "MOVIE"

    /** Indexul scrierilor proprii, pe sursă + element + acțiune. */
    class OwnWrites(writes: List<OwnWriteEntity>) {
        private val byKey = writes.groupBy { Triple(it.source, it.itemKey, it.action) }
        private val latestByItemAction = writes.groupBy { it.itemKey to it.action }.mapValues { (_, v) -> v.maxOf { it.at } }

        fun isEcho(source: String, itemKey: String, action: OwnWriteAction, at: Long, progress: Float? = null): Boolean =
            byKey[Triple(source, itemKey, action.name)].orEmpty().any { w ->
                kotlin.math.abs(w.at - at) <= ECHO_WINDOW_MS &&
                    (progress == null || w.progress == null || kotlin.math.abs(w.progress - progress) <= 0.02f)
            }

        /** Ultima dată când aplicația a făcut [action] pe element (în orice sursă). */
        fun latest(itemKey: String, action: OwnWriteAction): Long? = latestByItemAction[itemKey to action.name]
    }

    // ---------- Istoric ----------

    fun watches(events: List<WatchEventEntity>, writes: OwnWrites, keys: KeyResolver): List<LogicalWatch> {
        val out = mutableListOf<LogicalWatch>()
        events.groupBy { keys.itemKey(it.ref) }.forEach { (itemKey, group) ->
            val unwatchedAt = writes.latest(itemKey, OwnWriteAction.UNWATCHED)
            // Regula 3: ce e mai vechi decât „marcat nevăzut” din aplicație nu mai contează.
            val valid = group.filter { unwatchedAt == null || it.watchedAt > unwatchedAt }.sortedBy { it.watchedAt }
            var cluster = mutableListOf<WatchEventEntity>()
            fun flush() {
                if (cluster.isEmpty()) return
                // O sursă contează o singură dată pe vizionare (copia locală + cea din sursă = o dovadă).
                val evidences = cluster.map { e ->
                    Evidence(e.source, e.watchedAt, echo = writes.isEcho(e.source, itemKey, OwnWriteAction.WATCHED, e.watchedAt))
                }.distinctBy { it.source }
                val first = cluster.firstOrNull { e ->
                    !writes.isEcho(e.source, itemKey, OwnWriteAction.WATCHED, e.watchedAt)
                } ?: cluster.first()
                out += LogicalWatch(itemKey, keys.titleKey(first.ref), richest(cluster.map { it.ref }), first.watchedAt, evidences)
                cluster = mutableListOf()
            }
            for (e in valid) {
                val start = cluster.firstOrNull()?.watchedAt
                // Aceeași sursă de două ori = două vizionări (ai revăzut); altă sursă în fereastră = aceeași vizionare.
                val sameSourceAgain = cluster.any { it.source == e.source && e.watchedAt - it.watchedAt > ECHO_WINDOW_MS }
                if (start != null && (e.watchedAt - start > SAME_WATCH_WINDOW_MS || sameSourceAgain)) flush()
                cluster += e
            }
            flush()
        }
        return out.sortedByDescending { it.watchedAt }
    }

    // ---------- Puncte de reluare ----------

    /**
     * Un punct de reluare pe element: cel mai recent (regula 2). Dispare dacă elementul a fost
     * văzut complet după ultima pauză, în orice sursă (regula 3).
     *
     * [lastWatched] = momentul ultimei vizionări complete, pe cheia canonică a elementului.
     */
    fun resume(
        points: List<ResumePointEntity>,
        lastWatched: Map<String, Long>,
        writes: OwnWrites,
        keys: KeyResolver,
    ): List<ReconciledResume> =
        points.groupBy { keys.itemKey(it.ref) }.mapNotNull { (itemKey, group) ->
            val evidences = group.map { p ->
                Evidence(p.source, p.updatedAt, p.progress, writes.isEcho(p.source, itemKey, OwnWriteAction.RESUME, p.updatedAt, p.progress))
            }
            // La egalitate: preferăm poziția cunoscută, apoi dovada care nu e ecou.
            val winner = group.maxWith(
                compareBy<ResumePointEntity> { it.updatedAt }
                    .thenBy { if (it.progress != null) 1 else 0 }
                    .thenBy { if (writes.isEcho(it.source, itemKey, OwnWriteAction.RESUME, it.updatedAt, it.progress)) 0 else 1 },
            )
            val finishedAt = lastWatched[itemKey]
            when {
                finishedAt != null && finishedAt >= winner.updatedAt -> null
                winner.progress != null && winner.progress >= FINISHED_PROGRESS -> null
                else -> ReconciledResume(itemKey, keys.titleKey(winner.ref), winner, evidences.sortedByDescending { it.at })
            }
        }.sortedByDescending { it.updatedAt }

    /** Ultima vizionare completă pe element (filme din istoric + filme din titlurile văzute). */
    fun lastWatchedByItem(
        latestEvents: List<WatchEventEntity>,
        watchedTitles: List<WatchedTitleEntity>,
        writes: OwnWrites,
        keys: KeyResolver,
    ): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun put(key: String, at: Long) {
            val unwatched = writes.latest(key, OwnWriteAction.UNWATCHED)
            if (unwatched != null && at <= unwatched) return
            if (at > (out[key] ?: Long.MIN_VALUE)) out[key] = at
        }
        latestEvents.forEach { put(keys.itemKey(it.ref), it.watchedAt) }
        // Pentru seriale, „văzut” la nivel de titlu înseamnă doar început: nu închide episoade.
        watchedTitles.filter { it.ref.type == MOVIE }.forEach { put(keys.titleKey(it.ref), it.lastWatchedAt) }
        return out
    }

    // ---------- Următorul episod ----------

    /** Câte un rând pe serial: sursa cu cea mai recentă vizionare câștigă. */
    fun upNext(items: List<UpNextEntity>, keys: KeyResolver): List<UpNextEntity> =
        items.groupBy { keys.titleKey(it.ref) }
            .map { (_, group) -> group.maxWith(compareBy<UpNextEntity> { it.lastWatchedAt }.thenBy { it.completed }) }
            .sortedByDescending { it.lastWatchedAt }

    // ---------- De văzut ----------

    /**
     * Câte un rând pe titlu. Un titlu scos din listă din aplicație nu revine dintr-un import
     * mai vechi; un film văzut după ce a fost pus pe listă nu mai e „de văzut”.
     */
    fun watchlist(
        items: List<WatchlistEntity>,
        lastWatched: Map<String, Long>,
        writes: OwnWrites,
        keys: KeyResolver,
    ): List<WatchlistEntity> =
        items.groupBy { keys.titleKey(it.ref) }.mapNotNull { (titleKey, group) ->
            val removedAt = writes.latest(titleKey, OwnWriteAction.WATCHLIST_REMOVE)
            val valid = group.filter { removedAt == null || it.listedAt > removedAt }
            val newest = valid.maxByOrNull { it.listedAt } ?: return@mapNotNull null
            val watchedAt = if (newest.ref.type == MOVIE) lastWatched[titleKey] else null
            if (watchedAt != null && watchedAt >= newest.listedAt) null else newest
        }.sortedByDescending { it.listedAt }

    // ---------- Biblioteca ----------

    fun library(
        resume: List<ReconciledResume>,
        upNext: List<UpNextEntity>,
        watchlist: List<WatchlistEntity>,
        allWatchlist: List<WatchlistEntity>,
        watchedTitles: List<WatchedTitleEntity>,
        allResume: List<ResumePointEntity>,
        allUpNext: List<UpNextEntity>,
        writes: OwnWrites,
        keys: KeyResolver,
    ): List<LibraryEntry> {
        class Acc(val key: String) {
            val refs = mutableListOf<TrackedRef>()
            val statuses = mutableSetOf<LibraryStatus>()
            val bySource = mutableMapOf<LibraryStatus, MutableSet<String>>()
            var last = 0L
            var resume: ReconciledResume? = null
            var upNext: UpNextEntity? = null
            fun add(status: LibraryStatus, sources: Collection<String>, ref: TrackedRef, at: Long) {
                statuses += status
                bySource.getOrPut(status) { mutableSetOf() } += sources
                refs += ref
                if (at > last) last = at
            }
        }
        val acc = LinkedHashMap<String, Acc>()
        fun entry(key: String) = acc.getOrPut(key) { Acc(key) }

        // Sursele pe stare: toate sursele care raportează starea (nu doar câștigătoarea).
        val resumeSources = allResume.groupBy { keys.titleKey(it.ref) }.mapValues { (_, v) -> v.map { it.source }.toSet() }
        val upNextSources = allUpNext.groupBy { keys.titleKey(it.ref) }.mapValues { (_, v) -> v.map { it.source }.toSet() }
        val listSources = allWatchlist.groupBy { keys.titleKey(it.ref) }.mapValues { (_, v) -> v.map { it.source }.toSet() }

        // Pe titlu, păstrăm cel mai recent punct de reluare (poate fi un episod).
        resume.groupBy { it.titleKey }.forEach { (key, items) ->
            val best = items.maxBy { it.updatedAt }
            entry(key).apply {
                add(LibraryStatus.IN_PROGRESS, resumeSources[key] ?: setOf(best.winner.source), titleRef(best.winner.ref), best.updatedAt)
                this.resume = best
            }
        }
        upNext.forEach { u ->
            val key = keys.titleKey(u.ref)
            entry(key).apply {
                add(LibraryStatus.UP_NEXT, upNextSources[key] ?: setOf(u.source), titleRef(u.ref), u.lastWatchedAt)
                this.upNext = u
            }
        }
        watchlist.forEach { w ->
            val key = keys.titleKey(w.ref)
            entry(key).add(LibraryStatus.WATCHLIST, listSources[key] ?: setOf(w.source), w.ref, w.listedAt)
        }
        watchedTitles.groupBy { keys.titleKey(it.ref) }.forEach { (key, group) ->
            val unwatched = writes.latest(key, OwnWriteAction.UNWATCHED)
            val valid = group.filter { unwatched == null || it.lastWatchedAt > unwatched }
            if (valid.isEmpty()) return@forEach
            val newest = valid.maxBy { it.lastWatchedAt }
            entry(key).apply {
                add(LibraryStatus.WATCHED, valid.map { it.source }.toSet(), newest.ref, newest.lastWatchedAt)
                refs += valid.map { it.ref }
            }
        }

        return acc.values.map { a ->
            LibraryEntry(
                titleKey = a.key,
                ref = richest(a.refs),
                statuses = a.statuses.toSet(),
                sources = a.bySource.values.flatten().toSet(),
                sourcesByStatus = a.bySource.mapValues { it.value.toSet() },
                lastActivityAt = a.last,
                resume = a.resume,
                upNext = a.upNext,
            )
        }.sortedByDescending { it.lastActivityAt }
    }

    /** Referința la titlu (fără sezon/episod) pentru un episod. */
    fun titleRef(ref: TrackedRef): TrackedRef =
        if (ref.type == EPISODE) ref.copy(type = "SHOW", season = null, episode = null, episodeTitle = null) else ref

    /** Referința cu cele mai multe id-uri; id-urile lipsă se completează din celelalte. */
    fun richest(refs: List<TrackedRef>): TrackedRef {
        val base = refs.maxBy { r -> KeyResolver.tokens(r).size * 10 + (if (r.title.isNotBlank()) 1 else 0) }
        return base.copy(
            tmdbId = base.tmdbId ?: refs.firstNotNullOfOrNull { it.tmdbId },
            imdbId = base.imdbId ?: refs.firstNotNullOfOrNull { it.imdbId },
            traktId = base.traktId ?: refs.firstNotNullOfOrNull { it.traktId },
            tvdbId = base.tvdbId ?: refs.firstNotNullOfOrNull { it.tvdbId },
            simklId = base.simklId ?: refs.firstNotNullOfOrNull { it.simklId },
            title = base.title.ifBlank { refs.firstOrNull { it.title.isNotBlank() }?.title ?: base.title },
            year = base.year ?: refs.firstNotNullOfOrNull { it.year },
        )
    }
}
