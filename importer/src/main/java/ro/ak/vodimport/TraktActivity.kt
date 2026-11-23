package ro.ak.vodimport

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Importul din Trakt: te conectezi cu un cod (trakt.tv/activate, de pe telefon), apoi aducem
 * tot istoricul în AK VOD Unify. Ce există și în istoricul Netflix păstrăm de acolo (AK VOD Unify
 * scoate dublurile).
 */
class TraktActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var codeText: TextView
    private lateinit var importButton: Button
    private lateinit var connectButton: Button
    private lateinit var logoutButton: Button
    @Volatile private var busy = false
    @Volatile private var closed = false

    private val prefs get() = getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(14, 17, 22))
        }
        column.addView(text("Trakt", 26f, bold = true))
        column.addView(
            text(
                "Aducem tot istoricul din contul Trakt (filme și episoade, cu data). " +
                    "Ce ai văzut pe Netflix îl luăm din istoricul Netflix, nu din Trakt.",
                15f,
            ),
        )
        codeText = text("", 22f, bold = true).also(column::addView)
        status = text("", 15f).also(column::addView)
        importButton = button("Importă tot istoricul din Trakt") { startImport() }.also(column::addView)
        connectButton = button("Conectează contul Trakt") { connect() }.also(column::addView)
        logoutButton = button("Deconectează contul Trakt") {
            prefs.edit().remove(KEY_TOKEN).remove(KEY_EXPIRES).apply()
            refresh()
        }.also(column::addView)
        setContentView(ScrollView(this).apply { addView(column); keepClearOfSystemBars() })
        refresh()
    }

    override fun onDestroy() {
        closed = true
        super.onDestroy()
    }

    private fun token(): String? {
        val t = prefs.getString(KEY_TOKEN, null) ?: return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        return t.takeIf { expires == 0L || expires - System.currentTimeMillis() > 60_000 }
    }

    private fun refresh() {
        val connected = token() != null
        if (!TraktImport.isConfigured) {
            status.text = "Lipsește cheia Trakt (TRAKT_CLIENT_ID) din această versiune."
        } else if (!busy) {
            status.text = if (connected) "✅ Cont Trakt conectat." else "Contul Trakt nu e conectat."
        }
        importButton.visibility = if (connected) Button.VISIBLE else Button.GONE
        logoutButton.visibility = if (connected) Button.VISIBLE else Button.GONE
        connectButton.visibility = if (!connected && TraktImport.isConfigured) Button.VISIBLE else Button.GONE
        (if (connected) importButton else connectButton).requestFocus()
    }

    private fun connect() {
        if (busy) return
        busy = true
        status.text = "Cer un cod de la Trakt…"
        thread {
            val code = runCatching { TraktImport.requestCode() }.getOrElse { e ->
                ui {
                    busy = false
                    status.text = "❌ ${e.message}"
                }
                return@thread
            }
            ui {
                codeText.text = "Cod: ${code.userCode}"
                status.text = "Pe telefon, deschide ${code.url} și scrie codul de mai sus. Aștept aprobarea…"
            }
            var interval = code.interval * 1000L
            val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
            while (!closed && System.currentTimeMillis() < deadline) {
                Thread.sleep(interval)
                when (val p = runCatching { TraktImport.poll(code.deviceCode) }.getOrElse { TraktImport.Poll.Pending }) {
                    is TraktImport.Poll.Approved -> {
                        prefs.edit().putString(KEY_TOKEN, p.accessToken).putLong(KEY_EXPIRES, p.expiresAt).apply()
                        ui {
                            codeText.text = ""
                            busy = false
                            refresh()
                            startImport()
                        }
                        return@thread
                    }
                    TraktImport.Poll.Pending -> Unit
                    TraktImport.Poll.SlowDown -> interval += 1000
                    is TraktImport.Poll.Failed -> {
                        ui {
                            codeText.text = ""
                            busy = false
                            status.text = "❌ ${p.message}"
                            refresh()
                        }
                        return@thread
                    }
                }
            }
            ui {
                codeText.text = ""
                busy = false
                if (!closed) status.text = "❌ Codul a expirat. Încearcă din nou."
            }
        }
    }

    private fun startImport() {
        val token = token() ?: return refresh()
        if (busy) return
        busy = true
        status.text = "Citesc istoricul din Trakt…"
        thread {
            val result = runCatching {
                val items = TraktImport.history(token) { page, pages -> ui { status.text = "Citesc istoricul din Trakt… pagina $page din $pages" } }
                ui { status.text = "Trimit ${items.length()} vizionări în AK VOD Unify…" }
                Sender.send(this, TraktImport.SERVICE, "", items)
            }
            val message = result.fold(
                onSuccess = { r ->
                    when (r) {
                        is Sender.Result.Sent -> "${r.count} vizionări trimise în AK VOD Unify"
                        is Sender.Result.Failed -> "eroare: ${r.message}"
                    }
                },
                onFailure = { "eroare: ${it.message}" },
            )
            prefs.edit().putString("last_${TraktImport.SERVICE}", "${System.currentTimeMillis()}|$message").apply()
            ui {
                busy = false
                status.text = if (message.startsWith("eroare")) "❌ $message" else "✅ $message. Le găsești în AK VOD Unify → Istoric."
            }
        }
    }

    private fun ui(block: () -> Unit) {
        handler.post { if (!isFinishing && !isDestroyed) block() }
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

    private companion object {
        const val KEY_TOKEN = "trakt_token"
        const val KEY_EXPIRES = "trakt_expires"
    }
}
