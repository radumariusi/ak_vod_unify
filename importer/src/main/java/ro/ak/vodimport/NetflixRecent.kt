package ro.ak.vodimport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.concurrent.thread

/**
 * „Ce ai văzut recent pe Netflix”, cerut de AK VOD Unify când AK Scrobbler a prins o vizionare
 * Netflix fără titlu (aplicația Netflix de pe TV nu arată titlul nimănui).
 *
 * Folosește sesiunea în care te-ai conectat o dată în browserul din AK Import (cookie-urile):
 * deschide invizibil pagina „Watched” și ia din ce primește ea titlul și ora exactă a fiecărei
 * vizionări. Unify potrivește apoi vizionarea după oră. Nu trimite nimic altundeva.
 */
class NetflixRecentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        val cookies = runCatching { CookieManager.getInstance().getCookie(NetflixRecent.BASE) }.getOrNull()
        if (cookies.isNullOrBlank() || !cookies.contains("NetflixId")) {
            val out = JSONObject().put("route", BuildConfig.ROUTE).put("status", "not_logged_in")
                .put("message", "Nu ești conectat la Netflix în ${NetflixRecent.appName()}")
            thread { sendAndFinish(app, out, pending) }
            return
        }
        NetflixHistoryScan.run(app) { result -> thread { sendAndFinish(app, result, pending) } }
    }

    private fun sendAndFinish(app: Context, result: JSONObject, pending: PendingResult) {
        try {
            NetflixRecent.send(app, result)
        } catch (e: Exception) {
            Log.w("NetflixRecent", "eșuat", e)
        } finally {
            pending.finish()
        }
    }
}

/**
 * Încarcă pagina „Watched” într-un WebView invizibil, cu [Scripts.NETFLIX_CAPTURE_HOOK] pornit, și
 * citește cu [Scripts.NETFLIX_HISTORY_COLLECT] ce a primit pagina. Rezultatul e gata de trimis în
 * Unify (status ok / not_logged_in / error).
 */
object NetflixHistoryScan {
    private const val POLL_MS = 2_000L
    private const val TIMEOUT_MS = 40_000L

    @SuppressLint("SetJavaScriptEnabled")
    fun run(context: Context, done: (JSONObject) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val base = JSONObject().put("route", BuildConfig.ROUTE)
        val web = try {
            WebView(context)
        } catch (e: Exception) {
            done(base.put("status", "error").put("message", "WebView indisponibil: ${e.message}"))
            return
        }
        var finished = false
        var lastDiag = "pagina nu s-a încărcat"
        fun finish(result: JSONObject) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            runCatching { web.stopLoading(); web.destroy() }
            done(result)
        }
        fun poll() {
            if (finished) return
            web.evaluateJavascript(Scripts.NETFLIX_HISTORY_COLLECT) { raw ->
                val parsed = NetflixRecent.parseCollect(raw)
                val items = parsed?.optJSONArray("items")
                lastDiag = parsed?.optString("diag") ?: "răspuns gol din pagină"
                if (items != null && items.length() > 0) {
                    finish(JSONObject().put("route", BuildConfig.ROUTE).put("status", "ok").put("items", items))
                } else {
                    main.postDelayed({ poll() }, POLL_MS)
                }
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = NetflixRecent.UA
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (NetflixRecent.isNetflix(url)) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (finished) return
                if (url.contains("login", ignoreCase = true)) {
                    finish(JSONObject().put("route", BuildConfig.ROUTE).put("status", "not_logged_in")
                        .put("message", "Sesiunea Netflix a expirat în ${NetflixRecent.appName()}"))
                    return
                }
                if (!NetflixRecent.isNetflix(url)) return
                view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                main.postDelayed({ poll() }, POLL_MS)
            }
        }
        main.postDelayed({
            finish(JSONObject().put("route", BuildConfig.ROUTE).put("status", "error")
                .put("message", "pagina Watched: $lastDiag".take(300)))
        }, TIMEOUT_MS)
        web.loadUrl(NetflixRecent.HISTORY_URL)
    }
}

object NetflixRecent {
    const val ACTION = "ro.ak.vodimport.action.NETFLIX_RECENT"
    const val BASE = "https://www.netflix.com"
    const val HISTORY_URL = "$BASE/viewingactivity"
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun isNetflix(url: String): Boolean =
        runCatching { Uri.parse(url).host?.let { it == "netflix.com" || it.endsWith(".netflix.com") } }.getOrNull() == true

    /** evaluateJavascript întoarce rezultatul ca șir JSON (cu ghilimele). */
    fun parseCollect(raw: String?): JSONObject? =
        runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()

    fun send(context: Context, result: JSONObject) {
        val uri = Uri.parse("content://${BuildConfig.MAIN_APP_ID}.scrobble/netflix_recent")
        context.contentResolver.insert(uri, ContentValues().apply { put("payload", result.toString()) })
    }

    fun appName() = if (BuildConfig.ROUTE == "US") "AK Import US" else "AK Import RO"
}
