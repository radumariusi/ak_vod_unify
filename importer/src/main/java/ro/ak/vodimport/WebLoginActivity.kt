package ro.ak.vodimport

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import kotlin.concurrent.thread

/** Pagina de conectare și istoricul rulează în WebView, în sesiunea Android a aplicației gazdă. */
abstract class WebLoginActivity : ComponentActivity() {
    protected open val recentOnly: Boolean = false
    protected abstract fun saveHistory(service: String, payload: JSONObject): String

    private val main = Handler(Looper.getMainLooper())
    private lateinit var service: Service
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var requested = false
    private var generation = 0
    private var importing = false

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = if (recentOnly) "netflix" else intent.getStringExtra(EXTRA_SERVICE)
        service = Service.entries.firstOrNull { it.id == target } ?: run { finish(); return }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AkWidgets.background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(AkWidgets.surface)
        }
        header.addView(AkWidgets.heading(this, "AK VOD · ${service.label}"))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val action = if (recentOnly) "Verifică sesiunea" else "Importă istoricul"
        listOf(
            AkWidgets.button(this, action, primary = true) { startImport() },
            AkWidgets.button(this, "Conectare") { cancelImport(); web.loadUrl(service.start) },
            AkWidgets.button(this, "Închide") { finish() },
        ).forEach { button ->
            bar.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        header.addView(bar)
        val profile = if (service == Service.NETFLIX) ", alege profilul folosit pe TV" else ""
        status = AkWidgets.text(this, "Conectează-te în ${service.label}$profile și apasă „$action”.", 15f).apply {
            setTextColor(AkWidgets.muted)
        }.also(header::addView)
        root.addView(header)
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.keepClearOfSystemBars()
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = NetflixRecent.UA
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }
        val atDocumentStart = service == Service.NETFLIX && NetflixRecent.installCapture(web)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !service.trusted(request.url.toString())

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!atDocumentStart && service == Service.NETFLIX && service.trusted(url)) {
                    view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (isFinishing || isDestroyed || !requested || !service.trusted(url)) return
                if (service.isLogin(url)) {
                    cancelImport()
                    status.text = "Conectează-te și încearcă din nou."
                    return
                }
                if (!atDocumentStart && service == Service.NETFLIX) view.evaluateJavascript(Scripts.NETFLIX_CAPTURE_HOOK, null)
                val run = generation
                main.postDelayed({
                    if (!isFinishing && !isDestroyed && requested && run == generation) readHistory()
                }, if (service == Service.NETFLIX) 2_000L else 1_000L)
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!web.canGoBack()) { finish(); return }
                cancelImport()
                web.goBack()
            }
        })
        web.loadUrl(service.start)
        bar.getChildAt(0).requestFocus()
    }

    private fun startImport() {
        if (importing || requested) return
        val run = ++generation
        requested = true
        status.text = "Deschid activitatea de vizionare din ${service.label}…"
        web.loadUrl(service.history)
        main.postDelayed({
            if (requested && run == generation) {
                cancelImport()
                status.text = "Pagina nu a răspuns. Verifică sesiunea și încearcă din nou."
            }
        }, 35_000L)
    }

    private fun readHistory() {
        if (!service.trusted(web.url.orEmpty())) return
        requested = false
        importing = true
        val run = generation
        status.text = "Citesc istoricul din ${service.label}…"
        if (recentOnly) {
            collectRecent(run, System.currentTimeMillis() + 35_000)
        } else {
            web.evaluateJavascript(
                """
                window.__akImport = { count: 0 };
                window.AK = {
                  progress: count => { window.__akImport.count = count; },
                  fail: error => { window.__akImport.error = error; },
                  done: payload => { window.__akImport.payload = JSON.parse(payload); }
                };
                ${service.script}
                """.trimIndent(), null,
            )
            collectImport(run, System.currentTimeMillis() + 360_000)
        }
    }

    private fun collectImport(run: Int, deadline: Long) {
        if (!canCollect(run, deadline)) return
        web.evaluateJavascript("JSON.stringify(window.__akImport)") { raw ->
            if (generation != run) return@evaluateJavascript
            val result = NetflixRecent.parse(raw)
            val payload = result?.optJSONObject("payload")
            when {
                payload != null -> save(run, payload)
                result?.has("error") == true -> {
                    importing = false
                    status.text = result.optString("error").take(400)
                }
                else -> {
                    val count = result?.optInt("count") ?: 0
                    status.text = "Citesc istoricul din ${service.label}… ($count)"
                    main.postDelayed({ collectImport(run, deadline) }, 1_500)
                }
            }
        }
    }

    private fun collectRecent(run: Int, deadline: Long) {
        if (!canCollect(run, deadline)) return
        web.evaluateJavascript(Scripts.NETFLIX_HISTORY_COLLECT) { raw ->
            if (generation != run) return@evaluateJavascript
            val result = NetflixRecent.parse(raw)
            if ((result?.optJSONArray("items")?.length() ?: 0) > 0 && result != null) {
                save(run, result)
            } else {
                main.postDelayed({ collectRecent(run, deadline) }, 1_500)
            }
        }
    }

    private fun canCollect(run: Int, deadline: Long): Boolean {
        if (generation != run) return false
        if (System.currentTimeMillis() < deadline && service.trusted(web.url.orEmpty())) return true
        cancelImport()
        status.text = "Istoricul nu a răspuns. Verifică profilul și încearcă din nou."
        return false
    }

    private fun save(run: Int, payload: JSONObject) {
        thread {
            val message = runCatching { saveHistory(service.id, payload) }.getOrElse { "Eroare: ${it.message}" }
            main.post {
                if (!isFinishing && !isDestroyed && generation == run) {
                    importing = false
                    status.text = message
                }
            }
        }
    }

    private fun cancelImport() {
        generation++
        requested = false
        importing = false
        main.removeCallbacksAndMessages(null)
    }

    override fun onPause() {
        if (::web.isInitialized) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        cancelImport()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object { const val EXTRA_SERVICE = "service" }
}
