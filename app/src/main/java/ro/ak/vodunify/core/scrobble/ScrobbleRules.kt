package ro.ak.vodunify.core.scrobble

import java.util.concurrent.TimeUnit

/** Reguli pure pentru evenimentele din AK Scrobbler. */
object ScrobbleRules {
    /** Implicit: peste 90% = vizionare terminată (genericul de final). Se schimbă din Setări. */
    const val WATCHED_AT = 0.90f

    /** Pragurile „văzut” din care alegi în Setări (procente). */
    val WATCHED_CHOICES = listOf(80, 85, 90, 95)

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

    /** Trecut de prag = văzut, inclusiv în timpul redării (episodul următor pornește singur, fără pauză). */
    fun isFinished(progress: Float?, threshold: Float = WATCHED_AT) = progress != null && progress >= threshold

    /** Minut „estimat” (numărat de scrobbler de la pornire): nu coborâm sub minutul deja știut. */
    fun mergeEstimated(estimatedMs: Long, knownMs: Long?): Long = maxOf(estimatedMs, knownMs ?: 0L)

    /** Textul brut (pentru jurnal), în ordinea în care îl citim. */
    fun rawLabel(fields: Map<String, String>): String =
        TitleParser.FIELD_ORDER.mapNotNull { fields[it] }.distinct().joinToString(" · ").ifBlank { "(fără titlu)" }
}
