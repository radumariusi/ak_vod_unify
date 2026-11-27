package ro.ak.vodunify.core.reconcile

import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.db.WatchedTitleEntity
import ro.ak.vodunify.core.db.WatchlistEntity
import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import ro.ak.vodunify.core.tracking.LocalProgress
import ro.ak.vodunify.core.tracking.SeriesSummary

/** Rezultatul reconcilierii, gata de afișat. */
data class ReconciledView(
    val resume: List<ReconciledResume>,
    val upNext: List<UpNextEntity>,
    val watchlist: List<WatchlistEntity>,
    val library: List<LibraryEntry>,
    val series: Map<String, SeriesSummary>,
    val latestWatches: List<WatchEventEntity>,
) {
    /** O singură poziție activă per titlu. Reluarea din Acasă folosește doar AK Scrobbler. */
    val localContinueWatching: List<ResumePointEntity>
        get() = library.mapNotNull { entry -> entry.resume?.winner?.takeIf { it.source == LOCAL_SOURCE } }
            .sortedByDescending { it.updatedAt }
}

/** Pur: din tabelele per sursă la vederea reconciliată. */
fun reconcile(
    resume: List<ResumePointEntity>,
    latestWatches: List<WatchEventEntity>,
    watched: List<WatchedTitleEntity>,
    list: List<WatchlistEntity>,
    next: List<UpNextEntity>,
    own: List<OwnWriteEntity>,
): ReconciledView {
    val keys = KeyResolver(resume.map { it.ref } + latestWatches.map { it.ref } + watched.map { it.ref } + list.map { it.ref } + next.map { it.ref })
    val writes = Reconciler.OwnWrites(own)
    val history = latestWatches.filter {
        it.watchedAt > writes.manualCutoff(keys.itemKey(it.ref), keys.titleKey(it.ref), it.ref.season, unwatchedOnly = true)
    }
    val series = LocalProgress.summaries(history) { keys.titleKey(it.ref) }
    val lastWatched = Reconciler.lastWatchedByItem(history, watched, writes, keys)
    val r = Reconciler.resume(resume, lastWatched, writes, keys)
    val reopened = watched.filter { it.source == LOCAL_SOURCE && it.ref.type == "SHOW" }
        .associate { keys.titleKey(it.ref) to it.lastWatchedAt }
    val current = r.filter {
        it.updatedAt > maxOf(series[it.titleKey]?.lastAt ?: Long.MIN_VALUE, reopened[it.titleKey] ?: Long.MIN_VALUE)
    }
    val u = Reconciler.upNext(next, keys)
    val w = Reconciler.watchlist(list, lastWatched, writes, keys)
    val completedMovies = history.filter { it.ref.type == "MOVIE" }
        .map { WatchedTitleEntity(it.id, it.source, it.ref, it.watchedAt) }
    val lib = Reconciler.library(current, u, w, list, watched + completedMovies, resume, next, writes, keys)
    return ReconciledView(r, u, w, lib, series, history)
}
