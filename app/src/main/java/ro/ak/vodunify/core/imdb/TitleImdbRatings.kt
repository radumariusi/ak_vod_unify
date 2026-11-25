package ro.ak.vodunify.core.imdb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbExternalIds
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nota IMDb a unui titlu din TMDb, prin cheia OMDb (Setări → Calendar): id-ul IMDb din TMDb (păstrat 7 zile),
 * nota din OMDb. Notele se păstrează pe dispozitiv câteva zile, ca rândurile de postere să nu consume
 * limita zilnică OMDb (1.000 de cereri pe zi la cheia gratuită) la fiecare pornire a aplicației.
 */
@Singleton
class TitleImdbRatings @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdb: TmdbClient,
    private val calendar: CalendarRepository,
) {
    private val appContext = context.applicationContext
    private val diskFile = File(appContext.filesDir, "omdb_ratings.tsv")
    private val diskTmp = File(appContext.filesDir, "omdb_ratings.tsv.tmp")
    private val storeLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ids = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_SESSION_CACHE
        },
    )
    /** Notele aflate în sesiunea asta, după titlul TMDb (0 = fără notă), ca posterele să le arate pe loc. */
    private val known = Collections.synchronizedMap(
        object : LinkedHashMap<String, Double>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX_SESSION_CACHE
        },
    )
    private val inFlight = ConcurrentHashMap<String, Deferred<Double?>>()
    private val diskCache = HashMap<String, DiskEntry>()

    @Volatile private var loaded = false
    private var saveJob: Job? = null

    private data class DiskEntry(val rating: Double, val at: Long)

    fun peek(kind: TitleKind, id: Long): Double? = known["${kind.path}:$id"]?.takeIf { it > 0 }

    private val gate = Semaphore(4)

    suspend fun rating(kind: TitleKind, id: Long): Double? {
        val key = "${kind.path}:$id"
        known[key]?.let { return it.takeIf { r -> r > 0 } }

        val existing = inFlight[key]
        if (existing != null) return existing.await()?.takeIf { r -> r > 0 }

        val deferred = CompletableDeferred<Double?>()
        val winner = inFlight.putIfAbsent(key, deferred)
        if (winner != null) return winner.await()?.takeIf { r -> r > 0 }

        return try {
            val result = withContext(Dispatchers.IO) {
                lookup(kind, id, key)?.also { known[key] = it }
            }
            deferred.complete(result)
            result?.takeIf { it > 0 }
        } catch (t: Throwable) {
            deferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    /** Nota (0 = OMDb nu are notă) sau null = nu se știe încă (fără cheie, fără rețea). */
    private suspend fun lookup(kind: TitleKind, id: Long, key: String): Double? {
        if (!tmdb.isConfigured) return null
        val cachedId = ids[key]
        val imdb = cachedId ?: gate.withPermit {
            runCatching {
                tmdb.get("${kind.path}/$id/external_ids", emptyMap(), TmdbExternalIds.serializer(), TmdbClient.LONG_TTL).imdbId
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }?.also { ids[key] = it } ?: return null
        cached(imdb)?.let { return it }
        val answer = gate.withPermit { runCatching { calendar.imdbRating(imdb) }.getOrNull() } ?: return null
        if (!answer.answered) return null
        val rating = answer.rating ?: 0.0
        putCached(imdb, rating, System.currentTimeMillis())
        return rating
    }

    /** Nota păstrată (0 = OMDb a spus că nu are notă), dacă nu e prea veche. */
    private fun cached(imdb: String): Double? {
        ensureLoaded()
        val now = System.currentTimeMillis()
        synchronized(storeLock) {
            val e = diskCache[imdb] ?: return null
            val ttl = if (e.rating > 0) HIT_TTL_MS else MISS_TTL_MS
            if (now - e.at < ttl) return e.rating
            diskCache.remove(imdb)
            return null
        }
    }

    private fun putCached(imdb: String, rating: Double, at: Long) {
        ensureLoaded()
        synchronized(storeLock) {
            diskCache[imdb] = DiskEntry(rating, at)
            saveJob?.cancel()
            saveJob = ioScope.launch {
                delay(SAVE_DEBOUNCE_MS)
                flushToDisk()
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(storeLock) {
            if (loaded) return
            // Setul IMDb descărcat de versiunea anterioară nu mai e folosit.
            runCatching { File(appContext.filesDir, "imdb_ratings.bin").delete() }
            val now = System.currentTimeMillis()
            if (diskFile.exists()) {
                runCatching {
                    diskFile.forEachLine { line ->
                        val parts = line.split('\t')
                        if (parts.size == 3) {
                            val id = parts[0]
                            val r = parts[1].toDoubleOrNull() ?: return@forEachLine
                            val at = parts[2].toLongOrNull() ?: return@forEachLine
                            val ttl = if (r > 0) HIT_TTL_MS else MISS_TTL_MS
                            if (now - at < ttl) diskCache[id] = DiskEntry(r, at)
                        }
                    }
                }
            } else {
                // Migrare o singură dată din SharedPreferences-ul vechi (omdb_ratings.xml).
                val prefs = appContext.getSharedPreferences("omdb_ratings", Context.MODE_PRIVATE)
                val all = prefs.all
                if (all.isNotEmpty()) {
                    all.forEach { (imdb, raw) ->
                        val text = raw as? String ?: return@forEach
                        val r = text.substringBefore('|').toDoubleOrNull() ?: return@forEach
                        val at = text.substringAfter('|').toLongOrNull() ?: return@forEach
                        val ttl = if (r > 0) HIT_TTL_MS else MISS_TTL_MS
                        if (now - at < ttl) diskCache[imdb] = DiskEntry(r, at)
                    }
                    flushLocked(now)
                    prefs.edit().clear().apply()
                }
            }
            loaded = true
        }
    }

    private fun flushToDisk() {
        val now = System.currentTimeMillis()
        synchronized(storeLock) { flushLocked(now) }
    }

    private fun flushLocked(now: Long) {
        val iter = diskCache.entries.iterator()
        while (iter.hasNext()) {
            val e = iter.next().value
            val ttl = if (e.rating > 0) HIT_TTL_MS else MISS_TTL_MS
            if (now - e.at >= ttl) iter.remove()
        }
        runCatching {
            diskTmp.bufferedWriter().use { out ->
                diskCache.forEach { (imdb, e) ->
                    out.append(imdb).append('\t').append(e.rating.toString()).append('\t').append(e.at.toString()).append('\n')
                }
            }
            if (!diskTmp.renameTo(diskFile)) diskTmp.copyTo(diskFile, overwrite = true)
            diskTmp.delete()
        }
    }

    private companion object {
        const val MAX_SESSION_CACHE = 1_500
        const val SAVE_DEBOUNCE_MS = 1_500L
        // Notele IMDb se schimbă încet: 3 săptămâni, ca limita zilnică OMDb să ajungă la titlurile noi.
        val HIT_TTL_MS = TimeUnit.DAYS.toMillis(21)
        val MISS_TTL_MS = TimeUnit.DAYS.toMillis(3)
    }
}
