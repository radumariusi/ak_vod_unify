package ro.ak.vodunify.core.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client HTTP minimal pentru TMDb v3, cu cache în Room:
 *  - dacă există o intrare proaspătă (mai nouă decât `ttl`), o folosește fără rețea;
 *  - altfel cere de la TMDb și salvează;
 *  - dacă rețeaua pică, întoarce intrarea veche (mod offline).
 */
@Singleton
class TmdbClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val cache: TmdbCacheDao,
) {
    private val auth: TmdbAuth = TmdbAuth.from(BuildConfig.TMDB_API_KEY)

    val isConfigured: Boolean get() = auth !is TmdbAuth.Missing

    suspend fun <T> get(
        path: String,
        params: Map<String, String?>,
        serializer: KSerializer<T>,
        ttlMillis: Long = DEFAULT_TTL,
    ): T {
        val body = getRaw(path, params, ttlMillis)
        return json.decodeFromString(serializer, body)
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

            try {
                val body = fetch(buildUrl(path, clean))
                cache.put(TmdbCacheEntity(key, body, now))
                maybePrune(now)
                body
            } catch (e: IOException) {
                cached?.body ?: throw e
            }
        }

    private fun fetch(url: HttpUrl): String {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        val current = auth
        if (current is TmdbAuth.Bearer) builder.header("Authorization", "Bearer ${current.token}")
        http.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 401) throw TmdbUnauthorizedException()
            if (!resp.isSuccessful) throw TmdbHttpException(resp.code)
            return resp.body?.string() ?: throw IOException("Răspuns gol de la TMDb")
        }
    }

    private fun buildUrl(path: String, params: Map<String, String>): HttpUrl {
        val b = (BASE_URL + path.trimStart('/')).toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val current = auth
        if (current is TmdbAuth.ApiKey) b.addQueryParameter("api_key", current.key)
        return b.build()
    }

    private var lastPrune = 0L

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

        /** Cheia de cache: calea + parametrii sortați (include limba și regiunea/piața). */
        fun cacheKey(path: String, params: Map<String, String>): String =
            path.trimStart('/') + params.toSortedMap().entries.joinToString("&", prefix = "?") { "${it.key}=${it.value}" }
    }
}
