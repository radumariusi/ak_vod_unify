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
)

/**
 * Legătura dintre serviciul de accesibilitate (care citește ecranul) și ascultătorul de sesiuni
 * media. Ambele rulează în același proces, pe firul principal.
 */
object ScreenState {
    @Volatile var serviceConnected: Boolean = false

    /** Aplicațiile urmărite doar de pe ecran acum (fără sesiune media): le recitim periodic. */
    @Volatile var screenOnlyPackages: Set<String> = emptySet()

    /** Aplicația din prim-plan, după accesibilitate. */
    @Volatile var foregroundPackage: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val latest = HashMap<String, ScreenSnapshot>()

    /** Ultima pagină de detalii (cu buton „Redă”) a fiecărei aplicații. */
    private val lastDetails = HashMap<String, ScreenSnapshot>()

    /** Ascultătorul de sesiuni media se abonează aici. */
    var listener: ((ScreenSnapshot) -> Unit)? = null

    private const val PREFS = "screen"
    private const val KEY_MEDIA_APPS = "media_apps"

    fun publish(s: ScreenSnapshot) {
        main.post {
            latest[s.packageName] = s
            if (s.hasPlayButton && s.guess != null) lastDetails[s.packageName] = s
            listener?.invoke(s)
        }
    }

    fun latest(pkg: String): ScreenSnapshot? = latest[pkg]

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
        "tv.pluto.", "ro.antenaplay", "ro.voyo", "com.voyo.", "net.pro.voyo", "com.nuvio", "app.nuvio",
        "com.debridstream", "com.wuplay", "com.mubi", "com.crunchyroll", "com.plexapp", "org.jellyfin",
        "org.xbmc.kodi", "com.syncler", "com.tivimate", "is.xyz.mpv", "org.videolan.vlc", "com.mxtech.",
        "com.google.android.videos", "com.canal", "com.hbogo", "com.orange.", "ro.digionline", "ro.rcsrds",
    )
}
