package ro.ak.vodunify.core.scrobble

import java.util.concurrent.TimeUnit

/** Reguli pure pentru evenimentele din AK Scrobbler. */
object ScrobbleRules {
    /** Peste 90% la pauză/oprire = vizionare terminată (genericul de final). */
    const val WATCHED_AT = 0.90f

    /** Sub 10 minute nu e film sau episod (muzică, trailere, clipuri). */
    val MIN_DURATION_MS: Long = TimeUnit.MINUTES.toMillis(10)

    /** Aplicații de muzică / clipuri: nu le trimitem în TMDb. */
    private val IGNORED_PREFIXES = listOf(
        "com.google.android.youtube", "com.google.android.apps.youtube", "com.liskovsoft.smarttube",
        "com.teamsmart.videomanager", "com.spotify", "deezer.android", "com.aspiro.tidal", "com.soundcloud",
        "com.amazon.mp3", "com.apple.android.music", "tunein.player", "com.google.android.apps.podcasts",
        "com.google.android.music", "com.google.android.katniss", "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx", "ro.ak.vodunify", "ro.ak.vodscrobbler",
        "com.android.bluetooth", "com.google.android.bluetooth", "com.google.android.gms",
    )

    fun isIgnoredPackage(pkg: String) = IGNORED_PREFIXES.any { pkg.startsWith(it) }

    fun progress(positionMs: Long, durationMs: Long?): Float? =
        durationMs?.takeIf { it > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }

    fun isFinished(progress: Float?, isTick: Boolean) = !isTick && progress != null && progress >= WATCHED_AT

    /** Textul brut (pentru jurnal), în ordinea în care îl citim. */
    fun rawLabel(fields: Map<String, String>): String =
        TitleParser.FIELD_ORDER.mapNotNull { fields[it] }.distinct().joinToString(" · ").ifBlank { "(fără titlu)" }
}
