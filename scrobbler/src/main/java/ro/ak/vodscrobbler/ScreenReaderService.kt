package ro.ak.vodscrobbler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Citește textele de pe ecran (titlul și minutul) din aplicațiile video, pentru cele care nu le
 * pun în sesiunea media (Netflix, Apple TV…). Nu apasă nimic și nu păstrează nimic altceva decât
 * ultimul titlu / minut găsit pentru fiecare aplicație (plus liniile din jurnalul de diagnostic).
 */
class ScreenReaderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var pendingPackage: String? = null
    private val lastLogged = HashMap<String, String>()
    private var lastSnapshotAt = 0L
    private var nodeCount = 0
    private val classes = HashMap<String, Int>()

    /**
     * Playerele fără sesiune media (Nuvio, DebridStream) nu ne anunță pauza: dacă bara cu timpul
     * stă pe ecran, n-avem evenimente noi. Le recitim ecranul la câteva secunde cât sunt în față.
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            // Aplicația din față, după ferestre (lansatorul e ignorat la evenimente, deci nu ne-am baza pe ele).
            val front = frontApp()
            if (ScreenState.screenOnlyPackages.isNotEmpty()) front?.let { ScreenState.foregroundPackage = it }
            // Verificăm des și modul cititor de ecran: Netflix nu trebuie să-l vadă niciodată.
            updateReaderMode(front)
            val fg = ScreenState.foregroundPackage
            if (fg != null && fg in ScreenState.screenOnlyPackages && pendingPackage == null) {
                runCatching { snapshot(fg) }
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val snapshotRunnable = Runnable {
        val pkg = pendingPackage
        pendingPackage = null
        if (pkg != null) snapshot(pkg)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagLog.init(this)
        ScreenState.serviceConnected = true
        // Doar pe televizor (fără ecran tactil): ne prezentăm ca un cititor de ecran complet, ca
        // Netflix să-și expună textele. Pe telefon asta ar schimba atingerile, deci nu.
        val tv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        isTv = tv
        // Nu pornim orbește: TV-ul repornește serviciul și când Netflix e deja în față.
        if (tv) updateReaderMode(frontApp())
        handler.removeCallbacks(pollRunnable)
        handler.postDelayed(pollRunnable, POLL_MS)
        DiagLog.add("Citirea ecranului (accesibilitate) a pornit" + if (tv) " · mod cititor de ecran (TV)" else "")
        KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Netflix, când vede un cititor de ecran, citește cu voce tare butoanele (și oricum nu
            // ne arată textele): cât e Netflix în față, nu ne mai prezentăm ca cititor de ecran.
            // Decidem după fereastra aplicației din față, nu după cine a trimis evenimentul (bara
            // de volum sau o notificare peste Netflix nu înseamnă că Netflix a ieșit).
            updateReaderMode(frontApp() ?: pkg.takeUnless { it == packageName || IGNORED.any { p -> it.startsWith(p) } })
        }
        if (pkg == packageName || IGNORED.any { pkg.startsWith(it) }) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            ScreenState.foregroundPackage = pkg
        }
        if (!isInteresting(pkg)) return
        // Cel mult o citire la ~1 s: playerele schimbă textele (timpul) foarte des.
        if (pendingPackage == pkg) return
        pendingPackage = pkg
        val now = SystemClock.elapsedRealtime()
        val delay = if (now - lastSnapshotAt > MIN_GAP_MS) SETTLE_MS else MIN_GAP_MS
        handler.removeCallbacks(snapshotRunnable)
        handler.postDelayed(snapshotRunnable, delay)
    }

    private var isTv = false
    private var readerMode: Boolean? = null

    /** Pachetul ferestrei de aplicație din față (null dacă nu se poate afla). */
    private fun frontApp(): String? = runCatching {
        windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedBy { if (it.isFocused) 0 else if (it.isActive) 1 else 2 }
            .firstNotNullOfOrNull { window ->
                window.root?.packageName?.toString()?.takeUnless { it == packageName }
            }
    }.getOrNull()

    /**
     * Cititor de ecran pornit doar cât în față e o aplicație din [READER_PREFIXES]. Pentru restul
     * (Netflix, YouTube…) e oprit: văzând un cititor de ecran, ele citesc cu voce butoanele.
     * [front] necunoscut: păstrăm starea, iar la pornirea serviciului alegem „oprit”.
     */
    private fun updateReaderMode(front: String?) {
        if (!isTv) return
        if (front == null) {
            if (readerMode == null) setReaderMode(false, "aplicația din față necunoscută")
            return
        }
        setReaderMode(READER_PREFIXES.any { front.startsWith(it) }, front)
    }

    /** Mod „cititor de ecran” (Apple TV își arată textele doar așa) pornit / oprit. */
    private fun setReaderMode(on: Boolean, why: String) {
        if (readerMode == on) return
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = if (on) {
                    flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                } else {
                    flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                }
                feedbackType = if (on) {
                    AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_GENERIC
                } else {
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
                }
            }
            readerMode = on
            DiagLog.add("Mod cititor de ecran: ${if (on) "pornit" else "oprit"} · $why")
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ScreenState.serviceConnected = false
        handler.removeCallbacksAndMessages(null)
        DiagLog.add("Citirea ecranului (accesibilitate) s-a oprit")
        super.onDestroy()
    }

    private fun isInteresting(pkg: String): Boolean =
        ScrobblerState.live.any { it.packageName == pkg } ||
            ScreenState.KNOWN_VIDEO_PREFIXES.any { pkg.startsWith(it) } ||
            pkg in ScreenState.mediaApps(this)

    private fun snapshot(pkg: String) {
        lastSnapshotAt = SystemClock.elapsedRealtime()
        val items = mutableListOf<ScreenItem>()
        val ranges = mutableListOf<ScreenRange>()
        nodeCount = 0
        classes.clear()
        val all = mutableListOf<AccessibilityNodeInfo>()
        runCatching { windows }.getOrNull()?.forEach { w -> w.root?.let { all.add(it) } }
        if (all.isEmpty()) rootInActiveWindow?.let { all.add(it) }
        val roots = all.filter { it.packageName?.toString() == pkg }
        if (roots.isEmpty()) {
            all.forEach(::recycleLegacy)
            return
        }
        try {
            roots.forEach { collect(it, items, ranges, 0) }
        } finally {
            all.forEach(::recycleLegacy)
        }

        val app = ScrobblerState.live.firstOrNull { it.packageName == pkg }?.appLabel ?: appLabel(pkg)
        val guess = ScreenText.guess(items, app)
        val times = ScreenText.times(items, ranges)
        val snap = ScreenSnapshot(pkg, lastSnapshotAt, guess, times, ScreenText.hasPlayButton(items), items.size)
        ScreenState.publish(snap)

        // Jurnal: doar când se schimbă ce înțelegem de pe ecran (nu la fiecare secundă).
        val summary = "titlu=${guess?.title ?: "-"} · episod=${guess?.subtitle ?: "-"} · buton redare=${if (snap.hasPlayButton) "da" else "nu"}" +
            " · ${items.size} texte"
        if (lastLogged[pkg] != summary) {
            lastLogged[pkg] = summary
            val sample = items.take(25).joinToString(" | ") { it.text.take(60) + (it.viewId?.substringAfterLast('/')?.let { id -> " #$id" } ?: "") }
            val t = times.let { "minut=${it.positionMs?.let(StatusActivity::clock) ?: "-"} durată=${it.durationMs?.let(StatusActivity::clock) ?: "-"} rămas=${it.remainingMs?.let(StatusActivity::clock) ?: "-"}" }
            val tree = "noduri=$nodeCount ferestre=${roots.size} tipuri=" + classes.entries.sortedByDescending { it.value }.take(6).joinToString { "${it.key}×${it.value}" }
            DiagLog.add("Ecran $app: $summary · $t\n  $tree\n  texte (${items.size}): $sample")
        }
    }

    private fun collect(node: AccessibilityNodeInfo, items: MutableList<ScreenItem>, ranges: MutableList<ScreenRange>, depth: Int) {
        if (depth > MAX_DEPTH || items.size >= MAX_ITEMS || nodeCount > MAX_NODES) return
        nodeCount++
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        classes[cls] = (classes[cls] ?: 0) + 1
        // Și textele ascunse: unele playere țin titlul într-un panou ascuns cât timp rulează.
        run {
            val id = node.viewIdResourceName
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) items += ScreenItem(text, id)
            if (desc.isNotEmpty() && desc != text) items += ScreenItem(desc, id)
            node.rangeInfo?.let { r ->
                ranges += ScreenRange(r.current, r.max, r.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT)
            }
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collect(child, items, ranges, depth + 1)
            } finally {
                recycleLegacy(child)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleLegacy(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        /**
         * Singurele aplicații pentru care pornim modul cititor de ecran: Apple TV își arată titlul
         * doar așa; Nuvio și DebridStream au mers (fără voce) cu el pornit și nu le schimbăm.
         */
        val READER_PREFIXES = listOf("com.apple.atve.", "com.nuvio", "app.nuvio", "com.debridstream")

        const val SETTLE_MS = 700L
        const val POLL_MS = 4_000L
        const val MIN_GAP_MS = 1_500L
        const val MAX_DEPTH = 40
        const val MAX_ITEMS = 300
        const val MAX_NODES = 3000

        /** Sistemul, tastaturile și launcherele: nu au titluri de film. */
        val IGNORED = listOf(
            "com.android.systemui", "com.google.android.inputmethod", "com.android.inputmethod", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.android.tv.settings", "com.android.settings", "com.tcl.",
            "com.google.android.katniss", "com.google.android.leanbacklauncher",
        )
    }
}
