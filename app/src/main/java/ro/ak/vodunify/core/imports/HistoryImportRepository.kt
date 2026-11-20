package ro.ak.vodunify.core.imports

import android.content.Context
import android.content.pm.PackageManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.scrobble.ScrobbleMatcher
import ro.ak.vodunify.core.tracking.TrackingEntities
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Istoricul importat din servicii (AK Import RO/US sau CSV-ul Netflix):
 *  1. fiecare lot ajunge într-o coadă pe disc (rapid, fără rețea);
 *  2. un lucru în fundal (WorkManager, cu rețea) găsește titlurile în TMDb și le scrie ca sursă
 *     proprie („netflix”, „primevideo”): vizionările terminate în istoric, cele începute ca
 *     puncte de reluare (cu minutul exact, dacă serviciul îl dă).
 *
 * Reimportul e sigur: id-urile sunt deterministe, deci nu apar dubluri.
 */
@Singleton
class HistoryImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matcher: ScrobbleMatcher,
    private val dao: TrackingDao,
    private val json: Json,
) {
    private val queueDir = File(context.filesDir, "import_queue").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("history_import", Context.MODE_PRIVATE)
    private val statsSerializer = ListSerializer(ImportServiceStats.serializer())
    private val mutex = Mutex()

    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<List<ImportServiceStats>> = _stats.asStateFlow()

    /** Câte elemente așteaptă potrivirea în TMDb. */
    private val _pending = MutableStateFlow(countPending())
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Pachetele AK Import (RO / US) pentru acest build. */
    val importerPackages: Map<String, String> = linkedMapOf(
        "RO" to "ro.ak.vodimport.ro" + if (BuildConfig.DEBUG) ".debug" else "",
        "US" to "ro.ak.vodimport.us" + if (BuildConfig.DEBUG) ".debug" else "",
    )

    fun installedImporters(): Set<String> = importerPackages.filterValues { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }.keys

    /** Pune un lot în coadă și pornește procesarea. Sigur de apelat de pe orice fir. */
    fun enqueue(batch: ImportBatch) {
        if (batch.items.isNotEmpty()) {
            val name = "${System.currentTimeMillis()}-${System.nanoTime()}.json"
            val tmp = File(queueDir, "$name.tmp")
            tmp.writeText(json.encodeToString(ImportBatch.serializer(), batch))
            tmp.renameTo(File(queueDir, name))
        }
        _pending.value = countPending()
        start(context)
    }

    /** Procesează toată coada. Aruncă excepție la erori de rețea (lucrul se reîncearcă). */
    suspend fun processQueue() = mutex.withLock {
        while (true) {
            val next = queueFiles().firstOrNull() ?: break
            val batch = runCatching { json.decodeFromString(ImportBatch.serializer(), next.readText()) }.getOrNull()
            if (batch != null) process(batch)
            next.delete()
            _pending.value = countPending()
        }
    }

    suspend fun clear(service: String) = withContext(Dispatchers.IO) {
        dao.clearSource(service)
        writeStats(_stats.value.filterNot { it.service == service })
    }

    private suspend fun process(batch: ImportBatch) {
        val service = batch.service
        var matched = 0
        var unmatched = 0
        val samples = mutableListOf<String>()
        val history = mutableListOf<WatchEvent>()
        val resume = mutableMapOf<String, Pair<Long, ResumePointEntity>>()
        val finishedAt = mutableMapOf<String, Long>()

        for (item in batch.items) {
            val guesses = ImportParser.guesses(item)
            val match = if (guesses.isEmpty()) null else matcher.match(guesses, item.duration?.times(1000))
            if (match == null) {
                unmatched++
                if (samples.size < 15) samples += label(item)
                continue
            }
            matched++
            val media = match.media
            val at = item.date.takeIf { it > 0 } ?: System.currentTimeMillis()
            when (val kind = ImportPlanner.classify(item.bookmark, item.duration, match.runtimeMs)) {
                ImportPlanner.Kind.Watched -> {
                    if (item.date > 0) history += WatchEvent(media, Instant.ofEpochMilli(at), service)
                    finishedAt.merge(media.itemKey, at, ::maxOf)
                    if (media.type == MediaType.MOVIE && item.date <= 0) history += WatchEvent(media, Instant.ofEpochMilli(0), service)
                }
                is ImportPlanner.Kind.Resume -> {
                    val current = resume[media.itemKey]
                    if (current == null || current.first < at) {
                        resume[media.itemKey] = at to ResumePointEntity(
                            id = "$service|${media.itemKey}",
                            source = service,
                            ref = TrackingEntities.ref(media),
                            progress = kind.progress,
                            updatedAt = at,
                            remoteId = null,
                            positionMs = kind.positionMs,
                        )
                    }
                }
                ImportPlanner.Kind.Skip -> Unit
            }
        }

        // Un punct de reluare mai vechi decât o vizionare terminată a aceluiași episod nu mai contează.
        val resumes = resume.filter { (key, pair) -> (finishedAt[key] ?: 0L) < pair.first }.values.map { it.second }
        writeHistory(history.filter { it.watchedAt.toEpochMilli() > 0 }, service)
        writeWatchedMovies(history, service)
        if (resumes.isNotEmpty()) dao.upsertResume(resumes)

        updateStats(batch, matched, unmatched, resumes.size, samples)
    }

    private suspend fun writeHistory(events: List<WatchEvent>, service: String) {
        if (events.isEmpty()) return
        dao.insertHistory(
            events.map { e ->
                // Id determinist: aceeași vizionare reimportată nu apare de două ori.
                TrackingEntities.history(e).copy(id = "$service|${e.media.itemKey}@${e.watchedAt.toEpochMilli()}")
            },
        )
    }

    private suspend fun writeWatchedMovies(events: List<WatchEvent>, service: String) {
        val movies = events.filter { it.media.type == MediaType.MOVIE }
            .groupBy { it.media.titleKey }
            .map { (_, list) -> list.maxBy { it.watchedAt } }
            .map { e -> if (e.watchedAt.toEpochMilli() > 0) e else e.copy(watchedAt = Instant.now()) }
        if (movies.isNotEmpty()) dao.upsertWatched(movies.map { TrackingEntities.watched(it) })
        // Pentru seriale: titlul e „văzut” doar dacă tracker-ul o spune; aici păstrăm doar istoricul episoadelor.
    }

    private fun label(item: ImportItem): String =
        listOfNotNull(item.series, item.season, item.episode).joinToString(": ").ifBlank { item.title }

    private fun updateStats(batch: ImportBatch, matched: Int, unmatched: Int, resumes: Int, samples: List<String>) {
        val old = _stats.value.firstOrNull { it.service == batch.service }
        // Un import nou (primul lot după o pauză lungă) pornește numărătoarea de la zero.
        val fresh = old == null || System.currentTimeMillis() - old.lastImportAt > FRESH_AFTER_MS
        val base = if (fresh || old == null) ImportServiceStats(batch.service) else old
        val updated = base.copy(
            received = base.received + batch.items.size,
            matched = base.matched + matched,
            unmatched = base.unmatched + unmatched,
            resumePoints = base.resumePoints + resumes,
            lastImportAt = System.currentTimeMillis(),
            profile = batch.profile.ifBlank { base.profile },
            route = batch.route.ifBlank { base.route },
            unmatchedSamples = (base.unmatchedSamples + samples).takeLast(15),
        )
        writeStats(_stats.value.filterNot { it.service == batch.service } + updated)
    }

    private fun writeStats(list: List<ImportServiceStats>) {
        _stats.value = list
        prefs.edit().putString(KEY_STATS, json.encodeToString(statsSerializer, list)).apply()
    }

    private fun readStats(): List<ImportServiceStats> = runCatching {
        prefs.getString(KEY_STATS, null)?.let { json.decodeFromString(statsSerializer, it) }
    }.getOrNull().orEmpty()

    private fun queueFiles(): List<File> =
        queueDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }

    private fun countPending(): Int = queueFiles().sumOf { f ->
        runCatching { json.decodeFromString(ImportBatch.serializer(), f.readText()).items.size }.getOrDefault(0)
    }

    companion object {
        private const val KEY_STATS = "stats"
        private const val FRESH_AFTER_MS = 10 * 60_000L
        private const val WORK = "history_import"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<HistoryImportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
