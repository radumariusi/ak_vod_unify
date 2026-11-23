package ro.ak.vodscrobbler

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import ro.ak.vodimport.AkWidgets
import ro.ak.vodimport.keepClearOfSystemBars
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
    private lateinit var diag: TextView
    private lateinit var screenButton: Button
    private lateinit var accessButton: Button
    private lateinit var batteryButton: Button
    private lateinit var overlayButton: Button
    private lateinit var dotButton: Button
    private lateinit var dotSettings: LinearLayout
    private lateinit var sizeButton: Button
    private lateinit var cornerButton: Button
    private lateinit var alphaButton: Button
    private val colorButtons = mutableMapOf<Prefs.DotState, Button>()

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
            setBackgroundColor(AkWidgets.background)
        }
        column.addView(AkWidgets.heading(this, "AK Scrobbler"))
        column.addView(
            text(
                "Vede ce redă orice aplicație (Netflix, Prime Video, SkyShowtime, HBO Max, Disney+, Stremio…) " +
                    "și trimite titlul și minutul în AK VOD Unify. Activitatea Netflix ajută la identificarea titlurilor lipsă.",
                15f,
            ),
        )
        status = text("", 16f).also(column::addView)

        accessButton = button("Dă acces (setarea „Acces la notificări”)") { openListenerSettings() }
        column.addView(accessButton)
        screenButton = button("Pornește citirea titlului de pe ecran (Setări → Accesibilitate)") { openAccessibilitySettings() }
        column.addView(screenButton)
        batteryButton = button("Nu opri scrobblerul pentru economie de baterie") { askBatteryExemption() }
        column.addView(batteryButton)
        overlayButton = button("Permite bulina din dreapta sus (peste alte aplicații)") { openOverlaySettings() }
        column.addView(overlayButton)
        dotButton = button("") {
            Prefs.setDotEnabled(this, !Prefs.dotEnabled(this))
            render()
        }
        column.addView(dotButton)
        // Aspectul bulinei: fiecare apăsare trece la următoarea variantă.
        dotSettings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        Prefs.DotState.entries.forEach { state ->
            colorButtons[state] = button("") {
                Prefs.nextDotColor(this, state)
                render()
            }.also(dotSettings::addView)
        }
        sizeButton = button("") {
            Prefs.nextDotSize(this)
            render()
        }.also(dotSettings::addView)
        cornerButton = button("") {
            Prefs.nextDotCorner(this)
            render()
        }.also(dotSettings::addView)
        alphaButton = button("") {
            Prefs.nextDotAlpha(this)
            render()
        }.also(dotSettings::addView)
        column.addView(dotSettings)
        column.addView(button("Conectează Netflix pentru identificare") {
            startActivity(Intent(this, NetflixLoginActivity::class.java))
        })
        column.addView(button("Deschide AK VOD Unify") { openMainApp() })

        adb = text("", 14f).also { it.setTextIsSelectable(true) }.also(column::addView)

        column.addView(text("Ce văd acum", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
        live = focusableText().also(column::addView)
        column.addView(text("Trimise recent către AK VOD Unify", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
        sent = focusableText().also(column::addView)

        column.addView(text("Jurnal de diagnostic (cel mai nou sus)", 20f, bold = true).also { it.setPadding(0, dp(16), 0, 0) })
        column.addView(
            text(
                "Tot ce vede scrobblerul: sesiuni, texte de pe ecran, ce trimite și de ce NU trimite. " +
                    "După un test, apasă „Salvează jurnalul” și trimite-mi fișierul (sau fă poze).",
                14f,
            ),
        )
        column.addView(button("Salvează jurnalul în Download") { saveLog() })
        column.addView(button("Trimite jurnalul (alege aplicația)") { shareLog() })
        column.addView(button("Șterge jurnalul") {
            DiagLog.clear()
            render()
        })
        diag = focusableText().also { it.textSize = 12f }.also(column::addView)

        setContentView(ScrollView(this).apply { addView(column); keepClearOfSystemBars() })
        accessButton.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        // Din prim-plan pornirea serviciului e mereu permisă.
        KeepAliveService.start(this)
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
        val overlay = Access.canDrawOverlays(this)
        val screenOn = Access.isScreenReaderEnabled(this)
        status.text = buildString {
            append(if (enabled) "✅ Acces la sesiunile media: DA" else "❌ Acces la sesiunile media: NU (vezi mai jos)")
            if (enabled && !connected) append("\n⏳ Accesul e dat, aștept ca Android să pornească ascultătorul…")
            append(
                when {
                    screenOn && ScreenState.serviceConnected -> "\n✅ Citește titlul și minutul de pe ecran (accesibilitate)"
                    screenOn -> "\n⏳ Citirea ecranului e pornită, aștept ca Android s-o lege…"
                    else -> "\n❌ Citirea titlului de pe ecran: OPRITĂ (Netflix, Apple TV nu trimit titlul altfel)"
                },
            )
            append(
                if (LogcatProbe.isAllowed(this@StatusActivity)) {
                    "\n✅ Citește codul titlului din jurnalul sistemului (pentru Netflix)"
                } else {
                    "\n❌ Codul titlului din jurnalul sistemului (Netflix): fără acces, vezi comanda ADB de mai jos"
                },
            )
            append(if (main) "\n✅ AK VOD Unify instalat" else "\n❌ AK VOD Unify nu e instalat (pachet ${BuildConfig.MAIN_APP_ID})")
            append(if (battery) "\n✅ Scutit de economia de baterie" else "\nℹ️ Nu e scutit de economia de baterie (opțional)")
            append(if (ScrobblerState.keepAliveRunning) "\n✅ Rulează permanent (serviciu în prim-plan)" else "\n⚠️ Serviciul permanent nu rulează încă")
            append(
                when {
                    !overlay -> "\nℹ️ Bulina nu are voie să apară peste aplicații"
                    Prefs.dotEnabled(this@StatusActivity) -> "\n✅ Bulina e afișată: " +
                        Prefs.DotState.entries.joinToString(", ") { st ->
                            "${Prefs.dotColor(this@StatusActivity, st).name.lowercase()} = ${st.label}"
                        }
                    else -> "\nℹ️ Bulina e ascunsă"
                },
            )
            if (pending > 0) append("\n⏳ $pending evenimente așteaptă să ajungă în AK VOD Unify")
        }
        overlayButton.visibility = if (overlay) View.GONE else View.VISIBLE
        dotButton.visibility = if (overlay) View.VISIBLE else View.GONE
        dotButton.text = if (Prefs.dotEnabled(this)) "Ascunde bulina" else "Arată bulina"
        dotSettings.visibility = if (overlay && Prefs.dotEnabled(this)) View.VISIBLE else View.GONE
        colorButtons.forEach { (st, b) -> b.text = "Culoare când ${st.label}: ${Prefs.dotColor(this, st).name}" }
        val (sizeDp, sizeName) = Prefs.SIZES[Prefs.dotSizeIndex(this)]
        sizeButton.text = "Mărimea bulinei: $sizeName ($sizeDp dp)"
        cornerButton.text = "Locul bulinei: ${Prefs.dotCorner(this).label}"
        alphaButton.text = "Vizibilitatea bulinei: ${Prefs.dotAlpha(this)}%"
        accessButton.visibility = if (enabled) View.GONE else View.VISIBLE
        screenButton.visibility = if (screenOn) View.GONE else View.VISIBLE
        batteryButton.visibility = if (battery) View.GONE else View.VISIBLE
        val listenerHelp = if (enabled) {
            ""
        } else {
            "Dacă televizorul nu are setarea „Acces la notificări”, accesul se dă o singură dată " +
                "prin ADB (de pe telefon, cu o aplicație ADB prin Wi-Fi), cu comanda:\n\n${Access.adbCommand(this)}\n\n" +
                "Accesul rămâne și după restart.\n\n"
        }
        val screenHelp = if (screenOn) {
            ""
        } else {
            "Citirea titlului de pe ecran se pornește din Setări → Accesibilitate → AK Scrobbler. " +
                "Dacă televizorul nu are setarea sau o blochează, prin ADB (o singură dată, rămâne după restart):\n\n" +
                Access.adbScreenReaderCommands(this).joinToString("\n") + "\n\n"
        }
        val logcatHelp = if (LogcatProbe.isAllowed(this)) {
            ""
        } else {
            "Netflix nu spune ce redă. Ca AK Scrobbler să afle codul titlului din jurnalul sistemului, " +
                "dă-i voie o singură dată prin ADB (în Bugjaeger scrie fără „adb shell”):\n\n" +
                LogcatProbe.adbCommand(this) + "\n\napoi închide și redeschide AK Scrobbler.\n\n"
        }
        adb.text = listenerHelp + screenHelp + logcatHelp + "Pe televizoarele care închid aplicațiile (ex. TCL), tot prin ADB, o singură dată:\n" +
            "• bulina: ${Access.adbOverlayCommand(this)}\n" +
            "• fără economie de baterie: ${Access.adbBatteryCommand(this)}"

        val sessions = ScrobblerState.live
        live.text = when {
            !connected -> "Nimic încă: ascultătorul nu rulează."
            sessions.isEmpty() -> "Nicio aplicație nu redă acum. Pornește un episod în Netflix (sau altă aplicație) și revino aici."
            else -> sessions.joinToString("\n\n") { s ->
                buildString {
                    append("▶ ${s.appLabel} (${s.packageName})\n")
                    append("Stare: ${s.state.label()}")
                    append(
                        " · Poziție: " + (
                            s.positionMs?.let(::clock)
                                ?: if (s.playedMs > 0) "necunoscută (redat de noi: ${clock(s.playedMs)})" else "necunoscută"
                            ),
                    )
                    append(" / ${s.media?.durationMs?.let(::clock) ?: "durată necunoscută"}\n")
                    val fields = s.media?.fields.orEmpty()
                    if (fields.isEmpty()) {
                        append(
                            if (screenOn) {
                                "Aplicația nu trimite titlul; îl caut pe ecran (pune pauză o clipă ca să apară)."
                            } else {
                                "Aplicația nu trimite titlul. Pornește citirea de pe ecran (mai sus)."
                            },
                        )
                    } else {
                        fields.forEach { (k, v) -> append("  $k: $v\n") }
                    }
                }.trimEnd()
            }
        }

        diag.text = DiagLog.newestFirst(DIAG_LINES).joinToString("\n").ifEmpty { "Gol." }

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

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            adb.text = "Televizorul nu are ecranul de accesibilitate. Pornește citirea prin ADB:\n\n" +
                Access.adbScreenReaderCommands(this).joinToString("\n")
            adb.requestFocus()
        }
    }

    private fun saveLog() {
        val msg = try {
            "✅ Jurnal salvat: ${LogExport.saveToDownloads(this)}\n(în Bugjaeger: cat \"$(ls -t /sdcard/Download/ak-scrobbler-jurnal* | head -1)\")"
        } catch (e: Exception) {
            "❌ Nu am putut salva jurnalul: ${e.message}. Folosește „Trimite jurnalul” sau fă poze."
        }
        DiagLog.add(msg.lines().first())
        render()
        diag.requestFocus()
    }

    private fun shareLog() {
        try {
            startActivity(LogExport.shareIntent(this))
        } catch (_: Exception) {
            DiagLog.add("Nicio aplicație de pe TV nu poate primi jurnalul; folosește „Salvează jurnalul în Download”.")
            render()
        }
    }

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            adb.text = "Televizorul nu are ecranul pentru „peste alte aplicații”. Dă voie prin ADB:\n\n${Access.adbOverlayCommand(this)}"
            adb.requestFocus()
        }
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

    private fun text(value: String, size: Float, bold: Boolean = false) = AkWidgets.text(this, value, size, bold)

    /** Permite derularea jurnalului cu telecomanda. */
    private fun focusableText() = text("", 14f).apply {
        isFocusable = true
        setOnFocusChangeListener(AkWidgets::panelFocus)
    }

    private fun button(label: String, onClick: () -> Unit) = AkWidgets.button(this, label, action = onClick)

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
        "unmatched" -> "titlu negăsit încă (păstrat, se reîncearcă)"
        "pending" -> "neidentificat încă (păstrat, se completează automat)"
        "ignored" -> "ignorat (nu e film/serial)"
        "disabled" -> "oprit din AK VOD Unify"
        "too_short" -> "prea scurt"
        else -> this
    }

    companion object {
        private const val DIAG_LINES = 120

        fun clock(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
