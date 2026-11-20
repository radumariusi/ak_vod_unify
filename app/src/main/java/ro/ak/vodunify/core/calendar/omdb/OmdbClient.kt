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
    @Volatile private var blockedKey: String? = null

    override suspend fun rating(imdbId: String, apiKey: String): ImdbRating? {
        if (apiKey.isBlank() || imdbId.isBlank() || apiKey == blockedKey) return null
        cache[imdbId]?.let { (at, value) -> if (now() - at < ttlMillis) return value }

        val url = base.newBuilder()
            .addQueryParameter("i", imdbId)
            .addQueryParameter("apikey", apiKey)
            .build()
        val response = try {
            withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    if (r.code == 401) {
                        blockedKey = apiKey
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
                blockedKey = apiKey
                return null
            }
        }
        val parsed = parse(response)
        cache[imdbId] = now() to parsed
        return parsed
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.omdbapi.com/"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        internal fun parse(response: OmdbResponse): ImdbRating? {
            if (response.response != "True") return null
            val rating = response.imdbRating?.toDoubleOrNull() ?: return null
            val votes = response.imdbVotes?.replace(",", "")?.toIntOrNull() ?: return null
            return ImdbRating(rating, votes)
        }
    }
}
