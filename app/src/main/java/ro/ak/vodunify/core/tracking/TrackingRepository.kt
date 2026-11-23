package ro.ak.vodunify.core.tracking

import androidx.room.withTransaction

import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.ak.vodunify.core.connector.Capability
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.connector.ConnectorKind
import ro.ak.vodunify.core.connector.ConnectorRegistry
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import ro.ak.vodunify.core.connector.supports
import ro.ak.vodunify.core.db.OwnWriteAction
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.SyncStateEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.db.VodDatabase
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchlistEntity
import ro.ak.vodunify.core.reconcile.LibraryEntry
import ro.ak.vodunify.core.reconcile.ReconciledView
import ro.ak.vodunify.core.reconcile.reconcile
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
    /**
     * Titlul apare în Lista mea: e „De văzut” sau are istoric / minut salvat (din AK Scrobbler sau importat
     * din Netflix / Trakt) și nu l-ai scos de mână.
     */
    val inMyList: Boolean = inWatchlist,
)

/**
 * Datele personale (istoric, progres, watchlist, următorul episod) din toți trackerii
 * conectați. UI-ul citește doar din Room; [sync] aduce datele din surse.
 */
@Singleton
class TrackingRepository @Inject constructor(
    private val registry: ConnectorRegistry,
    private val dao: TrackingDao,
    private val hidden: HiddenTitles,
    private val db: VodDatabase,
) {
    /** Titlurile scoase de mână din Lista mea (vezi [HiddenTitles]). */
    val hiddenTitles: StateFlow<Map<String, Long>> get() = hidden.all

    /** Sursele ale căror date nu se arată (și nu se șterg): Trakt/SIMKL cât timp sunt ascunse. */
    private val protectedSources: List<String>
        get() = if (ExternalTrackers.ENABLED) emptyList() else ExternalTrackers.SOURCES.toList()

    private val syncMutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val trackers: List<Connector> get() = registry.all.filter { it.kind == ConnectorKind.TRACKER }

    /** True dacă cel puțin un tracker e conectat. */
    val anyConnected: Flow<Boolean> = trackers.filterIsInstance<DeviceAuthConnector>()
        .map { c -> c.account.map { it != null && c.isConfigured } }
        .let { flows -> if (flows.isEmpty()) flowOf(false) else combine(flows) { values -> values.any { it } } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Tot ce știu sursele, reconciliat (vezi [Reconciler]). Calculat o dată, partajat. */
    val reconciled: Flow<ReconciledView> = combine(
        combine(dao.allResume(), dao.latestWatches(), dao.watchedTitles()) { a, b, c -> Triple(a, b, c) },
        dao.watchlist(),
        dao.upNext(),
        dao.ownWrites(),
    ) { (resume, watches, watched), list, next, own ->
        reconcile(
            resume.visible { it.source },
            watches.visible { it.source },
            watched.visible { it.source },
            list.visible { it.source },
            next.visible { it.source },
            own.visible { it.source },
        )
    }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    /** Reconcilierea calculată acum, direct din Room (fără valoarea partajată, care poate întârzia). */
    suspend fun reconcileNow(): ReconciledView = reconcile(
        dao.allResume().first().visible { it.source },
        dao.latestWatches().first().visible { it.source },
        dao.watchedTitles().first().visible { it.source },
        dao.watchlist().first().visible { it.source },
        dao.upNext().first().visible { it.source },
        dao.ownWrites().first().visible { it.source },
    )

    /**
     * „Continuă vizionarea” vine DOAR din AK Scrobbler (sursa locală): ce ai început în Netflix,
     * Prime, Apple TV, Stremio etc. Trakt/SIMKL nu mai sunt sursa (nu văd aplicațiile de streaming).
     * Un singur rând pe titlu: dacă același film / serial a fost pornit din mai multe aplicații (sau
     * episoade diferite), rămâne doar vizionarea cea mai recentă.
     */
    val continueWatching: Flow<List<ResumePointEntity>> =
        dao.continueWatching().map { list -> latestPerTitle(list.filter { it.source == LOCAL_SOURCE }) }

    val upNext: Flow<List<UpNextEntity>> = reconciled.map { it.upNext }

    val watchlist: Flow<List<WatchlistEntity>> = reconciled.map { it.watchlist }

    val library: Flow<List<LibraryEntry>> = reconciled.map { it.library }

    fun titleState(mediaKey: String): Flow<TitleTrackState> =
        if (ExternalTrackers.ENABLED) {
            combine(
                dao.inWatchlist(mediaKey),
                dao.isWatched(mediaKey),
                dao.resumeFor(mediaKey),
            ) { inList, watched, resume -> TitleTrackState(inList, watched, resume.firstOrNull()) }
        } else {
            // Doar datele locale: ce a rămas de la Trakt/SIMKL nu mai contează.
            combine(
                combine(dao.watchlist(), dao.watchedTitles(), dao.resumeFor(mediaKey)) { a, b, c -> Triple(a, b, c) },
                dao.lastWatchedAt(mediaKey, protectedSources),
                hidden.all,
            ) { (list, watchedList, resumeList), lastWatched, hiddenMap ->
                val listed = list.filter { it.ref.mediaKey == mediaKey && ExternalTrackers.isVisible(it.source) }
                val watched = watchedList.filter { it.ref.mediaKey == mediaKey && ExternalTrackers.isVisible(it.source) }
                val resume = resumeList.firstOrNull { ExternalTrackers.isVisible(it.source) }
                val lastActivity = maxOf(
                    lastWatched ?: 0L,
                    watched.maxOfOrNull { it.lastWatchedAt } ?: 0L,
                    resume?.updatedAt ?: 0L,
                )
                val hasActivity = lastWatched != null || watched.isNotEmpty() || resume != null
                TitleTrackState(
                    inWatchlist = listed.isNotEmpty(),
                    watched = watched.isNotEmpty(),
                    resume = resume,
                    inMyList = listed.isNotEmpty() || (hasActivity && !HiddenTitles.isHidden(hiddenMap, mediaKey, lastActivity)),
                )
            }
        }

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

    private suspend fun recordOwnWrite(targets: List<Connector>, itemKey: String, action: OwnWriteAction, at: Long, progress: Float? = null) {
        dao.pruneOwnWrites(System.currentTimeMillis() - OWN_WRITE_TTL_MS)
        dao.putOwnWrites(targets.map { OwnWriteEntity("${it.id}|$itemKey|${action.name}", it.id, itemKey, action.name, progress, at) })
    }

    /** Marchează văzut / nevăzut. Întoarce numărul de surse actualizate. */
    suspend fun setWatched(media: MediaRef, watched: Boolean): Int {
        if (!ExternalTrackers.ENABLED) return setWatchedLocally(media, watched)
        val targets = connected(Capability.HISTORY_WRITE)
        requireTargets(targets)
        val now = Instant.now()
        targets.forEach { it.setWatched(media, watched, if (watched) now else null) }
        recordOwnWrite(targets, media.itemKey, if (watched) OwnWriteAction.WATCHED else OwnWriteAction.UNWATCHED, now.toEpochMilli())
        if (watched) {
            // Copie locală a vizionării: închide imediat punctul de reluare; când sursa o trimite
            // înapoi, reconcilierea o vede ca aceeași vizionare (ecou), nu ca una nouă.
            dao.insertHistory(targets.map { TrackingEntities.history(WatchEvent(media, now, it.id)) })
        }
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
        if (!ExternalTrackers.ENABLED) return setInWatchlistLocally(media, inWatchlist)
        val targets = connected(Capability.WATCHLIST_WRITE)
        requireTargets(targets)
        targets.forEach { it.setInWatchlist(media, inWatchlist) }
        val key = (if (media.type == MediaType.EPISODE) media.asShow() else media).titleKey
        recordOwnWrite(
            targets,
            key,
            if (inWatchlist) OwnWriteAction.WATCHLIST_ADD else OwnWriteAction.WATCHLIST_REMOVE,
            System.currentTimeMillis(),
        )
        if (inWatchlist) {
            dao.upsertWatchlist(
                targets.map { TrackingEntities.watchlist(WatchlistEntry(media, Instant.now(), it.id)) },
            )
        } else {
            dao.removeFromWatchlist(media.titleKey)
        }
        return targets.size
    }

    /** Pe fiecare serial: câte episoade ai văzut și ultimul (din istoricul local). */
    val seriesSummaries: Flow<Map<String, SeriesSummary>> =
        dao.latestWatches().map { LocalProgress.summaries(it.visible { e -> e.source }) }.flowOn(Dispatchers.Default)

    /** Progresul pe serial din istoricul local. [seasonCounts]: sezon → episoade (din TMDb). */
    fun localShowProgress(show: MediaRef, seasonCounts: Map<Int, Int>): Flow<ShowProgress> =
        dao.historyFor(show.titleKey).map { rows -> LocalProgress.show(show, rows.visible { it.source }, seasonCounts, LOCAL_SOURCE) }

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

    /** Scoate titlul (filmul sau tot serialul) din „Continuă vizionarea”; revine dacă îl pornești din nou. */
    suspend fun removeFromContinueWatching(mediaKey: String) = dao.deleteResumeFor(mediaKey)

    // ---------- Fără trackeri externi: „Văzut” și „De văzut” se țin doar în baza locală ----------

    private suspend fun setWatchedLocally(media: MediaRef, watched: Boolean): Int {
        val now = Instant.now()
        if (watched) {
            val event = WatchEvent(media, now, LOCAL_SOURCE)
            dao.insertHistory(listOf(TrackingEntities.history(event)))
            if (media.type != MediaType.EPISODE) dao.upsertWatched(listOf(TrackingEntities.watched(event)))
            dao.deleteResumeById("$LOCAL_SOURCE|${media.itemKey}")
            if (media.type == MediaType.MOVIE) dao.removeFromWatchlist(media.titleKey)
        } else {
            // Din toate sursele (AK Scrobbler, Netflix, Trakt importat), altfel titlurile importate rămân „văzute”.
            when (media.type) {
                MediaType.EPISODE -> dao.deleteItemHistory(media.titleKey, media.season, media.episode, protectedSources)
                else -> dao.deleteTitleHistory(media.titleKey, protectedSources)
            }
            if (media.type != MediaType.EPISODE) dao.removeWatched(media.titleKey)
        }
        return 1
    }

    /**
     * Marchează mai multe episoade ale unui serial (un sezon întreg sau tot serialul) văzute / nevăzute,
     * doar în baza locală.
     */
    suspend fun markEpisodesWatched(show: MediaRef, episodes: List<EpisodeNumber>) {
        if (episodes.isEmpty()) return
        val base = show.copy(type = MediaType.EPISODE, episodeTitle = null)
        db.withTransaction {
            val now = System.currentTimeMillis()
            val existing = dao.historyFor(show.titleKey).first().visible { it.source }
                .mapNotNull { e -> e.ref.season?.let { s -> e.ref.episode?.let { EpisodeNumber(s, it) } } }
                .toSet()
            // Câte o milisecundă între ele, ca ordinea episoadelor să rămână în istoric; cele deja văzute rămân cum sunt.
            val fresh = episodes.filter { it !in existing }
            dao.insertHistory(
                fresh.mapIndexed { i, n ->
                    TrackingEntities.history(
                        WatchEvent(base.copy(season = n.season, episode = n.episode), Instant.ofEpochMilli(now - fresh.size + i), LOCAL_SOURCE),
                    )
                },
            )
            episodes.forEach { n -> dao.deleteResumeById("$LOCAL_SOURCE|${base.copy(season = n.season, episode = n.episode).itemKey}") }
        }
        hidden.show(show.titleKey)
    }

    suspend fun clearSeriesHistory(show: MediaRef, seasons: List<Int>?): Int = db.withTransaction {
        if (seasons == null) {
            val removed = dao.deleteTitleHistory(show.titleKey, protectedSources)
            dao.removeWatchedFromVisibleSources(show.titleKey, protectedSources)
            removed
        } else {
            dao.deleteSeasonHistory(show.titleKey, seasons, protectedSources)
        }
    }

    private suspend fun setInWatchlistLocally(media: MediaRef, inWatchlist: Boolean): Int {
        if (inWatchlist) {
            val title = if (media.type == MediaType.EPISODE) media.asShow() else media
            dao.upsertWatchlist(listOf(TrackingEntities.watchlist(WatchlistEntry(title, Instant.now(), LOCAL_SOURCE))))
            hidden.show(media.titleKey)
        } else {
            // Scos din Lista mea: și din „De văzut”, și ascuns, chiar dacă are istoric (ex. importat din Netflix).
            dao.removeFromWatchlist(media.titleKey)
            hidden.hide(media.titleKey)
        }
        return 1
    }

    private fun requireTargets(targets: List<Connector>) {
        if (targets.isEmpty()) throw IllegalStateException("Niciun cont conectat. Conectează Trakt sau SIMKL din Setări.")
    }

    private fun <T> List<T>.visible(source: (T) -> String): List<T> =
        if (ExternalTrackers.ENABLED) this else filter { ExternalTrackers.isVisible(source(it)) }

    private companion object {
        /** Scrierile proprii se țin o săptămână: destul cât să revină ecoul din orice sursă. */
        val OWN_WRITE_TTL_MS: Long = TimeUnit.DAYS.toMillis(7)
    }
}

/** Cea mai recentă poziție pentru fiecare titlu (serial = toate episoadele lui), cele mai noi întâi. */
internal fun latestPerTitle(list: List<ResumePointEntity>): List<ResumePointEntity> =
    list.groupBy { it.ref.mediaKey }.values.map { group -> group.maxBy { it.updatedAt } }.sortedByDescending { it.updatedAt }
