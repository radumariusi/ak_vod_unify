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
    internal const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun hasSession(): Boolean = runCatching {
        CookieManager.getInstance().getCookie(BASE).orEmpty().split(';').any { it.trimStart().startsWith("NetflixId=") }
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
            fun poll() {
                if (finished || !Service.NETFLIX.trusted(web.url.orEmpty())) return
                web.evaluateJavascript(Scripts.NETFLIX_HISTORY_COLLECT) { raw ->
                    if (finished) return@evaluateJavascript
                    val parsed = parse(raw)
                    val items = parsed?.optJSONArray("items")
                    lastDiag = parsed?.optString("diag") ?: "răspuns gol din pagină"
                    if (items != null && items.length() > 0) {
                        val bounded = JSONArray()
                        for (i in 0 until minOf(items.length(), 200)) bounded.put(items.get(i))
                        finish(result("ok", items = bounded))
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
                    if (!atDocumentStart && Service.NETFLIX.trusted(url)) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!Service.NETFLIX.trusted(url) || finished) return
                    if (Service.NETFLIX.isLogin(url)) {
                        finish(result("not_logged_in", "Sesiunea Netflix a expirat."))
                        return
                    }
                    if (!atDocumentStart) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                    val page = ++generation
                    main.postDelayed({ if (page == generation) poll() }, 2_000L)
                }
            }
            main.postDelayed({ finish(result("error", "Activitate de vizionare: ${lastDiag.take(180)}")) }, 40_000L)
            web.loadUrl(HISTORY_URL)
        }
    }
}
