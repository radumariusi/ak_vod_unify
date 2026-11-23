package ro.ak.vodimport

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Lista serviciilor: te conectezi și imporți istoricul. Arată și pe ce rută iese aplicația
 * (țara IP-ului), ca să verifici că AK Import US e în tunelul VPN US.
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var routeText: TextView
    private lateinit var mainText: TextView
    private val statusViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(14, 17, 22))
        }
        column.addView(text(getString(R.string.app_name), 26f, bold = true))
        column.addView(
            text(
                if (BuildConfig.ROUTE == "US") {
                    "Pentru conturile americane. Pune ACEASTĂ aplicație în tunelul VPN US (o singură dată) și las-o acolo."
                } else {
                    "Pentru conturile din România. Merge direct, fără VPN."
                } + " Istoricul ajunge în AK VOD Unify.",
                15f,
            ),
        )
        routeText = text("Verific pe ce rută ies pe internet…", 15f).also(column::addView)
        column.addView(button("Verifică din nou ruta") { checkRoute() })
        mainText = text("", 15f).also(column::addView)

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var first: Button? = null
        for (s in Services.all(BuildConfig.ROUTE)) {
            column.addView(text(s.name + if (s.experimental) " (experimental)" else "", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
            column.addView(text(s.note, 14f))
            if (s.supported) {
                val status = text(statusLine(prefs.getString("last_${s.id}", null)), 14f).also(column::addView)
                statusViews[s.id] = status
                val b = button("Conectează-te și importă din ${s.name}") { open(s) }
                column.addView(b)
                if (first == null) first = b
            }
        }
        // Trakt: un singur cont, fără VPN, deci doar în AK Import RO.
        if (BuildConfig.ROUTE == "RO") {
            column.addView(text("Trakt", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
            column.addView(text("Tot istoricul din contul Trakt, în afară de ce ai văzut pe Netflix (acela vine din istoricul Netflix).", 14f))
            statusViews[TraktImport.SERVICE] = text(statusLine(prefs.getString("last_${TraktImport.SERVICE}", null)), 14f).also(column::addView)
            column.addView(button("Conectează-te și importă din Trakt") { startActivity(Intent(this, TraktActivity::class.java)) })
        }
        setContentView(ScrollView(this).apply { addView(column) })
        first?.requestFocus()
        checkRoute()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        statusViews.forEach { (id, view) -> view.text = statusLine(prefs.getString("last_$id", null)) }
        mainText.text = if (isMainInstalled()) "✅ AK VOD Unify instalat" else "❌ AK VOD Unify nu e instalat (${BuildConfig.MAIN_APP_ID})"
    }

    private fun open(s: Service) {
        startActivity(Intent(this, WebLoginActivity::class.java).putExtra(WebLoginActivity.EXTRA_SERVICE, s.id))
    }

    @SuppressLint("SetTextI18n")
    private fun checkRoute() {
        routeText.text = "Verific pe ce rută ies pe internet…"
        thread {
            // Cloudflare întoarce țara de unde vine cererea (fără cont, fără date despre tine).
            val country = runCatching {
                val c = URL("https://www.cloudflare.com/cdn-cgi/trace").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.inputStream.bufferedReader().use { r -> r.readLines().firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=") }
            }.getOrNull()
            handler.post {
                routeText.text = when {
                    country == null -> "⚠️ Nu pot verifica ruta (fără internet?)"
                    country == BuildConfig.ROUTE -> "✅ Ies pe internet din $country, cum trebuie pentru ${getString(R.string.app_name)}."
                    BuildConfig.ROUTE == "US" -> "❌ Ies din $country, nu din US. Adaugă ${getString(R.string.app_name)} în tunelul VPN US."
                    else -> "⚠️ Ies din $country. Pentru conturile RO, scoate ${getString(R.string.app_name)} din VPN."
                }
            }
        }
    }

    private fun isMainInstalled(): Boolean = try {
        packageManager.getPackageInfo(BuildConfig.MAIN_APP_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun statusLine(raw: String?): String {
        if (raw.isNullOrBlank()) return "Neimportat încă."
        val (at, msg) = raw.split('|', limit = 2).let { (it.getOrNull(0)?.toLongOrNull() ?: 0L) to it.getOrElse(1) { "" } }
        return "Ultimul import: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(at))} · $msg"
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "importer"
    }
}
