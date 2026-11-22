package ro.ak.vodunify.core.calendar.omdb

import ro.ak.vodunify.core.calendar.model.ImdbRating
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/** Source of IMDb ratings. */
interface ImdbRatings {
    /** Null when the show is unknown, the key is wrong or the daily limit is reached. */
    suspend fun rating(imdbId: String, apiKey: String): ImdbRating?
}

@Serializable
internal data class OmdbResponse(
    @SerialName("Response") val response: String = "False",
    @SerialName("Error") val error: String? = null,
    val imdbRating: String? = null,
    val imdbVotes: String? = null,
)

@Serializable
internal data class OmdbSeasonEpisode(
    @SerialName("Episode") val episode: String? = null,
    val imdbRating: String? = null,
)

@Serializable
internal data class OmdbSeasonResponse(
    @SerialName("Response") val response: String = "False",
    @SerialName("Error") val error: String? = null,
    @SerialName("Episodes") val episodes: List<OmdbSeasonEpisode> = emptyList(),
)

/**
 * IMDb rating and votes through OMDb (free key: 1,000 requests a day).
 * Results are cached for [ttlMillis], misses included, so a week view does not
 * spend the daily quota. After an invalid key or the daily limit, it stops
 * asking until the key changes.
 */
class OmdbClient(
    private val http: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val ttlMillis: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) : ImdbRatings {

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val cache = ConcurrentHashMap<String, Pair<Long, ImdbRating?>>()
    /** Cheia oprită (cheie greșită / limita zilnică) și până când; după o oră încercăm din nou. */
    @Volatile private var blockedKey: String? = null
    @Volatile private var blockedAt: Long = 0L

    private fun blocked(apiKey: String): Boolean {
        if (apiKey != blockedKey) return false
        if (now() - blockedAt > BLOCK_MS) { blockedKey = null; return false }
        return true
    }

    private fun block(apiKey: String) { blockedKey = apiKey; blockedAt = now() }

    override suspend fun rating(imdbId: String, apiKey: String): ImdbRating? {
        if (apiKey.isBlank() || imdbId.isBlank() || blocked(apiKey)) return null
        cache[imdbId]?.let { (at, value) -> if (now() - at < ttlMillis) return value }

        val url = base.newBuilder()
            .addQueryParameter("i", imdbId)
            .addQueryParameter("apikey", apiKey)
            .build()
        val response = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (r.code == 401) {
                        block(apiKey)
                        return@withContext null
                    }
                    if (!r.isSuccessful) return@withContext null
                    json.decodeFromString(OmdbResponse.serializer(), r.body?.string().orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        if (response.response != "True") {
            val error = response.error.orEmpty()
            if (error.contains("limit", ignoreCase = true) || error.contains("key", ignoreCase = true)) {
                block(apiKey)
                return null
            }
            // Doar „nu există” e un răspuns de păstrat; erorile trecătoare („Error getting data.”) se reîncearcă.
            if (!error.contains("not found", ignoreCase = true) && !error.contains("incorrect", ignoreCase = true)) return null
        }
        val parsed = parse(response)
        cache[imdbId] = now() to parsed
        return parsed
    }

    /** True după un răspuns OMDb pentru [imdbId] (chiar fără notă); false după o eroare de rețea sau cheie. */
    fun answered(imdbId: String): Boolean = cache.containsKey(imdbId)

    private val seasonCache = ConcurrentHashMap<String, Pair<Long, Map<Int, Double>>>()

    /**
     * Notele IMDb ale episoadelor dintr-un sezon (episod → notă), cu o singură cerere pe sezon.
     * Gol când serialul nu e găsit, cheia lipsește sau limita zilnică e atinsă.
     */
    suspend fun seasonRatings(seriesImdbId: String, season: Int, apiKey: String): Map<Int, Double> {
        if (apiKey.isBlank() || seriesImdbId.isBlank() || blocked(apiKey)) return emptyMap()
        val key = "$seriesImdbId:$season"
        seasonCache[key]?.let { (at, value) -> if (now() - at < ttlMillis) return value }
        val url = base.newBuilder()
            .addQueryParameter("i", seriesImdbId)
            .addQueryParameter("Season", season.toString())
            .addQueryParameter("apikey", apiKey)
            .build()
        val response = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (r.code == 401) {
                        block(apiKey)
                        return@withContext null
                    }
                    if (!r.isSuccessful) return@withContext null
                    json.decodeFromString(OmdbSeasonResponse.serializer(), r.body?.string().orEmpty())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        if (response.response != "True") {
            val error = response.error.orEmpty()
            if (error.contains("limit", ignoreCase = true) || error.contains("key", ignoreCase = true)) block(apiKey)
            return emptyMap()
        }
        val parsed = parseSeason(response)
        seasonCache[key] = now() to parsed
        return parsed
    }

    companion object {
        internal fun parseSeason(response: OmdbSeasonResponse): Map<Int, Double> =
            response.episodes.mapNotNull { e ->
                val n = e.episode?.toIntOrNull() ?: return@mapNotNull null
                val r = e.imdbRating?.toDoubleOrNull() ?: return@mapNotNull null
                n to r
            }.toMap()

        private const val BLOCK_MS = 60 * 60 * 1000L
        const val DEFAULT_BASE_URL = "https://www.omdbapi.com/"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        internal fun parse(response: OmdbResponse): ImdbRating? {
            if (response.response != "True") return null
            val rating = response.imdbRating?.toDoubleOrNull() ?: return null
            // Unele titluri au notă dar voturile „N/A”: nota contează oricum.
            val votes = response.imdbVotes?.replace(",", "")?.toIntOrNull() ?: 0
            return ImdbRating(rating, votes)
        }
    }
}
