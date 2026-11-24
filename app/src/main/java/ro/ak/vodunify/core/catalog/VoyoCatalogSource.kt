package ro.ak.vodunify.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Public VOYO listings and structured offer metadata from WhatToWatch.ro. */
@Singleton
class VoyoCatalogSource @Inject constructor(
    http: OkHttpClient,
    private val cache: TmdbCacheDao,
    private val json: Json,
) {
    private val client = http.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val detailRequests = Semaphore(6)
    private val idsSerializer = ListSerializer(Long.serializer())

    suspend fun ids(kind: TitleKind, page: Int): List<Long> = withContext(Dispatchers.IO) {
        val listPath = if (kind == TitleKind.MOVIE) "/platforma/voyo/filme" else "/platforma/voyo/seriale"
        val key = "whattowatch:voyo:RO:${kind.path}:$page"
        val now = System.currentTimeMillis()
        cache.get(key)?.takeIf { now - it.fetchedAt < CACHE_MILLIS }?.let {
            return@withContext json.decodeFromString(idsSerializer, it.body)
        }
        // The site redirects the first-page query to the canonical address.
        val pagePath = if (page == 1) listPath else "$listPath?page=$page"
        val links = VoyoPageMetadata.titlePaths(fetch(pagePath), kind, json)
        val parsed = links.map { path ->
            async {
                try {
                    Result.success(detailRequests.withPermit { VoyoPageMetadata.availableId(fetch(path), kind, json) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Result.failure(e)
                }
            }
        }.awaitAll()
        val ids = parsed.mapNotNull { it.getOrNull() }.distinct()
        if (ids.isEmpty() && parsed.any { it.isFailure }) {
            throw IOException("WhatToWatch.ro: ofertele VOYO nu au putut fi verificate")
        }
        if (parsed.all { it.isSuccess }) {
            cache.put(TmdbCacheEntity(key, json.encodeToString(idsSerializer, ids), now))
        }
        ids
    }

    private fun fetch(path: String): String {
        val url = HOST.resolve(path) ?: throw IOException("Adresă de catalog VOYO invalidă")
        val request = Request.Builder().url(url).header("Accept", "text/html")
            .header("User-Agent", "AK VOD Unify (streaming catalogue)").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("WhatToWatch.ro: HTTP ${response.code}")
            response.body?.string() ?: throw IOException("WhatToWatch.ro: răspuns gol")
        }
    }

    private companion object {
        val HOST = "https://whattowatch.ro/".toHttpUrl()
        val CACHE_MILLIS = TimeUnit.HOURS.toMillis(6)
    }
}

internal object VoyoPageMetadata {
    private val jsonLd = Regex(
        """<script\b[^>]*\btype\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun titlePaths(html: String, kind: TitleKind, json: Json): List<String> {
        val prefix = if (kind == TitleKind.MOVIE) "/film/" else "/serial/"
        val entries = objects(html, json).firstOrNull { it.text("@type") == "ItemList" }
            ?.get("itemListElement") as? JsonArray ?: throw IOException("WhatToWatch.ro: lipsește lista VOYO")
        val paths = entries.mapNotNull { entry ->
            val url = (entry as? JsonObject)?.text("url")?.toHttpUrlOrNull() ?: return@mapNotNull null
            url.encodedPath.takeIf {
                url.isHttps && url.host == "whattowatch.ro" && it.startsWith(prefix) && url.pathSegments.size == 2
            }
        }.distinct()
        if (entries.isNotEmpty() && paths.isEmpty()) throw IOException("WhatToWatch.ro: lista VOYO are un format necunoscut")
        return paths
    }

    fun availableId(html: String, kind: TitleKind, json: Json, now: Instant = Instant.now()): Long? {
        val type = if (kind == TitleKind.MOVIE) "Movie" else "TVSeries"
        val title = objects(html, json).firstOrNull { it.text("@type") == type }
            ?: throw IOException("WhatToWatch.ro: lipsesc datele titlului")
        val hasVoyo = (title["potentialAction"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().any { action ->
            val provider = action["provider"] as? JsonObject
            val access = action["actionAccessibilityRequirement"] as? JsonObject ?: return@any false
            val region = access["eligibleRegion"] as? JsonObject
            action.text("@type") == "WatchAction" &&
                provider?.text("name")?.equals("VOYO", ignoreCase = true) == true &&
                region?.text("name") == "RO" &&
                access.text("category") in setOf("subscription", "free", "ads") &&
                access.instant("availabilityStarts")?.isAfter(now) != true &&
                access.instant("availabilityEnds")?.isAfter(now) != false
        }
        if (!hasVoyo) return null
        return (title["sameAs"] as? JsonArray).orEmpty().firstNotNullOfOrNull { link ->
            val url = (link as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() ?: return@firstNotNullOfOrNull null
            val (path, id) = url.pathSegments
                .takeIf { it.size >= 2 && url.host in setOf("www.themoviedb.org", "themoviedb.org") }
                ?: return@firstNotNullOfOrNull null
            id.substringBefore('-').toLongOrNull()?.takeIf { it > 0 && path == kind.path }
        }
    }

    private fun objects(html: String, json: Json): Sequence<JsonObject> = jsonLd.findAll(html).mapNotNull { match ->
        runCatching { json.parseToJsonElement(match.groupValues[1]) as? JsonObject }.getOrNull()
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.instant(key: String): Instant? = text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }
}
