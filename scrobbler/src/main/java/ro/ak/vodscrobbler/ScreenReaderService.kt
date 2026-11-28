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
            // Verificăm des și modul cititor de ecran (complet doar în Apple TV/Nuvio/DebridStream).
            updateReaderMode(front)
            val fg = ScreenState.foregroundPackage
            val target = fg ?: front
            if (target != null && pendingPackage == null &&
                (target in ScreenState.screenOnlyPackages ||
                    (isInteresting(target) && target !in ScreenState.skipScreenForPackages &&
                        ScrobblerState.live.none { it.packageName == target }))
            ) {
                runCatching { snapshot(target) }
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
        ScreenState.onReaderArm = { handler.post { updateReaderMode(frontApp()) } }
        ScreenState.onSnapshotRequest = { pkg ->
            if (isInteresting(pkg)) {
                handler.removeCallbacks(snapshotRunnable)
                pendingPackage = pkg
                handler.postDelayed(snapshotRunnable, SETTLE_MS)
            }
        }
        DiagLog.add("Citirea ecranului (accesibilitate) a pornit" + if (tv) " · mod cititor de ecran (TV)" else "")
        KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (LAUNCHERS.any { pkg.startsWith(it) }) runCatching { onLauncherEvent(event) }
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
        } else if (pkg in ScreenState.skipScreenForPackages) {
            // Sesiunea media dă deja titlul și poziția exactă: nu trezim procesorul la fiecare subtitrare/secundă.
            return
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
    /** [MODE_OFF], [MODE_SPOKEN] (fără chenar) sau [MODE_FULL] (cu explorare la atingere). */
    private var readerMode: Int? = null
    private var spokenCheckedAt = 0L
    private var spokenOk = false
    private var lastFront: String? = null

    /** Ultima plăcuță din Google TV apăsată / selectată (titlu + momentul). */
    private var launcherClick: Pair<ScreenGuess, Long>? = null
    private var launcherFocus: Pair<ScreenGuess, Long>? = null
    private var appLabels: Set<String>? = null

    /**
     * Google TV: ținem minte titlul de pe plăcuța selectată / apăsată. Dacă imediat după se deschide o
     * aplicație (Apple TV din „Continuă vizionarea”), ăsta e titlul pornit. Fără mod cititor de ecran:
     * lansatorul își expune textele plăcuțelor și așa.
     */
    private fun onLauncherEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_VIEW_CLICKED && type != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
            type != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) return
        val items = mutableListOf<ScreenItem>()
        event.text?.forEach { t -> t?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { items += ScreenItem(it) } }
        event.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { items += ScreenItem(it) }
        event.source?.let { src ->
            try {
                collectCard(src, items, 0)
            } finally {
                recycleLegacy(src)
            }
        }
        val labels = launchableLabels()
        val isAppIcon = items.isNotEmpty() && items.all { isAppName(ScreenText.normalize(it.text), labels) }
        val cardItems = items.filterNot { val n = ScreenText.normalize(it.text); isAppName(n, labels) || n in CARD_NOISE }
        // O plăcuță de film/serial are cel puțin titlul + o descriere / un gen; „Open Settings”, „Home” nu.
        val distinct = cardItems.map { ScreenText.normalize(it.text) }.distinct()
        val guess = if (distinct.size >= 2) ScreenText.guess(cardItems) else null
        val now = SystemClock.elapsedRealtime()
        val clicked = type == AccessibilityEvent.TYPE_VIEW_CLICKED
        if (clicked) {
            when {
                guess != null -> launcherClick = guess to now
                // Pictograma unei aplicații: nu mai e un titlu anume.
                isAppIcon -> { launcherClick = null; launcherFocus = null }
                // Un buton („Watch on Apple TV”) pe pagina titlului din Google TV: rămâne plăcuța apăsată înainte.
                else -> Unit
            }
        } else {
            launcherFocus = guess?.let { it to now }
        }
        val summary = "${if (clicked) "apăsat" else "selectat"}: ${guess?.let { "${it.title} · ${it.subtitle ?: "-"}" } ?: if (isAppIcon) "pictogramă aplicație" else "-"}"
        if (clicked || lastLogged["launcher"] != summary) {
            lastLogged["launcher"] = summary
            DiagLog.add("Google TV $summary · texte: ${items.take(8).joinToString(" | ") { it.text.take(60) }}")
        }
    }

    private fun collectCard(node: AccessibilityNodeInfo, items: MutableList<ScreenItem>, depth: Int) {
        if (depth > 8 || items.size >= 30) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { items += ScreenItem(it, node.viewIdResourceName) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != node.text?.toString()?.trim() }
            ?.let { items += ScreenItem(it, node.viewIdResourceName) }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collectCard(child, items, depth + 1)
            } finally {
                recycleLegacy(child)
            }
        }
    }

    /** „Tubi TV” e tot aplicația „Tubi”: numele aplicației, cu sau fără „TV”/„+” la final. */
    private fun isAppName(n: String, labels: Set<String>): Boolean =
        n in labels || n.removeSuffix(" tv").removeSuffix("+").trim() in labels || "$n tv" in labels

    /** Numele aplicațiilor instalate (pictogramele din lansator nu sunt titluri). */
    private fun launchableLabels(): Set<String> = appLabels ?: runCatching {
        val pm = packageManager
        listOf(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER, android.content.Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(cat), 0)
        }.map { ScreenText.normalize(it.loadLabel(pm).toString()) }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet()).also { appLabels = it }

    /** Când se schimbă aplicația din față imediat după o plăcuță din Google TV, îi dăm titlul plăcuței. */
    private fun onFrontChanged(front: String) {
        if (front == lastFront) return
        lastFront = front
        if (front == packageName || front == "android" || IGNORED.any { front.startsWith(it) }) return
        val now = SystemClock.elapsedRealtime()
        val pick = launcherClick?.takeIf { now - it.second < CARD_TO_APP_MS }
            ?: launcherFocus?.takeIf { now - it.second < CARD_TO_APP_MS }
        launcherClick = null
        launcherFocus = null
        if (pick == null) return
        ScreenState.setLauncherCard(ScreenState.LauncherCard(front, pick.first, now))
        DiagLog.add("Google TV → ${appLabel(front)}: titlul plăcuței ${pick.first.title} · ${pick.first.subtitle ?: "-"}")
    }

    /**
     * Ne prezentăm ca cititor „vorbitor” (FEEDBACK_SPOKEN) doar dacă nu există niciun motor de voce
     * activ: TV-ul nu are cu ce vorbi. Verificat cel mult o dată pe minut.
     */
    private fun spokenAllowed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (spokenCheckedAt != 0L && now - spokenCheckedAt < 60_000L) return spokenOk
        spokenCheckedAt = now
        val engines = runCatching {
            packageManager.queryIntentServices(android.content.Intent("android.intent.action.TTS_SERVICE"), 0)
                .map { it.serviceInfo.packageName }.filter { it != packageName }.distinct()
        }.getOrNull()
        val ok = engines != null && engines.isEmpty()
        if (ok != spokenOk || readerMode == null) {
            DiagLog.add("Motoare de voce active: ${engines?.joinToString().takeUnless { it.isNullOrEmpty() } ?: if (engines == null) "eroare" else "niciunul"} · mod vorbitor fără chenar ${if (ok) "permis" else "NU (TV-ul ar vorbi)"}")
        }
        spokenOk = ok
        ScreenState.ttsEngines = engines
        ScreenState.spokenModeAllowed = ok
        return ok
    }

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
        front?.let(::onFrontChanged)
        if (!isTv) return
        val base = if (spokenAllowed()) MODE_SPOKEN else MODE_OFF
        if (front == null) {
            if (readerMode == null) setReaderMode(base, "aplicația din față necunoscută")
            return
        }
        // Armarea expiră singură (15 s): n-o ștergem când apare Apple TV, ca modul să nu se
        // oprească/pornească în timp ce Apple TV se deschide (ferestrele se schimbă de câteva ori).
        val armed = ScreenState.readerArmed() && ARM_HOSTS.any { front.startsWith(it) }
        // NICIODATĂ peste tot (v0.3.40–0.3.42): în toate aplicațiile apare chenarul alb de focus
        // (HBO, Prime îl redesenează), iar ștergerea lui a stricat deschiderea aplicațiilor din Google TV.
        // Doar Apple TV/Nuvio/DebridStream (+ Apple TV pornit din Unify).
        // În rest: modul „vorbitor” fără explorare la atingere (fără chenar alb, TV-ul fără motor de voce
        // nu vorbește). Apple TV îl vede deja când pornește, oricum ar fi pornit (și din Google TV).
        // Armarea din Unify contează doar când modul vorbitor nu e permis (altfel Apple TV îl vede deja).
        val full = READER_PREFIXES.any { front.startsWith(it) } || (armed && base == MODE_OFF)
        setReaderMode(if (full) MODE_FULL else base, if (armed) "$front (urmează Apple TV)" else front)
    }


    /** Mod „cititor de ecran” (Apple TV își arată textele doar așa) pornit / oprit. */
    private fun setReaderMode(mode: Int, why: String) {
        if (readerMode == mode) return
        val on = mode == MODE_FULL
        runCatching {
            serviceInfo = serviceInfo.apply {
                flags = (if (on) {
                    flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
                } else {
                    flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
                }) or AccessibilityServiceInfo.FLAG_ENABLE_ACCESSIBILITY_VOLUME
                feedbackType = if (mode != MODE_OFF) {
                    AccessibilityServiceInfo.FEEDBACK_SPOKEN or AccessibilityServiceInfo.FEEDBACK_GENERIC
                } else {
                    AccessibilityServiceInfo.FEEDBACK_GENERIC
                }
            }
            readerMode = mode
            ScreenState.readerModeLabel = when (mode) { MODE_FULL -> "complet"; MODE_SPOKEN -> "vorbitor, fără chenar"; else -> "oprit" }
            DiagLog.add("Mod cititor de ecran: ${when (mode) { MODE_FULL -> "pornit (complet)"; MODE_SPOKEN -> "vorbitor, fără chenar"; else -> "oprit" }} · $why")
            if (mode != MODE_OFF) muteAccessibilityVoice()
        }
    }

    /**
     * Vocea pe care o pornesc aplicațiile când văd un cititor de ecran poate merge pe canalul
     * „accesibilitate”: doar un serviciu de accesibilitate îl poate da la zero. Jurnalul arată dacă a mers.
     */
    private fun muteAccessibilityVoice() {
        runCatching {
            val am = getSystemService(android.media.AudioManager::class.java) ?: return
            val stream = android.media.AudioManager.STREAM_ACCESSIBILITY
            val before = am.getStreamVolume(stream)
            if (before > 0) am.setStreamVolume(stream, 0, 0)
            val tts = runCatching { android.provider.Settings.Secure.getString(contentResolver, "tts_default_synth") }.getOrNull()
            DiagLog.add("Volum accesibilitate: $before → ${am.getStreamVolume(stream)} · muzică=${am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)} · motor TTS=${tts ?: "-"}")
        }.onFailure { DiagLog.add("Volum accesibilitate: eroare ${it.javaClass.simpleName}: ${it.message}") }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ScreenState.serviceConnected = false
        ScreenState.onSnapshotRequest = null
        ScreenState.onReaderArm = null
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
        val snap = ScreenSnapshot(
            pkg,
            lastSnapshotAt,
            guess,
            times,
            ScreenText.hasPlayButton(items),
            items.size,
            isAd = ScreenText.isAdScreen(items),
        )
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
         * NU: lansatorul TV, AK VOD Unify, Peacock (v0.3.36). Aplicațiile verifică modul când pornesc,
         * deci, pornit în lansator, Netflix, Peacock & co. porneau cu el și vorbeau tot timpul.
         */
        val READER_PREFIXES = listOf("com.apple.atve.", "com.nuvio", "app.nuvio", "com.debridstream")

        /**
         * Doar Unify ține modul pornit, cât e armat chiar înainte să deschidă Apple TV. NU lansatorul:
         * în v0.3.37, modul pornit/oprit după plăcuța selectată făcea Apple TV să se închidă imediat.
         */
        val ARM_HOSTS = listOf("ro.ak.vodunify")

        const val MODE_OFF = 0
        const val MODE_SPOKEN = 1
        const val MODE_FULL = 2

        /** Lansatoarele TV ale căror plăcuțe le citim (titlul pornit din „Continuă vizionarea”). */
        val LAUNCHERS = listOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")

        /** Plăcuța apăsată contează doar dacă aplicația apare în față în cel mult atât. */
        const val CARD_TO_APP_MS = 20_000L

        /** Texte de pe plăcuțe care nu sunt titluri. */
        val CARD_NOISE = setOf(
            "apple tv", "apple tv+", "continue watching", "continua vizionarea", "for you", "pentru tine",
            "watch next", "up next", "new episode", "episod nou", "top picks for you", "recomandate",
            "sponsored", "sponsorizat", "buy or rent now", "sign up", "free with ads", "open settings", "all settings",
            "add apps", "inputs", "home",
        ).map { ScreenText.normalize(it) }.toSet()

        const val SETTLE_MS = 800L
        const val POLL_MS = 5_000L
        const val MIN_GAP_MS = 2_500L
        const val MAX_DEPTH = 36
        const val MAX_ITEMS = 120
        const val MAX_NODES = 800

        /** Sistemul, tastaturile și launcherele: nu au titluri de film. */
        val IGNORED = listOf(
            "com.android.systemui", "com.google.android.inputmethod", "com.android.inputmethod", "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx", "com.android.tv.settings", "com.android.settings", "com.tcl.",
            "com.google.android.katniss", "com.google.android.leanbacklauncher",
        )
    }
}
