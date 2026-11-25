package ro.ak.vodimport

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

object NetflixRecent {
    const val ACTION = "ro.ak.vodscrobbler.action.NETFLIX_RECENT"
    const val BASE = "https://www.netflix.com"
    const val HISTORY_URL = "$BASE/viewingactivity"
    const val BROWSE_URL = "$BASE/browse"
    internal const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun hasSession(): Boolean = runCatching {
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie(BASE).orEmpty()
        val c2 = cm.getCookie("https://netflix.com").orEmpty()
        "$c1;$c2".split(';').any { it.trimStart().startsWith("NetflixId=") }
    }.getOrDefault(false)

    fun result(status: String, message: String? = null, items: JSONArray? = null): JSONObject =
        JSONObject().put("route", "RO").put("status", status).apply {
            message?.let { put("message", it) }
            items?.let { put("items", it) }
        }

    internal fun parse(raw: String?): JSONObject? =
        runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()

    internal fun installCapture(web: WebView): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        WebViewCompat.addDocumentStartJavaScript(web, Scripts.NETFLIX_CAPTURE_HOOK, setOf("https://netflix.com", "https://*.netflix.com"))
        return true
    }

    /** Citește doar vizionările recente, folosind sesiunea din aplicația apelantă. */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun scan(context: Context): JSONObject = withContext(Dispatchers.Main) {
        if (!hasSession()) return@withContext result("not_logged_in", "Conectează-te la Netflix și alege profilul folosit la vizionare.")
        val web = try {
            WebView(context)
        } catch (e: Exception) {
            return@withContext result("error", "WebView indisponibil: ${e.message}")
        }
        suspendCancellableCoroutine { continuation ->
            val main = Handler(Looper.getMainLooper())
            var finished = false
            var stage = 0 // 0 = viewingactivity, 1 = browse (Continue Watching bookmarks)
            var historyItems: JSONArray? = null
            var lastDiag = "pagina nu s-a încărcat"
            fun close() {
                if (finished) return
                finished = true
                main.removeCallbacksAndMessages(null)
                web.stopLoading()
                web.destroy()
            }
            fun finish(value: JSONObject) {
                if (finished) return
                close()
                if (continuation.isActive) continuation.resume(value)
            }
            fun completeMerged(browseItems: JSONArray?) {
                val merged = mergeItems(historyItems, browseItems)
                if (merged.length() > 0) {
                    finish(result("ok", items = merged))
                } else {
                    finish(result("error", "Activitate de vizionare: ${lastDiag.take(180)}"))
                }
            }
            fun pollBrowse(triesLeft: Int) {
                if (finished || !Service.NETFLIX.trusted(web.url.orEmpty())) return
                web.evaluateJavascript(Scripts.NETFLIX_BROWSE_COLLECT) { raw ->
                    if (finished) return@evaluateJavascript
                    val parsed = parse(raw)
                    val bItems = parsed?.optJSONArray("items")
                    if ((bItems != null && bItems.length() > 0) || triesLeft <= 0) {
                        completeMerged(bItems)
                    } else {
                        main.postDelayed({ pollBrowse(triesLeft - 1) }, 1_500L)
                    }
                }
            }
            fun startBrowseStage() {
                if (finished || stage == 1) return
                stage = 1
                web.loadUrl(BROWSE_URL)
            }
            fun poll() {
                if (finished || stage != 0 || !Service.NETFLIX.trusted(web.url.orEmpty())) return
                web.evaluateJavascript(Scripts.NETFLIX_HISTORY_COLLECT) { raw ->
                    if (finished || stage != 0) return@evaluateJavascript
                    val parsed = parse(raw)
                    val items = parsed?.optJSONArray("items")
                    lastDiag = parsed?.optString("diag") ?: "răspuns gol din pagină"
                    if (items != null && items.length() > 0) {
                        historyItems = items
                        startBrowseStage()
                    } else {
                        main.postDelayed({ poll() }, 2_000L)
                    }
                }
            }
            continuation.invokeOnCancellation { main.post { close() } }
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = UA
                allowFileAccess = false
                allowContentAccess = false
            }
            val atDocumentStart = installCapture(web)
            var generation = 0
            web.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    generation++
                    if (stage == 0 && !atDocumentStart && Service.NETFLIX.trusted(url)) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!Service.NETFLIX.trusted(url) || finished) return
                    if (Service.NETFLIX.isLogin(url)) {
                        if (historyItems != null) completeMerged(null)
                        else finish(result("not_logged_in", "Sesiunea Netflix a expirat."))
                        return
                    }
                    if (stage == 0) {
                        if (!atDocumentStart) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                        val page = ++generation
                        main.postDelayed({ if (page == generation && stage == 0) poll() }, 2_000L)
                    } else {
                        val page = ++generation
                        main.postDelayed({ if (page == generation && stage == 1) pollBrowse(3) }, 1_800L)
                    }
                }
            }
            // Dacă /viewingactivity nu a prins nimic în 20s, încercăm măcar `/browse` înainte de timeout.
            main.postDelayed({ if (stage == 0 && historyItems == null) startBrowseStage() }, 20_000L)
            main.postDelayed({ completeMerged(null) }, 38_000L)
            web.loadUrl(HISTORY_URL)
        }
    }

    /** Combină istoricul (`/viewingactivity`) cu pozițiile exacte în secunde (`/browse`). */
    internal fun mergeItems(history: JSONArray?, browse: JSONArray?, maxItems: Int = 200): JSONArray {
        val byId = LinkedHashMap<String, JSONObject>()
        val extra = mutableListOf<JSONObject>()
        if (history != null) {
            for (i in 0 until history.length()) {
                val obj = history.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotBlank()) byId.putIfAbsent(id, obj) else extra += obj
            }
        }
        if (browse != null) {
            for (i in 0 until browse.length()) {
                val b = browse.optJSONObject(i) ?: continue
                val id = b.optString("id")
                val existing = if (id.isNotBlank()) byId[id] else null
                if (existing != null) {
                    val copy = JSONObject(existing.toString())
                    if (b.optLong("bookmark", 0) > 0) {
                        copy.put("bookmark", b.optLong("bookmark"))
                        if (b.optLong("duration", 0) > 0) copy.put("duration", b.optLong("duration"))
                    }
                    val bAt = b.optLong("watchedAt", 0)
                    val eAt = copy.optLong("watchedAt", 0)
                    if (bAt > eAt) copy.put("watchedAt", bAt)
                    byId[id] = copy
                } else if (id.isNotBlank()) {
                    byId[id] = b
                } else {
                    extra += b
                }
            }
        }
        val out = JSONArray()
        for (obj in (byId.values + extra).sortedByDescending { it.optLong("watchedAt", it.optLong("date", 0)) }.take(maxItems)) {
            out.put(obj)
        }
        return out
    }
}
