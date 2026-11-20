package ro.ak.vodunify.core.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.ak.vodunify.core.connector.Capability
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.connector.ConnectorKind
import ro.ak.vodunify.core.connector.ConnectorRegistry
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import ro.ak.vodunify.core.connector.supports
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.SyncStateEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchlistEntity
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/** Starea unui titlu în sursele conectate (pentru ecranul de detalii). */
data class TitleTrackState(
    val inWatchlist: Boolean,
    val watched: Boolean,
    /** Progresul de reluare cel mai recent; null dacă nu e început. */
    val resume: ResumePointEntity?,
)

/**
 * Datele personale (istoric, progres, watchlist, următorul episod) din toți trackerii
 * conectați. UI-ul citește doar din Room; [sync] aduce datele din surse.
 */
@Singleton
class TrackingRepository @Inject constructor(
    private val registry: ConnectorRegistry,
    private val dao: TrackingDao,
) {
    private val syncMutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val trackers: List<Connector> get() = registry.all.filter { it.kind == ConnectorKind.TRACKER }

    /** True dacă cel puțin un tracker e conectat. */
    val anyConnected: Flow<Boolean> = trackers.filterIsInstance<DeviceAuthConnector>()
        .map { c -> c.account.map { it != null && c.isConfigured } }
        .let { flows -> if (flows.isEmpty()) flowOf(false) else combine(flows) { values -> values.any { it } } }

    val continueWatching: Flow<List<ResumePointEntity>> = dao.continueWatching().map { list ->
        TrackingEntities.dedupe(list, { it.ref.mediaKey + ":" + it.ref.season + ":" + it.ref.episode }, { it.updatedAt })
    }

    val upNext: Flow<List<UpNextEntity>> = dao.upNext().map { list ->
        TrackingEntities.dedupe(list, { it.ref.mediaKey }, { it.lastWatchedAt })
    }

    val watchlist: Flow<List<WatchlistEntity>> = dao.watchlist().map { list ->
        TrackingEntities.dedupe(list, { it.ref.mediaKey }, { it.listedAt })
    }

    fun titleState(mediaKey: String): Flow<TitleTrackState> = combine(
        dao.inWatchlist(mediaKey),
        dao.isWatched(mediaKey),
        dao.resumeFor(mediaKey),
    ) { inList, watched, resume -> TitleTrackState(inList, watched, resume.firstOrNull()) }

    fun syncState(source: String): Flow<SyncStateEntity?> = dao.syncStateFlow(source)

    private suspend fun connected(capability: Capability): List<Connector> =
        trackers.filter { it.supports(capability) && it.isConfigured && it.isAuthenticated() }

    suspend fun hasConnectedTracker(): Boolean = trackers.any { it.isConfigured && it.isAuthenticated() }

    /** Sincronizează doar dacă au trecut [maxAgeMillis] de la ultima sincronizare. */
    suspend fun syncIfStale(maxAgeMillis: Long = TimeUnit.MINUTES.toMillis(15)) {
        val now = System.currentTimeMillis()
        val stale = trackers.any { c ->
            c.isConfigured && c.isAuthenticated() && (dao.syncState(c.id)?.lastSyncAt ?: 0L) < now - maxAgeMillis
        }
        if (stale) sync()
    }

    suspend fun sync() {
        if (syncMutex.isLocked) return
        syncMutex.withLock {
            val targets = trackers.filter { it.isConfigured && it.isAuthenticated() }
            if (targets.isEmpty()) {
                _status.value = SyncStatus.Idle
                return
            }
            _status.value = SyncStatus.Running
            val errors = targets.mapNotNull { c ->
                try {
                    syncConnector(c)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    val prev = dao.syncState(c.id)
                    dao.putSyncState(SyncStateEntity(c.id, prev?.lastSyncAt ?: 0L, prev?.historyCursor, message))
                    "${c.displayName}: $message"
                }
            }
            _status.value = if (errors.isEmpty()) SyncStatus.Done(System.currentTimeMillis()) else SyncStatus.Failed(errors.joinToString("\n"))
        }
    }

    private suspend fun syncConnector(c: Connector) {
        val source = c.id
        if (c.supports(Capability.RESUME_READ)) {
            dao.replaceResume(source, c.readResume().map(TrackingEntities::resume))
        }
        if (c.supports(Capability.WATCHLIST_READ)) {
            dao.replaceWatchlist(source, c.readWatchlist().map(TrackingEntities::watchlist))
        }
        var cursor = dao.syncState(source)?.historyCursor
        if (c.supports(Capability.HISTORY_READ)) {
            dao.replaceWatched(source, c.readWatchedTitles().map(TrackingEntities::watched))
            val since = cursor?.let { Instant.ofEpochMilli(it + 1) }
            val events = c.readHistory(since)
            dao.insertHistory(events.map(TrackingEntities::history))
            events.maxOfOrNull { it.watchedAt.toEpochMilli() }?.let { cursor = maxOf(cursor ?: 0L, it) }
        }
        if (c.supports(Capability.UP_NEXT_READ)) {
            dao.replaceUpNext(source, c.readUpNext().mapNotNull(TrackingEntities::upNext))
        }
        dao.putSyncState(SyncStateEntity(source, System.currentTimeMillis(), cursor, null))
    }

    // ---------- Acțiuni din UI (trimise în toate sursele conectate) ----------

    /** Marchează văzut / nevăzut. Întoarce numărul de surse actualizate. */
    suspend fun setWatched(media: MediaRef, watched: Boolean): Int {
        val targets = connected(Capability.HISTORY_WRITE)
        requireTargets(targets)
        targets.forEach { it.setWatched(media, watched) }
        if (media.type != MediaType.EPISODE) {
            if (watched) {
                dao.upsertWatched(
                    targets.map { TrackingEntities.watched(WatchEvent(media, Instant.now(), it.id)) },
                )
            } else {
                dao.removeWatched(media.titleKey)
            }
        }
        if (watched) targets.forEach { dao.deleteResumeById("${it.id}|${media.itemKey}") }
        if (watched && media.type == MediaType.MOVIE) dao.removeFromWatchlist(media.titleKey)
        return targets.size
    }

    suspend fun setInWatchlist(media: MediaRef, inWatchlist: Boolean): Int {
        val targets = connected(Capability.WATCHLIST_WRITE)
        requireTargets(targets)
        targets.forEach { it.setInWatchlist(media, inWatchlist) }
        if (inWatchlist) {
            dao.upsertWatchlist(
                targets.map { TrackingEntities.watchlist(WatchlistEntry(media, Instant.now(), it.id)) },
            )
        } else {
            dao.removeFromWatchlist(media.titleKey)
        }
        return targets.size
    }

    /** Progresul pe serial din prima sursă conectată care îl știe. */
    suspend fun showProgress(show: MediaRef): ShowProgress? {
        for (c in connected(Capability.UP_NEXT_READ)) {
            c.showProgress(show)?.let { return it }
        }
        return null
    }

    /** După un episod marcat, reîmprospătează rândul „Următorul episod” pentru serial. */
    suspend fun refreshUpNext(show: MediaRef) {
        for (c in connected(Capability.UP_NEXT_READ)) {
            val p = runCatching { c.showProgress(show) }.getOrNull() ?: continue
            val entity = TrackingEntities.upNext(p)
            if (entity != null) dao.upsertUpNext(listOf(entity)) else dao.deleteUpNextById("${c.id}|${show.titleKey}")
        }
    }

    suspend fun clearSource(source: String) = dao.clearSource(source)

    private fun requireTargets(targets: List<Connector>) {
        if (targets.isEmpty()) throw IllegalStateException("Niciun cont conectat. Conectează Trakt din Setări.")
    }
}
