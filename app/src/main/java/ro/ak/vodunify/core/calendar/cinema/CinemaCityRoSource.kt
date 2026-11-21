package ro.ak.vodunify.core.calendar.cinema

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate

/**
 * Cinema City România: the public film list its own site uses
 * (`data-api-service/v1/quickbook/10107/films/until/<date>`), with every film now
 * showing and the ones already scheduled, each with its Romanian release date.
 * Titles are cut at about 40 characters; dubbed copies are separate films ("… dub").
 */
class CinemaCityRoSource(
    private val http: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val lookAheadDays: Long = 120,
) : RoCinemaSource {

    override val name: String = "Cinema City"

    override suspend fun releases(today: LocalDate): List<RoCinemaRelease> {
        val url = "${baseUrl}films/until/${today.plusDays(lookAheadDays)}?attr=&lang=ro_RO"
        val body = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Cinema City: HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
        }
        return parse(body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.cinemacity.ro/ro/data-api-service/v1/quickbook/10107/"
        private const val CUT_LENGTH = 38
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

        @Serializable
        internal data class Response(val body: Body? = null)

        @Serializable
        internal data class Body(val films: List<Film> = emptyList())

        @Serializable
        internal data class Film(
            val id: String = "",
            val name: String = "",
            val releaseYear: String? = null,
            val releaseDate: String? = null,
        )

        fun parse(body: String): List<RoCinemaRelease> =
            json.decodeFromString(Response.serializer(), body).body?.films.orEmpty().mapNotNull { f ->
                val date = f.releaseDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
                val name = f.name.trim()
                if (name.isEmpty()) return@mapNotNull null
                val year = f.releaseYear?.trim()?.toIntOrNull()
                // "Dune re-release" (2021) back in cinemas in 2026: not a premiere.
                if (name.contains("re-release", ignoreCase = true) || name.contains("relansare", ignoreCase = true)) return@mapNotNull null
                if (year != null && year < date.year - 1) return@mapNotNull null
                RoCinemaRelease(
                    title = name,
                    year = year,
                    date = date,
                    source = "Cinema City",
                    truncated = name.length >= CUT_LENGTH,
                )
            }
    }
}
