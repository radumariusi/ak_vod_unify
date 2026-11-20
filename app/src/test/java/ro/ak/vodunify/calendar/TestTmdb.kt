package ro.ak.vodunify.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.core.calendar.tmdb.TmdbCalendarApi
import ro.ak.vodunify.core.calendar.tmdb.TmdbFetch

private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

/** The calendar's TMDb API over a mock server, sending the key as `api_key` like TmdbClient does for v3 keys. */
fun testTmdbApi(baseUrl: String, key: String) = TmdbCalendarApi(
    object : TmdbFetch {
        override suspend fun <T> get(path: String, params: Map<String, String?>, serializer: KSerializer<T>, ttlMillis: Long): T {
            val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
                addQueryParameter("api_key", key)
            }.build()
            return withContext(Dispatchers.IO) {
                OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    json.decodeFromString(serializer, r.body!!.string())
                }
            }
        }
    },
)
