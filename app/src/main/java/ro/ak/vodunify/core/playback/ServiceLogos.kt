package ro.ak.vodunify.core.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbProviderList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Siglele pătrate ale serviciilor de streaming (Netflix „N”, Prime Video, HBO Max…), cele din TMDb / JustWatch:
 * pătrate, ca o iconiță de aplicație, nu cu tot numele. Lista furnizorilor (RO + US, filme + seriale) se
 * păstrează 7 zile; imaginile le încarcă Coil.
 */
@Singleton
class ServiceLogos @Inject constructor(private val tmdb: TmdbClient) {
    private val lock = Mutex()
    @Volatile private var logos: Map<Int, String>? = null
    @Volatile private var failedAt = 0L

    /** Sigla deja știută (fără așteptare), ca eticheta s-o arate din primul cadru. */
    fun peekPackage(packageName: String): String? = logos?.let { map -> serviceOf(packageName)?.let { s -> pick(map, s) } }

    fun peekService(service: StreamingService): String? = logos?.let { pick(it, service) }

    private fun serviceOf(packageName: String): StreamingService? {
        val app = TargetApp.byPackage(packageName) ?: return null
        return StreamingService.entries.firstOrNull { it.appId == app.id }
    }

    private fun pick(map: Map<Int, String>, service: StreamingService): String? =
        service.tmdbProviderIds.firstNotNullOfOrNull { map[it] }

    /** Sigla pătrată a aplicației [packageName], dacă e aplicația unui serviciu de streaming; null pentru playere. */
    suspend fun forPackage(packageName: String): String? {
        val service = serviceOf(packageName) ?: return null
        return forService(service)
    }

    suspend fun forService(service: StreamingService): String? = pick(load(), service)

    private suspend fun load(): Map<Int, String> {
        logos?.let { return it }
        return lock.withLock {
            logos?.let { return@withLock it }
            if (!tmdb.isConfigured || System.currentTimeMillis() - failedAt < RETRY_MS) return@withLock emptyMap()
            val map = HashMap<Int, String>()
            var ok = false
            for (kind in listOf("movie", "tv")) for (region in listOf("RO", "US")) {
                runCatching {
                    tmdb.get("watch/providers/$kind", mapOf("watch_region" to region), TmdbProviderList.serializer(), TmdbClient.LONG_TTL)
                }.onSuccess { list ->
                    ok = true
                    list.results.forEach { p -> p.logoPath?.let { map.putIfAbsent(p.providerId, IMAGE_BASE + it) } }
                }
            }
            if (ok) logos = map else failedAt = System.currentTimeMillis()
            map
        }
    }

    private companion object {
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/w92"
        const val RETRY_MS = 5 * 60_000L
    }
}
