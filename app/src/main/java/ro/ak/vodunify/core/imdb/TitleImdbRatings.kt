package ro.ak.vodunify.core.imdb

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbExternalIds
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Nota IMDb a unui titlu din TMDb: id-ul IMDb din TMDb (păstrat 7 zile), nota din setul local IMDb. */
@Singleton
class TitleImdbRatings @Inject constructor(
    private val tmdb: TmdbClient,
    private val store: ImdbRatingsStore,
) {
    private val ids = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(4)

    suspend fun rating(kind: TitleKind, id: Long): Double? {
        if (!tmdb.isConfigured) return null
        val key = "${kind.path}:$id"
        val imdb = ids[key] ?: gate.withPermit {
            runCatching {
                tmdb.get("${kind.path}/$id/external_ids", emptyMap(), TmdbExternalIds.serializer(), TmdbClient.LONG_TTL).imdbId
            }.getOrNull()
        }?.also { ids[key] = it }
        return store.rating(imdb)
    }
}
