package ro.ak.vodscrobbler

import android.content.Context
import android.os.Handler
import android.os.Looper

/** Ce s-a citit de pe ecranul unei aplicații, la un moment dat (ms de la pornirea sistemului). */
data class ScreenSnapshot(
    val packageName: String,
    val at: Long,
    val guess: ScreenGuess?,
    val times: ScreenTimes,
    /** Pagina de detalii a unui titlu (are buton „Redă”/„Play”). */
    val hasPlayButton: Boolean,
    val textCount: Int,
    /** Ecranul arată o reclamă / pauză publicitară (nu conținutul principal). */
    val isAd: Boolean = false,
)

/**
 * Legătura dintre serviciul de accesibilitate (care citește ecranul) și ascultătorul de sesiuni
 * media. Ambele rulează în același proces, pe firul principal.
 */
object ScreenState {
    @Volatile var serviceConnected: Boolean = false

    /**
     * Apple TV își arată textele doar dacă modul cititor de ecran e deja pornit când se deschide.
     * Unify (înainte să-l pornească) sau lansatorul (cu Apple TV selectat) îl „armează” pentru
     * câteva secunde; altfel modul rămâne oprit în lansator/Unify, ca Netflix & co. să nu vorbească.
     */
    @Volatile var readerArmedFor: String? = null
    @Volatile var readerArmedUntil: Long = 0L

    /** Motoarele de voce active găsite (null = necunoscut) și dacă modul vorbitor fără chenar e permis. */
    @Volatile var ttsEngines: List<String>? = null
    @Volatile var spokenModeAllowed: Boolean = false
    @Volatile var readerModeLabel: String = "-"

    /** Serviciul de accesibilitate aplică imediat armarea (pe firul principal). */
    @Volatile var onReaderArm: (() -> Unit)? = null

    fun armReader(pkg: String?, forMs: Long) {
        readerArmedFor = pkg
        readerArmedUntil = if (pkg == null) 0L else android.os.SystemClock.elapsedRealtime() + forMs
    }

    fun readerArmed(): Boolean = readerArmedFor != null && android.os.SystemClock.elapsedRealtime() < readerArmedUntil

    /** Aplicațiile urmărite doar de pe ecran acum (fără sesiune media): le recitim periodic. */
    @Volatile var screenOnlyPackages: Set<String> = emptySet()

    /** Aplicații care au deja titlul și poziția în sesiunea media: nu consumăm CPU citind ecranul. */
    @Volatile var skipScreenForPackages: Set<String> = emptySet()

    /** Aplicația din prim-plan, după accesibilitate. */
    @Volatile var foregroundPackage: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val latest = HashMap<String, ScreenSnapshot>()

    /** Ultima pagină de detalii (cu buton „Redă”) a fiecărei aplicații. */
    private val lastDetails = HashMap<String, ScreenSnapshot>()

    /** Ascultătorul de sesiuni media se abonează aici. */
    var listener: ((ScreenSnapshot) -> Unit)? = null

    /** Serviciul de accesibilitate se abonează aici pentru citiri la cerere (ex. la pauză fără titlu). */
    var onSnapshotRequest: ((String) -> Unit)? = null

    fun requestSnapshot(pkg: String) {
        main.post { onSnapshotRequest?.invoke(pkg) }
    }

    private const val PREFS = "screen"
    private const val KEY_MEDIA_APPS = "media_apps"

    fun publish(s: ScreenSnapshot) {
        main.post {
            latest[s.packageName] = s
            if (s.hasPlayButton && s.guess != null && s.times.isEmpty) lastDetails[s.packageName] = s
            listener?.invoke(s)
        }
    }

    fun latest(pkg: String): ScreenSnapshot? = latest[pkg]

    /**
     * Titlul de pe plăcuța din Google TV pe care s-a apăsat chiar înainte să se deschidă aplicația
     * (Apple TV pornit din „Continuă vizionarea” / recomandări nu-și arată titlul altfel).
     */
    data class LauncherCard(val packageName: String, val guess: ScreenGuess, val at: Long)

    @Volatile private var launcherCard: LauncherCard? = null

    fun setLauncherCard(card: LauncherCard?) { launcherCard = card }

    fun launcherCard(pkg: String, maxAgeMs: Long): LauncherCard? =
        launcherCard?.takeIf { it.packageName == pkg && android.os.SystemClock.elapsedRealtime() - it.at < maxAgeMs }

    fun lastDetails(pkg: String): ScreenSnapshot? = lastDetails[pkg]

    /** Aplicațiile care au redat vreodată ceva: pe acestea le citim și înainte de redare. */
    fun rememberMediaApp(context: Context, pkg: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = p.getStringSet(KEY_MEDIA_APPS, emptySet()).orEmpty()
        if (pkg !in set) p.edit().putStringSet(KEY_MEDIA_APPS, set + pkg).apply()
    }

    fun mediaApps(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_MEDIA_APPS, emptySet()).orEmpty()

    /** Aplicații video cunoscute (le citim chiar înainte de prima redare). */
    val KNOWN_VIDEO_PREFIXES = listOf(
        "com.netflix.", "com.amazon.amazonvideo", "com.amazon.avod", "com.hbo.", "com.wbd.", "com.disney.",
        "com.skyshowtime.", "com.peacocktv.", "com.apple.atve.", "com.stremio.", "com.cbs.", "com.paramount",
        "tv.pluto.", "com.tubitv", "com.streammate.tv", "ro.antenaplay",
        "ro.voyo", "com.voyo.", "net.pro.voyo", "net.cme.voyo", "com.cme.voyo",
        "com.curiosity.", "com.xumo.", "com.future.moviesByFawesome", "tv.fawesome", "com.fawesome",
        "com.abide.magellan", "air.com.vudu.", "com.fandango.", "com.sling", "tv.arte.",
        "com.nuvio", "app.nuvio", "com.debridstream", "com.wuplay", "com.mubi", "com.crunchyroll", "com.plexapp", "org.jellyfin",
        "org.xbmc.kodi", "com.syncler", "com.tivimate", "is.xyz.mpv", "org.videolan.vlc", "com.mxtech.",
        "com.google.android.videos", "com.canal", "com.hbogo", "com.orange.", "ro.digionline", "ro.rcsrds",
    )
}
