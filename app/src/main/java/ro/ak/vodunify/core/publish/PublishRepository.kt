package ro.ak.vodunify.core.publish

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.ak.vodunify.core.connector.Capability
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.connector.ConnectorKind
import ro.ak.vodunify.core.connector.ConnectorRegistry
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.supports
import ro.ak.vodunify.core.db.OwnWriteAction
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.PublishDao
import ro.ak.vodunify.core.db.PublishRecordEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.reconcile.KeyResolver
import ro.ak.vodunify.core.reconcile.ReconciledResume
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publică progresul (din „Setează minutul” sau din sursa reconciliată) în trackerele alese
 * din profilul activ, prin metodele documentate de „pauză”: Trakt `POST /scrobble/pause`,
 * SIMKL `POST /scrobble/pause`. Nu pornește sesiuni false („start”) și nu trimite „stop”,
 * deci nu transformă niciodată o poziție parțială în „văzut”.
 *
 * După fiecare scriere citește înapoi punctele de reluare ale destinației și doar dacă
 * poziția apare acolo marchează „Publicat”. Fiecare scriere e înregistrată ca scriere proprie
 * (own_writes), ca reconcilierea să-i recunoască ecoul la următoarea sincronizare.
 */
@Singleton
class PublishRepository @Inject constructor(
    private val registry: ConnectorRegistry,
    private val tracking: TrackingRepository,
    private val trackingDao: TrackingDao,
    private val dao: PublishDao,
    private val prefs: PublishPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    init {
        // După fiecare sincronizare: reverificăm ce n-a fost confirmat, apoi (în modul automat) publicăm.
        scope.launch {
            var last: SyncStatus? = null
            tracking.status.collect { s ->
                if (s is SyncStatus.Done && s != last) {
                    last = s
                    runCatching { afterSync() }
                }
            }
        }
    }

    val records = dao.all()
    val settings = prefs.settings

    fun label(id: String): String = when (id) {
        LOCAL_SOURCE -> "Local (setat de tine sau AK Scrobbler)"
        else -> registry.byId(id)?.displayName ?: id
    }

    /** Trackerele care pot primi progres (indiferent dacă sunt conectate acum). */
    val writableTrackers: List<Connector>
        get() = registry.all.filter { it.kind == ConnectorKind.TRACKER && it.supports(Capability.RESUME_WRITE) }

    /** Trackerele-destinație conectate acum. */
    val connectedDestinations: Flow<Set<String>> = writableTrackers.filterIsInstance<DeviceAuthConnector>()
        .map { c -> c.account.map { a -> if (a != null && c.isConfigured) c.id else null } }
        .let { flows -> if (flows.isEmpty()) flowOf(emptySet()) else combine(flows) { ids -> ids.filterNotNull().toSet() } }

    /** Ecranul „Diagnostic rute”, recalculat la orice schimbare. */
    val diagnostics: Flow<List<RouteItem>> = combine(
        tracking.reconciled,
        trackingDao.allResume(),
        dao.all(),
        prefs.settings,
        connectedDestinations,
    ) { view, all, recs, s, connected ->
        RouteDiagnostics.build(
            resume = view.resume,
            allResume = all,
            records = recs,
            profile = s.active,
            trackers = writableTrackers.associate { it.id to it.displayName },
            connected = connected,
            now = System.currentTimeMillis(),
        )
    }.flowOn(Dispatchers.Default)

    private suspend fun ready(c: Connector) = c.isConfigured && c.isAuthenticated()

    // ---------- Setează minutul ----------

    /**
     * Salvează poziția ta pentru un film / episod (sursa „local”), apoi, în modul automat,
     * o publică. [positionMs] = 0 șterge poziția locală. [publish] = false doar salvează
     * (punctele intermediare din AK Scrobbler, ca să nu scriem în trackere la fiecare minut).
     */
    suspend fun setManualPosition(media: MediaRef, positionMs: Long, durationMs: Long?, publish: Boolean = true): List<PublishOutcome> {
        require(media.type != MediaType.SHOW) { "Setează minutul pentru un film sau un episod" }
        val id = "$LOCAL_SOURCE|${media.itemKey}"
        if (positionMs <= 0L) {
            trackingDao.deleteResumeById(id)
            return emptyList()
        }
        val progress = durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
        trackingDao.upsertResume(
            listOf(
                ResumePointEntity(
                    id = id,
                    source = LOCAL_SOURCE,
                    ref = TrackingEntities.ref(media),
                    progress = progress,
                    updatedAt = System.currentTimeMillis(),
                    remoteId = null,
                    positionMs = positionMs,
                ),
            ),
        )
        val profile = prefs.current().active
        return if (publish && profile.mode == PublishMode.AUTOMATIC) publishItem(media, manual = true) else emptyList()
    }

    // ---------- Publicare ----------

    /** „Publică acum” pentru un element (film / episod). */
    suspend fun publishItem(media: MediaRef, manual: Boolean = true): List<PublishOutcome> = mutex.withLock {
        val profile = prefs.current().active
        if (profile.mode == PublishMode.VIEW_ONLY) {
            return@withLock listOf(PublishOutcome("", "Profil ${profile.name}", null, "modul „Doar vizualizare” nu publică"))
        }
        val view = tracking.reconcileNow()
        val all = trackingDao.allResume().first()
        val keys = KeyResolver(all.map { it.ref } + TrackingEntities.ref(media))
        val itemKey = keys.itemKey(TrackingEntities.ref(media))
        val r = view.resume.firstOrNull { it.itemKey == itemKey }
            ?: return@withLock listOf(PublishOutcome("", "Progres", null, "nu există o poziție de reluare pentru acest element"))
        publishResume(r, all, profile, manual, System.currentTimeMillis())
    }

    /** Publicarea automată: pozițiile recente, câte destinații lipsesc, cu limită pe rulare. */
    suspend fun autoPublish(): List<PublishOutcome> = mutex.withLock {
        val profile = prefs.current().active
        if (profile.mode != PublishMode.AUTOMATIC) return@withLock emptyList()
        val now = System.currentTimeMillis()
        val all = trackingDao.allResume().first()
        val out = mutableListOf<PublishOutcome>()
        var writes = 0
        for (r in tracking.reconcileNow().resume) {
            if (writes >= PublishPlanner.AUTO_MAX_PER_RUN) break
            val c = PublishPlanner.candidate(r, localPosition(r, all)) ?: continue
            if (!PublishPlanner.autoEligible(c, now)) continue
            val res = publishResume(r, all, profile, manual = false, now = now)
            writes += res.count { it.status != null }
            out += res.filter { it.status != null }
        }
        out
    }

    private fun localPosition(r: ReconciledResume, all: List<ResumePointEntity>): Long? {
        if (r.winner.source == LOCAL_SOURCE) return r.winner.positionMs
        val keys = KeyResolver(all.map { it.ref })
        return all.firstOrNull { it.source == LOCAL_SOURCE && keys.itemKey(it.ref) == r.itemKey }?.positionMs
    }

    private suspend fun publishResume(
        r: ReconciledResume,
        all: List<ResumePointEntity>,
        profile: PublishProfile,
        manual: Boolean,
        now: Long,
    ): List<PublishOutcome> {
        val candidate = PublishPlanner.candidate(r, localPosition(r, all))
            ?: return listOf(PublishOutcome("", "Progres", null, "poziția e un ecou al unei publicări proprii"))
        val destinations = writableTrackers.filter { it.id in profile.destinations }
        if (destinations.isEmpty()) return emptyList()
        return destinations.map { c ->
            if (!ready(c)) return@map PublishOutcome(c.id, c.displayName, null, "neconectat")
            val destPoint = r.evidences.filter { it.source == c.id }.maxByOrNull { it.at }
            val record = dao.get("${c.id}|${candidate.itemKey}")
            when (val d = PublishPlanner.decide(candidate, c.id, destPoint, record, now, manual)) {
                is PublishDecision.Skip -> PublishOutcome(c.id, c.displayName, null, d.reason)
                PublishDecision.Publish -> write(c, candidate)
            }
        }
    }

    private suspend fun write(c: Connector, candidate: PublishCandidate): PublishOutcome {
        val progress = requireNotNull(candidate.progress)
        val now = System.currentTimeMillis()
        val base = PublishRecordEntity(
            id = "${c.id}|${candidate.itemKey}",
            destination = c.id,
            itemKey = candidate.itemKey,
            ref = candidate.ref,
            progress = progress,
            positionMs = candidate.positionMs,
            origin = candidate.origin,
            originAt = candidate.originAt,
            status = PublishStatus.SENT.name,
            attemptAt = now,
            verifiedAt = null,
            readBackProgress = null,
            error = null,
        )
        dao.prune(now - RECORD_TTL_MS)
        dao.upsert(base)
        // Scrierea proprie: ecoul ei din sursă nu va fi luat drept o poziție nouă.
        trackingDao.putOwnWrites(
            listOf(OwnWriteEntity("${c.id}|${candidate.itemKey}|${OwnWriteAction.RESUME.name}", c.id, candidate.itemKey, OwnWriteAction.RESUME.name, progress, now)),
        )
        try {
            c.writeResume(ResumePoint(TrackingEntities.media(candidate.ref), progress, Instant.ofEpochMilli(now), c.id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            dao.upsert(base.copy(status = PublishStatus.FAILED.name, error = msg))
            return PublishOutcome(c.id, c.displayName, PublishStatus.FAILED, error = msg)
        }
        // Citire înapoi: doar dacă destinația arată poziția o marcăm „Publicat”.
        val readBack = try {
            c.readResume().map(TrackingEntities::resume)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.upsert(base.copy(status = PublishStatus.NOT_CONFIRMED.name, error = "Citirea înapoi a eșuat: ${e.message}"))
            return PublishOutcome(c.id, c.displayName, PublishStatus.NOT_CONFIRMED)
        }
        trackingDao.replaceResume(c.id, readBack)
        val match = PublishPlanner.matchReadBack(candidate.ref, readBack)
        val status = PublishPlanner.verify(progress, match)
        dao.upsert(
            base.copy(
                status = status.name,
                verifiedAt = if (status == PublishStatus.PUBLISHED) System.currentTimeMillis() else null,
                readBackProgress = match?.progress,
            ),
        )
        return PublishOutcome(c.id, c.displayName, status)
    }

    // ---------- După sincronizare ----------

    /** Reverifică publicările neconfirmate cu datele proaspăt sincronizate. */
    suspend fun verifyPending() {
        val pending = dao.withStatus(listOf(PublishStatus.SENT.name, PublishStatus.NOT_CONFIRMED.name))
        if (pending.isEmpty()) return
        val all = trackingDao.allResume().first()
        pending.forEach { rec ->
            val points = all.filter { it.source == rec.destination }
            val match = PublishPlanner.matchReadBack(rec.ref, points)
            val status = PublishPlanner.verify(rec.progress, match)
            if (status == PublishStatus.PUBLISHED) {
                dao.upsert(rec.copy(status = status.name, verifiedAt = System.currentTimeMillis(), readBackProgress = match?.progress, error = null))
            } else if (match?.progress != rec.readBackProgress) {
                dao.upsert(rec.copy(status = PublishStatus.NOT_CONFIRMED.name, readBackProgress = match?.progress))
            }
        }
    }

    private suspend fun afterSync() {
        verifyPending()
        autoPublish()
    }

    // ---------- Profiluri ----------

    suspend fun setActiveProfile(index: Int) = prefs.setActive(index)
    suspend fun setMode(index: Int, mode: PublishMode) = prefs.setMode(index, mode)
    suspend fun toggleDestination(index: Int, destination: String) = prefs.toggleDestination(index, destination)

    private companion object {
        val RECORD_TTL_MS: Long = TimeUnit.DAYS.toMillis(60)
    }
}
