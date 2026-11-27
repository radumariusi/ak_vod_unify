package ro.ak.vodunify.core.tmdb

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.net.readResponse
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client HTTP minimal pentru TMDb v3, cu cache în Room:
 *  - dacă există o intrare proaspătă (mai nouă decât `ttl`), o folosește fără rețea;
 *  - cererile identice concurente se deduplifică pe cheie (fără duplicate spre TMDb);
 *  - la limitare de rată (429) sau erori trecătoare de server (502–504) reîncearcă scurt cu backoff;
 *  - dacă rețeaua pică, întoarce intrarea veche (mod offline).
 */
@Singleton
class TmdbClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val cache: TmdbCacheDao,
) {
    @Volatile private var auth: TmdbAuth = TmdbAuth.from(BuildConfig.TMDB_API_KEY)
    @Volatile private var baseUrl: String = BASE_URL

    private class InFlightEntry(val waiters: AtomicInteger = AtomicInteger(0)) {
        lateinit var job: Deferred<String>
    }

    private sealed interface FetchOutcome {
        data class Ok(val body: String) : FetchOutcome
        data class Retry(val waitMs: Long) : FetchOutcome
    }

    private val inFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, InFlightEntry>()

    @VisibleForTesting
    internal fun overrideForTest(baseUrl: String, apiKey: String) {
        this.baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        this.auth = TmdbAuth.from(apiKey)
    }

    val isConfigured: Boolean get() = auth !is TmdbAuth.Missing

    suspend fun <T> get(
        path: String,
        params: Map<String, String?>,
        serializer: KSerializer<T>,
        ttlMillis: Long = DEFAULT_TTL,
    ): T {
        val body = getRaw(path, params, ttlMillis)
        // Decodarea JSON (unele răspunsuri au zeci de KB) nu are voie pe firul interfeței: altfel derularea sacadează.
        return withContext(Dispatchers.Default) { json.decodeFromString(serializer, body) }
    }

    /** Corpul JSON brut (din cache sau rețea), pentru cine își decodează singur DTO-urile (ex. Calendarul). */
    suspend fun getRaw(path: String, params: Map<String, String?>, ttlMillis: Long = DEFAULT_TTL): String =
        withContext(Dispatchers.IO) {
            if (auth is TmdbAuth.Missing) throw TmdbNotConfiguredException()
            val clean = params.filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
            val key = cacheKey(path, clean)
            val now = System.currentTimeMillis()
            val cached = cache.get(key)
            if (cached != null && now - cached.fetchedAt < ttlMillis) return@withContext cached.body

            val entry = synchronized(inFlight) {
                val existing = inFlight[key]?.takeIf { !it.job.isCancelled }
                if (existing != null) {
                    existing.waiters.incrementAndGet()
                    existing
                } else {
                    val created = InFlightEntry()
                    created.waiters.incrementAndGet()
                    created.job = inFlightScope.async(start = CoroutineStart.LAZY) {
                        try {
                            val fetchedAt = System.currentTimeMillis()
                            val body = fetchWithRetry(buildUrl(path, clean))
                            cache.put(TmdbCacheEntity(key, body, fetchedAt))
                            maybePrune(fetchedAt)
                            body
                        } catch (e: IOException) {
                            cache.get(key)?.body ?: throw e
                        } finally {
                            synchronized(inFlight) { inFlight.remove(key, created) }
                        }
                    }
                    inFlight[key] = created
                    created
                }
            }
            entry.job.start()
            try {
                entry.job.await()
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (entry.waiters.decrementAndGet() <= 0 && !entry.job.isCompleted) {
                    synchronized(inFlight) { inFlight.remove(key, entry) }
                    entry.job.cancel()
                }
            }
        }

    private suspend fun fetchWithRetry(url: HttpUrl): String {
        for (attempt in 0 until MAX_ATTEMPTS) {
            val outcome = fetchOnce(url, attempt)
            when (outcome) {
                is FetchOutcome.Ok -> return outcome.body
                is FetchOutcome.Retry -> delay(outcome.waitMs)
            }
        }
        throw TmdbHttpException(429)
    }

    private suspend fun fetchOnce(url: HttpUrl, attempt: Int): FetchOutcome {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        val current = auth
        if (current is TmdbAuth.Bearer) builder.header("Authorization", "Bearer ${current.token}")
        return http.newCall(builder.build()).readResponse { response ->
            val code = response.code
            if (code == 401) throw TmdbUnauthorizedException()
            val retryable = code == 429 || code in RETRYABLE_HTTP_CODES
            if (retryable && attempt < MAX_ATTEMPTS - 1) {
                val retryAfterSec = response.header("Retry-After")?.trim()?.toLongOrNull()
                val waitMs = when {
                    retryAfterSec != null -> (retryAfterSec * 1_000L).coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
                    code == 429 -> 1_000L * (attempt + 1)
                    else -> 500L * (attempt + 1)
                }
                return@readResponse FetchOutcome.Retry(waitMs)
            }
            if (!response.isSuccessful) throw TmdbHttpException(code)
            val body = response.body?.string() ?: throw IOException("Răspuns gol de la TMDb")
            FetchOutcome.Ok(body)
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>): HttpUrl {
        val b = (baseUrl + path.trimStart('/')).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val current = auth
        if (current is TmdbAuth.ApiKey) b.addQueryParameter("api_key", current.key)
        return b.build()
    }

    @Volatile private var lastPrune = 0L

    private suspend fun maybePrune(now: Long) {
        if (now - lastPrune < TimeUnit.HOURS.toMillis(6)) return
        lastPrune = now
        cache.prune(now - TimeUnit.DAYS.toMillis(30))
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) { cache.clear() }

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3/"
        val DEFAULT_TTL: Long = TimeUnit.HOURS.toMillis(6)
        val LONG_TTL: Long = TimeUnit.DAYS.toMillis(7)
        private const val MAX_ATTEMPTS = 3
        private const val MIN_RETRY_MS = 250L
        private const val MAX_RETRY_MS = 5_000L
        private val RETRYABLE_HTTP_CODES = setOf(502, 503, 504)

        /** Cheia de cache: calea + parametrii sortați (include limba și regiunea/piața). */
        fun cacheKey(path: String, params: Map<String, String>): String =
            path.trimStart('/') + params.toSortedMap().entries.joinToString("&", prefix = "?") { "${it.key}=${it.value}" }
    }
}
