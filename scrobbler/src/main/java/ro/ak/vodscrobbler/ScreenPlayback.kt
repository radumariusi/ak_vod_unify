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
    val tracker = SessionTracker(packageName, appLabel).also { it.screenOnly = true }
    var guess: ScreenGuess? = null
        private set
    var finished = false
        private set

    private var lastScreenPos: Long? = null

    private val timeResolver = ScreenTimeResolver()
    private var unchangedSince: Long? = null
    private var lastScreenAt = 0L
    private var lastForegroundAt = 0L

    /** O citire a ecranului aplicației (ea e în prim-plan). [details] = pagina titlului de dinainte. */
    fun onScreen(g: ScreenGuess?, rawTimes: ScreenTimes, now: Long, details: ScreenGuess? = null): List<Outgoing> {
        lastForegroundAt = now
        val out = mutableListOf<Outgoing>()
        val duration = tracker.media?.durationMs
        val rawPosition = rawTimes.position(duration)

        // Titlul: de pe ecranul playerului (cu timpi sau linie de episod), altfel pagina titlului.
        val title = g?.takeIf { !rawTimes.isEmpty || it.subtitle != null } ?: if (guess == null) details else null
        // Același episod (S2E10) citit din alt panou al playerului („TV-MA · S2:E10”): păstrăm serialul știut.
        val sameEpisode = title != null && guess != null && title.title != guess!!.title &&
            episodeCode(title.subtitle) != null && episodeCode(title.subtitle) == episodeCode(guess!!.subtitle)
        val changed = title != null && title != guess && !sameEpisode
        val oldCode = episodeCode(guess?.subtitle)
        val newCode = episodeCode(title?.subtitle)
        val newEpisode = oldCode != null && newCode != null && oldCode != newCode
        val continuing = !newEpisode && guess != null && lastScreenPos != null && rawPosition != null &&
            kotlin.math.abs(rawPosition - (tracker.position(now) ?: rawPosition)) < 120_000
        if (changed && !continuing) timeResolver.reset()
        val priorDuration = duration.takeUnless { changed && !continuing }
        val times = timeResolver.resolve(rawTimes)
        val pos = times.position(priorDuration)
        if (changed) {
            guess = title
            val fields = linkedMapOf("title" to title.title, "title_source" to "screen")
            title.subtitle?.let { fields["display_subtitle"] = it }
            out += tracker.onMetadata(MediaInfo(fields, times.durationMs ?: priorDuration), now, correction = continuing)
            if (!continuing) {
                lastScreenPos = null
                unchangedSince = null
            }
        }
        if (times.durationMs != null || pos != null) tracker.onScreenTimes(null, times.durationMs, now, fromScreen = true)

        if (pos != null) {
            val prev = lastScreenPos
            val elapsed = now - lastScreenAt
            unchangedSince = if (prev == pos) unchangedSince ?: lastScreenAt else null
            val state = when {
                prev == null -> PlayState.PLAYING
                pos - prev >= 1_000 && pos - prev >= elapsed / 3 -> PlayState.PLAYING
                unchangedSince?.let { now - it >= 1_500 } == true -> PlayState.PAUSED
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

        private val EPISODE_CODE = Regex("""(?i)(?<![\p{L}\d])[ST](\d{1,2})\s*[:.,\-]?\s*(?:E|Ep\.?\s?)(\d{1,3})(?!\d)""")

        /** „S2:E10 - The Queen Is Dead” → „2x10”. */
        fun episodeCode(s: String?): String? =
            s?.let { EPISODE_CODE.find(it) }?.let { "${it.groupValues[1].toInt()}x${it.groupValues[2].toInt()}" }

        /** Pornim doar pe ecranul unui player: minut + durată (un ceas singur nu e player). */
        fun looksLikePlayer(times: ScreenTimes): Boolean =
            (times.durationMs != null || times.rawPair != null) && times.position(null) != null
    }
}
