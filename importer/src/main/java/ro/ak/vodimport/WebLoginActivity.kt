package ro.ak.vodimport

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Browserul din aplicație pentru un serviciu: te conectezi normal (parola o introduci doar pe
 * site-ul serviciului; noi nu o vedem și nu o salvăm), apoi „Importă istoricul” rulează în pagină
 * scriptul care citește istoricul și îl trimite în AK VOD Unify.
 */
class WebLoginActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var service: Service
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var pendingImport = false

    /** Test Netflix: ascultăm ce primește pagina „Activitate de vizionare”. */
    private var captureMode = false
    private var captureToken = 0

    /** Adresa curentă, ca puntea să răspundă doar paginilor serviciului. */
    @Volatile private var currentUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_SERVICE)
        service = Services.all(BuildConfig.ROUTE).firstOrNull { it.id == id && it.supported } ?: run {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 17, 22))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val importButton = button("Importă istoricul") { startImport() }
        bar.addView(importButton)
        bar.addView(button("Pagina de conectare") { web.loadUrl(service.startUrl ?: return@button) })
        bar.addView(button("Închide") { finish() })
        root.addView(bar)
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(16, 4, 16, 4)
            text = "Conectează-te în ${service.name}" +
                (if (service.id == "netflix") " și alege profilul" else "") +
                ", apoi apasă „Importă istoricul”."
        }
        root.addView(status)

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Site-urile servesc pagina de istoric doar browserelor „de desktop”.
            userAgentString = DESKTOP_UA
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        web.addJavascriptInterface(Bridge(), "AK")
        web.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // Navigare internă (fără încărcare nouă): ținem adresa la zi pentru punte.
                currentUrl = url
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (captureMode && isTrusted(url)) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                if (captureMode && isTrusted(url) && !isLoginPage(url)) {
                    view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                    scheduleCaptureReport()
                    return
                }
                if (pendingImport && isHistoryPage(url)) {
                    runScript()
                } else if (pendingImport && isLoginPage(url)) {
                    pendingImport = false
                    status.text = "Nu ești conectat încă. Conectează-te, apoi apasă din nou „Importă istoricul”."
                }
            }
        }
        web.loadUrl(service.startUrl ?: return)
        importButton.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    /** Test Netflix: deschidem „Activitate de vizionare” cu ascultarea pornită, apoi raportăm. */
    private fun startNetflixCapture() {
        captureMode = true
        captureToken++
        status.text = "TEST Netflix: deschid „Activitate de vizionare” și ascult ce primește pagina (~15 s)…"
        web.loadUrl("https://www.netflix.com/viewingactivity")
    }

    private fun scheduleCaptureReport() {
        val token = ++captureToken
        handler.postDelayed({
            if (token == captureToken && captureMode) web.evaluateJavascript(Scripts.NETFLIX_SHOW_MORE, null)
        }, 6_000L)
        handler.postDelayed({
            if (token != captureToken || !captureMode) return@postDelayed
            web.evaluateJavascript(Scripts.NETFLIX_CAPTURE_REPORT) { raw ->
                captureMode = false
                status.text = "TEST2 Netflix · " + describeCapture(raw)
            }
        }, 12_000L)
    }

    private fun describeCapture(raw: String?): String = runCatching {
        val json = JSONObject(org.json.JSONTokener(raw).nextValue() as String)
        val cap = json.optJSONArray("cap") ?: org.json.JSONArray()
        val reqs = (0 until cap.length()).joinToString(" | ") { i ->
            val c = cap.getJSONObject(i)
            "${c.optString("u")} ${c.optInt("s")} ${c.optInt("n") / 1024}KB chei:${c.optString("k").replace("\"", "")} ⟨${c.optString("x").take(120)}⟩"
        }
        val rows = json.optJSONArray("rows")
        val sample = (0 until (rows?.length() ?: 0)).joinToString(" | ") { rows!!.getString(it).take(70) }
        "pagina ${json.optString("url")} · ascultare: ${json.optBoolean("hook")} · cereri prinse: ${cap.length()} " +
            (if (reqs.isNotEmpty()) "[$reqs] " else "") + "· rânduri cu dată: ${json.optInt("total")}" + (if (sample.isNotEmpty()) " · ex: $sample" else "")
    }.getOrElse { "răspuns neașteptat: ${raw?.take(200)}" }

    private fun startImport() {
        if (service.id == "netflix") {
            startNetflixCapture()
            return
        }
        pendingImport = true
        // Netflix: scriptul merge de pe orice pagină netflix.com pe care ești conectat, deci nu
        // mai depindem de adresa paginii de istoric (Netflix a mutat-o la /settings/viewed/…).
        val url = currentUrl
        if (service.runAnywhere && url != null && trustedPage() && !isLoginPage(url)) {
            runScript()
            return
        }
        status.text = "Deschid pagina de istoric…"
        web.loadUrl(service.historyUrl ?: return)
        // Plasă de siguranță: dacă pagina se încarcă fără „gata” (site-uri cu navigare internă),
        // pornim oricum după câteva secunde.
        handler.postDelayed({
            val now = currentUrl ?: web.url
            if (pendingImport && now != null && isTrusted(now) && !isLoginPage(now)) runScript()
        }, FALLBACK_DELAY_MS)
    }

    private fun runScript() {
        if (!pendingImport) return
        pendingImport = false
        status.text = "Citesc istoricul din ${service.name}…"
        val started = System.currentTimeMillis()
        web.evaluateJavascript(service.script ?: return, null)
        handler.postDelayed({
            if (lastBridgeAt < started) {
                status.text = "⚠️ Pagina nu a răspuns. Reîncarcă pagina (Înapoi, apoi din nou) și apasă „Importă istoricul”. " +
                    "Adresa curentă: ${currentUrl.orEmpty().take(80)}"
            }
        }, WATCHDOG_MS)
    }

    /** Ultima dată când scriptul a răspuns prin punte. */
    @Volatile private var lastBridgeAt = 0L

    private fun isHistoryPage(url: String): Boolean {
        if (service.runAnywhere) return isTrusted(url) && !isLoginPage(url)
        val target = Uri.parse(service.historyUrl ?: return false)
        val u = Uri.parse(url)
        return u.host == target.host && (u.path ?: "").trimEnd('/').startsWith((target.path ?: "").trimEnd('/'))
    }

    private fun isTrusted(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return service.hosts.any { host == it || host.endsWith(".$it") }
    }

    private fun isLoginPage(url: String): Boolean = url.contains("login", ignoreCase = true) || url.contains("signin", ignoreCase = true)

    private fun trustedPage(): Boolean {
        val url = currentUrl ?: return false
        return isTrusted(url)
    }

    private fun saveStatus(message: String) {
        getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_${service.id}", "${System.currentTimeMillis()}|$message")
            .apply()
    }

    /** Puntea apelată din scriptul din pagină. */
    private inner class Bridge {
        @JavascriptInterface
        fun progress(count: Int) {
            if (!trustedPage()) return
            lastBridgeAt = System.currentTimeMillis()
            handler.post { status.text = "Citesc istoricul din ${service.name}… ($count)" }
        }

        @JavascriptInterface
        fun fail(message: String) {
            if (!trustedPage()) return
            lastBridgeAt = System.currentTimeMillis()
            handler.post { status.text = "⚠️ $message" }
        }

        @JavascriptInterface
        fun done(payload: String) {
            if (!trustedPage()) return
            lastBridgeAt = System.currentTimeMillis()
            thread {
                val result = runCatching {
                    val json = JSONObject(payload)
                    val items = json.optJSONArray("items") ?: org.json.JSONArray()
                    val profile = json.optString("profile")
                    items.length() to Sender.send(this@WebLoginActivity, service.id, profile, items)
                }
                handler.post {
                    result.onSuccess { (count, sent) ->
                        when (sent) {
                            is Sender.Result.Sent -> {
                                val msg = "$count titluri trimise în AK VOD Unify"
                                saveStatus(msg)
                                status.text = "✅ $msg. Le găsește în TMDb în fundal (vezi Setări → Istoric din servicii)."
                            }
                            is Sender.Result.Failed -> status.text = "⚠️ ${sent.message}"
                        }
                    }.onFailure { status.text = "⚠️ Răspuns neașteptat: ${it.message}" }
                }
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    companion object {
        const val EXTRA_SERVICE = "service"
        private const val FALLBACK_DELAY_MS = 8_000L
        private const val WATCHDOG_MS = 15_000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
