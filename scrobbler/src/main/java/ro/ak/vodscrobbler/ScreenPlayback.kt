package ro.ak.vodscrobbler

/**
 * Redare fără sesiune media (Nuvio, DebridStream, unele playere): aplicația nu anunță nimic,
 * deci o urmărim doar după ecranul playerului, citit prin accesibilitate.
 *
 *  - pornește când pe ecran apar minutul și durata (bara playerului);
 *  - minutul care avansează = redare, același minut la două citiri = pauză;
 *  - cât bara e ascunsă, minutul avansează singur (ca la orice redare);
 *  - aplicația iese din prim-plan mai mult de [GONE_AFTER_MS] = oprire.
 *
 * Pur (fără Android), ca să poată fi testat.
 */
class ScreenPlayback(val packageName: String, val appLabel: String) {
    val tracker = SessionTracker(packageName, appLabel)
    var guess: ScreenGuess? = null
        private set
    var finished = false
        private set

    private var lastScreenPos: Long? = null
    private var lastScreenAt = 0L
    private var lastForegroundAt = 0L

    /** O citire a ecranului aplicației (ea e în prim-plan). [details] = pagina titlului de dinainte. */
    fun onScreen(g: ScreenGuess?, times: ScreenTimes, now: Long, details: ScreenGuess? = null): List<Outgoing> {
        lastForegroundAt = now
        val out = mutableListOf<Outgoing>()
        val duration = tracker.media?.durationMs
        val pos = times.position(duration)

        // Titlul: de pe ecranul playerului (cu timpi sau linie de episod), altfel pagina titlului.
        val title = g?.takeIf { !times.isEmpty || it.subtitle != null } ?: if (guess == null) details else null
        if (title != null && title != guess) {
            val continuing = guess != null && lastScreenPos != null && pos != null &&
                kotlin.math.abs(pos - (tracker.position(now) ?: pos)) < 120_000
            guess = title
            val fields = linkedMapOf("title" to title.title, "title_source" to "screen")
            title.subtitle?.let { fields["display_subtitle"] = it }
            out += tracker.onMetadata(MediaInfo(fields, times.durationMs ?: duration), now, correction = continuing)
        }
        if (times.durationMs != null || pos != null) tracker.onScreenTimes(null, times.durationMs, now)

        if (pos != null) {
            val prev = lastScreenPos
            val elapsed = now - lastScreenAt
            val state = when {
                prev == null -> PlayState.PLAYING
                pos - prev >= 1_000 && pos - prev >= elapsed / 3 -> PlayState.PLAYING
                kotlin.math.abs(pos - prev) < 1_000 && elapsed >= 1_500 -> PlayState.PAUSED
                else -> tracker.state.takeIf { it == PlayState.PLAYING || it == PlayState.PAUSED } ?: PlayState.PLAYING
            }
            lastScreenPos = pos
            lastScreenAt = now
            out += tracker.onPlaybackState(state, pos, now, 1f, now)
        }
        return out
    }

    /** Verificare periodică: trimiteri la minut și oprirea când aplicația nu mai e pe ecran. */
    fun onTick(now: Long, foregroundPackage: String?): List<Outgoing> {
        if (finished) return emptyList()
        if (foregroundPackage == packageName) lastForegroundAt = now
        if (now - lastForegroundAt > GONE_AFTER_MS) {
            finished = true
            return tracker.onGone(now)
        }
        return tracker.onTick(now)
    }

    companion object {
        const val GONE_AFTER_MS = 20_000L

        /** Pornim doar pe ecranul unui player: minut + durată (un ceas singur nu e player). */
        fun looksLikePlayer(times: ScreenTimes): Boolean =
            times.durationMs != null && times.position(null) != null
    }
}
