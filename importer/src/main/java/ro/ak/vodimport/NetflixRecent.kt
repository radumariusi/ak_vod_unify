package ro.ak.vodimport

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * „Ce ai văzut recent pe Netflix”, cerut de AK VOD Unify când AK Scrobbler a prins o vizionare
 * Netflix fără titlu (aplicația Netflix de pe TV nu arată titlul nimănui).
 *
 * Folosește sesiunea în care te-ai conectat o dată în browserul din AK Import (cookie-urile) și
 * citește prima pagină din „Activitate de vizionare”: titlul, data și minutul unde te-ai oprit.
 * Unify potrivește apoi vizionarea după zi și minut. Nu trimite nimic altundeva.
 */
class NetflixRecentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Cookie-urile se citesc pe firul principal (WebView); rețeaua pe un fir separat.
        val cookies = runCatching { CookieManager.getInstance().getCookie(NetflixRecent.BASE) }.getOrNull()
        val app = context.applicationContext
        val pending = goAsync()
        thread {
            try {
                NetflixRecent.send(app, NetflixRecent.fetch(cookies))
            } catch (e: Exception) {
                Log.w("NetflixRecent", "eșuat", e)
            } finally {
                pending.finish()
            }
        }
    }
}

object NetflixRecent {
    const val ACTION = "ro.ak.vodimport.action.NETFLIX_RECENT"
    const val BASE = "https://www.netflix.com"
    private const val PAGE_SIZE = 40
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val BUILD = Regex("\"BUILD_IDENTIFIER\":\"([^\"]+)\"")
    private val AUTH = Regex("\"authURL\":\"([^\"]+)\"")

    /** Adresele de încercat pentru „Activitate de vizionare”, în ordine. */
    fun activityUrls(build: String?, authUrl: String?, page: Int, size: Int): List<String> = buildList {
        val q = "pg=$page&pgSize=$size"
        if (authUrl != null) add("$BASE/api/shakti/mre/viewingactivity?languages=en-US&authURL=${java.net.URLEncoder.encode(authUrl, "UTF-8")}&$q")
        add("$BASE/api/shakti/mre/viewingactivity?$q")
        if (build != null) add("$BASE/api/shakti/$build/viewingactivity?$q")
    }

    /** authURL vine în pagină cu escape-uri JavaScript („\x2F”, „\u002F”). */
    fun unescape(s: String): String = Regex("""\\x([0-9A-Fa-f]{2})|\\u([0-9A-Fa-f]{4})""").replace(s) {
        val hex = it.groupValues[1].ifEmpty { it.groupValues[2] }
        hex.toInt(16).toChar().toString()
    }

    /** Rezultatul trimis către Unify: status ok / not_logged_in / error. */
    fun fetch(cookies: String?): JSONObject {
        val out = JSONObject().put("route", BuildConfig.ROUTE)
        if (cookies.isNullOrBlank() || !cookies.contains("NetflixId")) {
            return out.put("status", "not_logged_in").put("message", "Nu ești conectat la Netflix în ${appName()}")
        }
        return try {
            val (code, finalUrl, html) = get("$BASE/browse", cookies)
            if (finalUrl.contains("login", ignoreCase = true)) {
                return out.put("status", "not_logged_in").put("message", "Sesiunea Netflix a expirat în ${appName()}")
            }
            val build = BUILD.find(html)?.groupValues?.get(1)
            val auth = AUTH.find(html)?.groupValues?.get(1)?.let(::unescape)
            if (build == null && auth == null) {
                return out.put("status", "error").put("message", "Netflix /browse fără sesiune (HTTP $code)")
            }
            // Netflix a mutat API-ul: întâi forma nouă („mre” + authURL), apoi cea veche (BUILD_IDENTIFIER).
            val urls = activityUrls(build, auth, 0, PAGE_SIZE)
            var body: String? = null
            val codes = mutableListOf<Int>()
            for (u in urls) {
                val (c, _, b) = get(u, cookies)
                codes += c
                if (c in 200..299 && b.contains("viewedItems")) {
                    body = b
                    break
                }
            }
            if (body == null) {
                return out.put("status", "error").put("message", "Netflix a răspuns cu HTTP ${codes.joinToString("/")} la activitatea de vizionare")
            }
            val viewed = JSONObject(body).optJSONArray("viewedItems") ?: JSONArray()
            val items = JSONArray()
            for (i in 0 until viewed.length()) {
                val it = viewed.optJSONObject(i) ?: continue
                items.put(
                    JSONObject()
                        .put("series", it.optString("seriesTitle").ifBlank { null })
                        .put("season", it.optString("seasonDescriptor").ifBlank { null })
                        .put("episode", it.optString("episodeTitle").ifBlank { null })
                        .put("title", it.optString("title").ifBlank { it.optString("videoTitle") })
                        .put("date", it.optLong("date"))
                        .put("bookmark", it.optLong("bookmark").takeIf { b -> b > 0 })
                        .put("duration", it.optLong("duration").takeIf { d -> d > 0 })
                        .put("id", it.opt("movieID")?.toString().orEmpty()),
                )
            }
            out.put("status", "ok").put("items", items)
        } catch (e: Exception) {
            out.put("status", "error").put("message", "${e.javaClass.simpleName}: ${e.message}".take(160))
        }
    }

    fun send(context: Context, result: JSONObject) {
        val uri = Uri.parse("content://${BuildConfig.MAIN_APP_ID}.scrobble/netflix_recent")
        context.contentResolver.insert(uri, ContentValues().apply { put("payload", result.toString()) })
    }

    private fun appName() = if (BuildConfig.ROUTE == "US") "AK Import US" else "AK Import RO"

    private fun get(url: String, cookies: String): Triple<Int, String, String> {
        var current = URL(url)
        repeat(5) {
            val c = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Cookie", cookies)
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            try {
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in 300..399 && location != null) {
                    current = URL(current, location)
                    // Nu urmăm redirecționări în afara Netflix (cookie-urile rămân doar acolo).
                    if (current.host?.endsWith("netflix.com") != true) return Triple(code, current.toString(), "")
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val body = stream?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
                return Triple(code, current.toString(), body)
            } finally {
                c.disconnect()
            }
        }
        return Triple(310, current.toString(), "")
    }
}
