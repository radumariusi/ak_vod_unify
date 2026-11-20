package ro.ak.vodscrobbler

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singurul ecran: dacă scrobblerul are acces, ce vede acum de la fiecare aplicație
 * și ce a trimis recent către AK VOD Unify. Construit fără XML ca aplicația să rămână mică.
 * Merge cu telecomanda (D-pad) și cu touch.
 */
class StatusActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var adb: TextView
    private lateinit var live: TextView
    private lateinit var sent: TextView
    private lateinit var accessButton: Button
    private lateinit var batteryButton: Button

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.rgb(14, 17, 22))
        }
        column.addView(text("AK Scrobbler", 26f, bold = true))
        column.addView(
            text(
                "Vede ce redă orice aplicație (Netflix, Prime Video, SkyShowtime, HBO Max, Disney+, Stremio…) " +
                    "și trimite titlul și minutul în AK VOD Unify. Nu folosește internetul, " +
                    "deci nu trebuie pus în VPN.",
                15f,
            ),
        )
        status = text("", 16f).also(column::addView)

        accessButton = button("Dă acces (setarea „Acces la notificări”)") { openListenerSettings() }
        column.addView(accessButton)
        batteryButton = button("Nu opri scrobblerul pentru economie de baterie") { askBatteryExemption() }
        column.addView(batteryButton)
        column.addView(button("Deschide AK VOD Unify") { openMainApp() })

        adb = text("", 14f).also { it.setTextIsSelectable(true) }.also(column::addView)

        column.addView(text("Ce văd acum", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
        live = focusableText().also(column::addView)
        column.addView(text("Trimise recent către AK VOD Unify", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
        sent = focusableText().also(column::addView)

        setContentView(ScrollView(this).apply { addView(column) })
        accessButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    @SuppressLint("SetTextI18n")
    private fun render() {
        val enabled = Access.isListenerEnabled(this)
        val connected = ScrobblerState.listenerConnected
        val main = Access.isMainAppInstalled(this)
        val battery = Access.isIgnoringBatteryOptimizations(this)
        val pending = Forwarder.get(this).pendingCount()
        status.text = buildString {
            append(if (enabled) "✅ Acces la sesiunile media: DA" else "❌ Acces la sesiunile media: NU (vezi mai jos)")
            if (enabled && !connected) append("\n⏳ Accesul e dat, aștept ca Android să pornească ascultătorul…")
            append(if (main) "\n✅ AK VOD Unify instalat" else "\n❌ AK VOD Unify nu e instalat (pachet ${BuildConfig.MAIN_APP_ID})")
            append(if (battery) "\n✅ Scutit de economia de baterie" else "\nℹ️ Nu e scutit de economia de baterie (opțional)")
            if (pending > 0) append("\n⏳ $pending evenimente așteaptă să ajungă în AK VOD Unify")
        }
        accessButton.visibility = if (enabled) View.GONE else View.VISIBLE
        batteryButton.visibility = if (battery) View.GONE else View.VISIBLE
        adb.visibility = if (enabled) View.GONE else View.VISIBLE
        adb.text = "Dacă televizorul nu are setarea „Acces la notificări”, accesul se dă o singură dată " +
            "prin ADB (de pe telefon, cu o aplicație ADB prin Wi-Fi), cu comanda:\n\n${Access.adbCommand(this)}\n\n" +
            "Accesul rămâne și după restart."

        val sessions = ScrobblerState.live
        live.text = when {
            !connected -> "Nimic încă: ascultătorul nu rulează."
            sessions.isEmpty() -> "Nicio aplicație nu redă acum. Pornește un episod în Netflix (sau altă aplicație) și revino aici."
            else -> sessions.joinToString("\n\n") { s ->
                buildString {
                    append("▶ ${s.appLabel} (${s.packageName})\n")
                    append("Stare: ${s.state.label()}")
                    append(" · Poziție: ${s.positionMs?.let(::clock) ?: "necunoscută"}")
                    append(" / ${s.media?.durationMs?.let(::clock) ?: "durată necunoscută"}\n")
                    val fields = s.media?.fields.orEmpty()
                    if (fields.isEmpty()) {
                        append("Aplicația nu trimite titlul.")
                    } else {
                        fields.forEach { (k, v) -> append("  $k: $v\n") }
                    }
                }.trimEnd()
            }
        }

        val log = Forwarder.get(this).recent()
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        sent.text = if (log.isEmpty()) {
            "Nimic trimis încă."
        } else {
            log.joinToString("\n") { e ->
                "${fmt.format(Date(e.at))} · ${e.appLabel} · ${e.title} · ${clock(e.positionMs)} · ${e.reason.label()} → ${e.result.resultLabel()}"
            }
        }
    }

    private fun openListenerSettings() {
        val component = Access.component(this)
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()),
                )
            }
            add(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: ActivityNotFoundException) {
                // încercăm următoarea variantă
            } catch (_: SecurityException) {
                // unele televizoare blochează ecranul
            }
        }
        adb.text = "Televizorul nu are ecranul „Acces la notificări”. Folosește comanda ADB:\n\n${Access.adbCommand(this)}"
        adb.requestFocus()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                // Pe multe televizoare nu există; nu e obligatoriu.
            }
        }
    }

    private fun openMainApp() {
        val pm = packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(BuildConfig.MAIN_APP_ID)
            ?: pm.getLaunchIntentForPackage(BuildConfig.MAIN_APP_ID)
        if (intent != null) startActivity(intent)
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    /** Text care poate primi focus, ca ecranul să se poată derula cu telecomanda. */
    private fun focusableText() = text("", 14f).apply {
        isFocusable = true
        setOnFocusChangeListener { v, has -> v.setBackgroundColor(if (has) Color.rgb(40, 44, 52) else Color.TRANSPARENT) }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun PlayState.label() = when (this) {
        PlayState.PLAYING -> "redă"
        PlayState.PAUSED -> "pauză"
        PlayState.BUFFERING -> "se încarcă"
        PlayState.STOPPED -> "oprit"
        PlayState.OTHER -> "necunoscută"
    }

    private fun String.label() = when (this) {
        "pause" -> "pauză"
        "stop" -> "oprit"
        "tick" -> "în redare"
        else -> this
    }

    private fun String.resultLabel() = when (this) {
        "saved" -> "salvat minutul"
        "watched" -> "marcat văzut"
        "unmatched" -> "titlu negăsit în TMDb"
        "ignored" -> "ignorat (nu e film/serial)"
        "disabled" -> "oprit din AK VOD Unify"
        "too_short" -> "prea scurt"
        else -> this
    }

    companion object {
        fun clock(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
