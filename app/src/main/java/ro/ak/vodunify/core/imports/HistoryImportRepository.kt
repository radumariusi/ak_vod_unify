package ro.ak.vodunify.core.imports

import android.content.Context
import android.util.AtomicFile
import androidx.work.BackoffPolicy
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
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.scrobble.ScrobbleMatch
import ro.ak.vodunify.core.scrobble.ScrobbleMatcher
import ro.ak.vodunify.core.tmdb.TmdbHttpException
import ro.ak.vodunify.core.tracking.TrackingEntities
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Istoricul importat din servicii (contul serviciului sau CSV-ul Netflix):
 *  1. fiecare lot ajunge într-o coadă pe disc (rapid, fără rețea);
 *  2. un lucru în fundal (WorkManager, cu rețea) găsește titlurile în TMDb și le scrie ca sursă
 *     proprie („netflix”, „primevideo”, „trakt_import”): vizionările terminate în istoric, cele începute ca
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
    private val unmatchedDir = File(context.filesDir, "import_unmatched").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("history_import", Context.MODE_PRIVATE)
    private val statsSerializer = ListSerializer(ImportServiceStats.serializer())
    private val mutex = Mutex()

    private val _stats = MutableStateFlow(readStats())
    val stats: StateFlow<List<ImportServiceStats>> = _stats.asStateFlow()

    /** Câte elemente așteaptă potrivirea în TMDb. */
    private val _pending = MutableStateFlow(countPending())
    val pending: StateFlow<Int> = _pending.asStateFlow()

    /** Pune un lot în coadă și pornește procesarea. Sigur de apelat de pe orice fir. */
    fun enqueue(batch: ImportBatch) = enqueueAll(listOf(batch))

    fun enqueueAll(batches: List<ImportBatch>) {
        for (batch in batches.filter { it.items.isNotEmpty() }) {
            val name = "${System.currentTimeMillis()}-${System.nanoTime()}.json"
            val tmp = File(queueDir, "$name.tmp")
            tmp.writeText(json.encodeToString(ImportBatch.serializer(), batch))
            if (!tmp.renameTo(File(queueDir, name))) throw IOException("Nu am putut salva lotul de import.")
        }
        _pending.value = countPending()
        start(context)
    }

    /** Procesează toată coada. Aruncă excepție la erori de rețea (lucrul se reîncearcă). */
    suspend fun processQueue() = mutex.withLock {
        _pending.value = countPending()
        while (true) {
            val next = queueFiles().firstOrNull() ?: break
            val batch = runCatching { json.decodeFromString(ImportBatch.serializer(), next.readText()) }.getOrNull()
            if (batch == null || batch.items.isEmpty()) {
                next.delete()
                continue
            }
            // Câte puțin, cu progresul salvat după fiecare bucată: numărul scade pe ecran, iar dacă
            // Android oprește lucrul (limita de 10 minute, rețea), continuăm de unde am rămas.
            val chunk = batch.items.take(CHUNK)
            val rest = batch.items.drop(CHUNK)
            process(batch.copy(items = chunk, last = rest.isEmpty() && batch.last))
            if (rest.isEmpty()) {
                next.delete()
            } else {
                val tmp = File(queueDir, next.name + ".tmp")
                tmp.writeText(json.encodeToString(ImportBatch.serializer(), batch.copy(items = rest)))
                if (!tmp.renameTo(next)) throw IOException("Nu am putut actualiza coada de import.")
            }
            _pending.value = (_pending.value - chunk.size).coerceAtLeast(0)
        }
        _pending.value = countPending()
    }


    suspend fun clear(service: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.clearSource(service)
            unresolvedFile(service)?.delete()
            writeStats(_stats.value.filterNot { it.service == service })
        }
    }

    suspend fun retryUnmatched(service: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val batch = readUnresolved(service) ?: return@withLock 0
            if (batch.items.isEmpty()) return@withLock 0
            enqueue(batch)
            batch.items.size
        }
    }

    private suspend fun process(batch: ImportBatch) {
        val service = batch.service
        var matched = 0
        val unresolved = readUnresolved(service)?.items.orEmpty().associateByTo(linkedMapOf(), ::itemKey)
        val history = mutableListOf<WatchEvent>()
        val resume = mutableMapOf<String, Pair<Long, ResumePointEntity>>()
        val finishedAt = mutableMapOf<String, Long>()

        for (item in batch.items) {
            val match = try {
                direct(item) ?: run {
                    val guesses = ImportParser.guesses(item)
                    if (guesses.isEmpty()) null else matcher.match(guesses, item.duration?.times(1000))
                }
            } catch (e: TmdbHttpException) {
                // Un titlu pe care TMDb îl refuză (404 / 422) nu oprește tot importul; rețeaua da (reîncercăm).
                if (e.code in 400..499 && e.code != 429) null else throw e
            } catch (e: kotlinx.serialization.SerializationException) {
                null
            }
            if (match == null) {
                unresolved[itemKey(item)] = item
                continue
            }
            unresolved.remove(itemKey(item))
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
        resumes.forEach { dao.upsertResumeIfNewer(it) }
        // Din Trakt aducem tot, în afară de ce vine din istoricul Netflix (acela e mai exact).
        // Merge în orice ordine: la importul Trakt și la cel Netflix.
        if (batch.last && (service == ImportSources.TRAKT || service == ImportSources.NETFLIX)) {
            dao.deleteHistoryCoveredBy(drop = ImportSources.TRAKT, keep = ImportSources.NETFLIX)
        }

        val remaining = unresolved.values.toList().takeLast(MAX_UNMATCHED)
        writeUnresolved(batch.copy(items = remaining, last = true))
        updateStats(batch, matched, remaining.size, resumes.size, remaining.takeLast(15).map(::label))
    }

    private fun itemKey(item: ImportItem): String =
        "${item.id.orEmpty()}|${item.title}|${item.series}|${item.season}|${item.episode}|${item.date}"

    private fun unresolvedFile(service: String): AtomicFile? =
        service.takeIf { it in ImportSources.labels }?.let { AtomicFile(File(unmatchedDir, "$it.json")) }

    private fun readUnresolved(service: String): ImportBatch? = runCatching {
        unresolvedFile(service)?.readFully()?.let { json.decodeFromString(ImportBatch.serializer(), it.decodeToString()) }
    }.getOrNull()

    private fun writeUnresolved(batch: ImportBatch) {
        val file = unresolvedFile(batch.service) ?: return
        if (batch.items.isEmpty()) {
            file.delete()
            return
        }
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(ImportBatch.serializer(), batch).encodeToByteArray())
            file.finishWrite(stream)
        } catch (e: IOException) {
            file.failWrite(stream)
            throw e
        }
    }

    /** Sursa dă codul TMDb (Trakt): titlul e sigur, fără căutare după nume. */
    private fun direct(item: ImportItem): ScrobbleMatch? {
        val tmdb = item.tmdb?.takeIf { it > 0 } ?: return null
        val ids = ExternalIds(tmdb = tmdb, imdb = item.imdb?.takeIf { it.isNotBlank() })
        val media = when (item.kind) {
            "movie" -> MediaRef(MediaType.MOVIE, ids, item.title.ifBlank { "Fără titlu" }, item.year)
            "episode" -> {
                val season = item.seasonNumber ?: return null
                val episode = item.episodeNumber ?: return null
                MediaRef(
                    type = MediaType.EPISODE,
                    ids = ids,
                    title = item.series?.takeIf { it.isNotBlank() } ?: "Fără titlu",
                    year = item.year,
                    season = season,
                    episode = episode,
                    episodeTitle = item.episode?.takeIf { it.isNotBlank() },
                )
            }
            else -> return null
        }
        return ScrobbleMatch(media, null)
    }

    private suspend fun writeHistory(events: List<WatchEvent>, service: String) {
        // Aceeași vizionare deja salvată în aceeași zi (de AK Scrobbler, de un import anterior sau din
        // CSV-ul aceluiași serviciu) contează o singură dată. Revizionările din alte zile rămân.
        val fresh = events.filter { e ->
            val ref = TrackingEntities.ref(e.media)
            val (from, to) = ImportPlanner.dayBounds(e.watchedAt.toEpochMilli())
            dao.countWatchesBetween(ref.mediaKey, ref.season, ref.episode, from, to) == 0
        }
        if (fresh.isEmpty()) return
        dao.insertHistory(
            fresh.map { e ->
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
        val base = if (fresh) ImportServiceStats(batch.service) else checkNotNull(old)
        val updated = base.copy(
            received = base.received + batch.items.size,
            matched = base.matched + matched,
            unmatched = unmatched,
            resumePoints = base.resumePoints + resumes,
            lastImportAt = System.currentTimeMillis(),
            profile = batch.profile.ifBlank { base.profile },
            route = batch.route.ifBlank { base.route },
            unmatchedSamples = samples,
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

        /** Câte elemente procesăm între două salvări ale progresului. */
        private const val CHUNK = 25
        private const val MAX_UNMATCHED = 5_000

        /** La pornirea aplicației: dacă a rămas ceva în coadă (aplicația a fost oprită), îl reluăm. */
        fun resumeIfPending(context: Context) {
            val left = File(context.filesDir, "import_queue").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            if (left.isNotEmpty()) enqueue(context, ExistingWorkPolicy.KEEP)
        }

        // Un import cerut acum poate reporni o încercare aflată în așteptare după o eroare.
        fun start(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<HistoryImportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, policy, request)
        }
    }
}
