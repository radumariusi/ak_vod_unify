package ro.ak.vodunify.core.reconcile

import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.db.WatchedTitleEntity
import ro.ak.vodunify.core.db.WatchlistEntity

/** Rezultatul reconcilierii, gata de afișat. */
data class ReconciledView(
    val resume: List<ReconciledResume>,
    val upNext: List<UpNextEntity>,
    val watchlist: List<WatchlistEntity>,
    val library: List<LibraryEntry>,
)

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
    val lastWatched = Reconciler.lastWatchedByItem(latestWatches, watched, writes, keys)
    val r = Reconciler.resume(resume, lastWatched, writes, keys)
    val u = Reconciler.upNext(next, keys)
    val w = Reconciler.watchlist(list, lastWatched, writes, keys)
    val lib = Reconciler.library(r, u, w, list, watched, resume, next, writes, keys)
    return ReconciledView(r, u, w, lib)
}
