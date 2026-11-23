package ro.ak.vodimport

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Istoricul din contul Trakt, prin API-ul oficial (api.trakt.tv):
 *  1. conectare cu cod de dispozitiv: scrii codul pe trakt.tv/activate (de pe telefon);
 *  2. citim tot /sync/history, pagină cu pagină;
 *  3. îl trimitem în AK VOD Unify cu codurile TMDb, deci fără căutare după nume.
 *
 * Aplicațiile Trakt noi nu mai primesc „client secret”: conectarea pe dispozitiv merge doar cu Client ID.
 */
object TraktImport {
    const val SERVICE = "trakt_import"
    private const val BASE = "https://api.trakt.tv/"
    private const val PAGE_LIMIT = 250

    val isConfigured: Boolean get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank()

    class Http(val code: Int, val body: String, val pageCount: Int)

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    sealed interface Poll {
        data class Approved(val accessToken: String, val expiresAt: Long) : Poll
        data object Pending : Poll
        data object SlowDown : Poll
        data class Failed(val message: String) : Poll
    }

    fun requestCode(): DeviceCode {
        val r = request("oauth/device/code", JSONObject().put("client_id", BuildConfig.TRAKT_CLIENT_ID), token = null)
        if (r.code != 200) throw IOException("Trakt a răspuns ${r.code}: ${r.body.take(200)}")
        val o = JSONObject(r.body)
        return DeviceCode(
            deviceCode = o.getString("device_code"),
            userCode = o.getString("user_code"),
            url = o.optString("verification_url", "https://trakt.tv/activate"),
            expiresIn = o.optInt("expires_in", 600),
            interval = o.optInt("interval", 5).coerceAtLeast(1),
        )
    }

    fun poll(deviceCode: String): Poll {
        val body = JSONObject().put("code", deviceCode).put("client_id", BuildConfig.TRAKT_CLIENT_ID)
        val r = request("oauth/device/token", body, token = null)
        return when (r.code) {
            200 -> {
                val o = JSONObject(r.body)
                val created = o.optLong("created_at", System.currentTimeMillis() / 1000)
                Poll.Approved(o.getString("access_token"), (created + o.optLong("expires_in", 0)) * 1000)
            }
            400 -> Poll.Pending
            429 -> Poll.SlowDown
            404 -> Poll.Failed("Cod invalid. Încearcă din nou.")
            409 -> Poll.Failed("Codul a fost deja folosit. Încearcă din nou.")
            410 -> Poll.Failed("Codul a expirat. Încearcă din nou.")
            418 -> Poll.Failed("Ai refuzat accesul pe Trakt.")
            else -> Poll.Failed("Trakt a răspuns ${r.code}: ${r.body.take(200)}")
        }
    }

    /** Tot istoricul, ca elemente pentru AK VOD Unify. [progress] primește (pagina, din câte). */
    fun history(token: String, progress: (Int, Int) -> Unit): JSONArray {
        val out = JSONArray()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val r = request("sync/history?page=$page&limit=$PAGE_LIMIT", null, token)
            if (r.code == 401) throw IOException("Sesiunea Trakt a expirat. Conectează-te din nou.")
            if (r.code !in 200..299) throw IOException("Trakt a răspuns ${r.code} la pagina $page: ${r.body.take(200)}")
            pages = r.pageCount.coerceAtLeast(1)
            progress(page, pages)
            val arr = JSONArray(r.body)
            for (i in 0 until arr.length()) toItem(arr.optJSONObject(i) ?: continue)?.let(out::put)
            page++
        }
        return out
    }

    /** Un rând din /sync/history → elementul de import (ImportItem din AK VOD Unify). */
    private fun toItem(h: JSONObject): JSONObject? {
        val date = runCatching { Instant.parse(h.optString("watched_at")).toEpochMilli() }.getOrNull() ?: return null
        return when (h.optString("type")) {
            "movie" -> {
                val m = h.optJSONObject("movie") ?: return null
                JSONObject().apply {
                    put("kind", "movie")
                    put("title", m.optString("title"))
                    put("date", date)
                    putIds(m.optJSONObject("ids"))
                    if (m.optInt("year") > 0) put("year", m.optInt("year"))
                }
            }
            "episode" -> {
                val show = h.optJSONObject("show") ?: return null
                val e = h.optJSONObject("episode") ?: return null
                val episodeTitle = e.optString("title").takeIf { it.isNotBlank() && it != "null" }
                JSONObject().apply {
                    put("kind", "episode")
                    put("series", show.optString("title"))
                    put("title", episodeTitle ?: "")
                    episodeTitle?.let { put("episode", it) }
                    put("seasonNumber", e.optInt("season"))
                    put("episodeNumber", e.optInt("number"))
                    put("date", date)
                    putIds(show.optJSONObject("ids"))
                    if (show.optInt("year") > 0) put("year", show.optInt("year"))
                }
            }
            else -> null
        }
    }

    private fun JSONObject.putIds(ids: JSONObject?) {
        if (ids == null) return
        val tmdb = ids.optLong("tmdb")
        if (tmdb > 0) put("tmdb", tmdb)
        ids.optString("imdb").takeIf { it.startsWith("tt") }?.let { put("imdb", it) }
    }

    private fun request(path: String, body: JSONObject?, token: String?): Http {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("trakt-api-version", "2")
            c.setRequestProperty("trakt-api-key", BuildConfig.TRAKT_CLIENT_ID)
            c.setRequestProperty("User-Agent", "AKImport/${BuildConfig.VERSION_NAME}")
            token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Http(code, text, c.getHeaderField("X-Pagination-Page-Count")?.toIntOrNull() ?: 1)
        } finally {
            c.disconnect()
        }
    }
}
