package ro.ak.vodscrobbler

import java.text.Normalizer

/** Un text de pe ecran, cu id-ul elementului (dacă aplicația îl are, ex. "player_title"). */
data class ScreenItem(val text: String, val viewId: String? = null)

/** Ce credem că e titlul de pe ecranul playerului. */
data class ScreenGuess(val title: String, val subtitle: String?)

/** O bară de progres de pe ecran (SeekBar), cum o raportează accesibilitatea. */
data class ScreenRange(val current: Float, val max: Float, val isPercent: Boolean)

/** Minutul și durata citite de pe ecran (oricare poate lipsi). [remainingMs] = „-45:12”. */
data class ScreenTimes(val positionMs: Long?, val durationMs: Long?, val remainingMs: Long?) {
    val isEmpty: Boolean get() = positionMs == null && durationMs == null && remainingMs == null

    /** Poziția, folosind durata știută din altă parte dacă ecranul arată doar timpul rămas. */
    fun position(knownDurationMs: Long?): Long? = positionMs
        ?: remainingMs?.let { r -> (durationMs ?: knownDurationMs)?.let { d -> (d - r).takeIf { it >= 0 } } }
}

/**
 * Alege titlul (și linia de episod) dintre textele afișate de player. Pur, ca să poată fi testat.
 *
 * Playerele (Netflix, Apple TV, Prime Video…) arată la pauză / la pornire numele serialului și o
 * linie de tipul „S1:E3 Numele episodului”. Restul textelor sunt butoane și timpi, pe care îi
 * aruncăm.
 */
object ScreenText {
    private val TIME = Regex("""^[-−]?\d{1,2}:\d{2}(:\d{2})?(\s*/\s*[-−]?\d{1,2}:\d{2}(:\d{2})?)?$""")
    private val REMAINING = Regex("""(?i)^(\d+\s*(h|ore|oră|min|m|s)\b\s*)+(rămase|remaining|left)?$""")
    private val EPISODE = Regex(
        """(?i)((?<![\p{L}\d])[ST]\d{1,2}\s*[:.,\-]?\s*(E|Ep\.?\s?)\d{1,3}(?!\d))|""" +
            """((season|sezonul|sezon)\s*\d{1,2})|((episode|episodul|episod|ep\.)\s*\d{1,3})|((?<![\p{L}\d])E\d{1,3}(?!\d))""",
    )

    /** Butoane și etichete de player (EN / RO), normalizate. */
    private val UI = setOf(
        "play", "pause", "resume", "redare", "reda", "pauza", "continua", "back", "inapoi", "close", "inchide",
        "subtitles", "subtitrari", "subtitrare", "audio", "audio subtitles", "audio and subtitles", "audio si subtitrari",
        "audio subtitrari", "skip intro", "omite intro", "sari peste intro", "skip recap", "omite rezumatul",
        "skip credits", "next episode", "episodul urmator", "urmatorul episod", "episodes", "episoade",
        "more episodes", "alte episoade", "speed", "viteza", "playback speed", "viteza de redare",
        "report a problem", "raporteaza o problema", "settings", "setari", "watch credits", "vezi genericul",
        "rewind", "fast forward", "forward", "seek", "menu", "meniu", "search", "cauta", "home", "acasa",
        "live", "more", "mai mult", "info", "details", "detalii", "restart", "start over", "de la inceput",
        "like", "dislike", "my list", "lista mea", "add to list", "adauga in lista", "share", "trailer",
        "previous", "next", "anterior", "urmator", "volume", "mute", "cc", "hd", "4k", "uhd", "dolby vision",
        "dolby atmos", "hdr", "hdr10", "loading", "se incarca", "ok", "cancel", "anuleaza", "exit", "iesire",
        "netflix", "apple tv", "apple tv+", "prime video", "amazon prime video", "disney+", "disney", "max",
        "hbo max", "skyshowtime", "peacock", "stremio", "player", "video", "video player", "seek bar",
        "progress", "progres", "now playing", "se reda", "up next", "urmeaza", "watch now", "vizioneaza acum",
    )

    fun guess(items: List<ScreenItem>, appLabel: String? = null): ScreenGuess? {
        val app = appLabel?.let(::normalize)
        val clean = items
            .map { it.copy(text = it.text.trim().replace(Regex("\\s+"), " ")) }
            .filter { keep(it.text) && normalize(it.text) != app }
            .distinctBy { normalize(it.text) }
        if (clean.isEmpty()) return null

        // 1) Elemente cu id care spune clar ce sunt.
        val byIdTitle = clean.firstOrNull { idHas(it, "title") && !idHas(it, "subtitle") && !idHas(it, "episode") && !EPISODE.containsMatchIn(it.text) }
        val byIdEpisode = clean.firstOrNull { (idHas(it, "subtitle") || idHas(it, "episode")) && it != byIdTitle }
        if (byIdTitle != null) return ScreenGuess(byIdTitle.text, byIdEpisode?.text ?: clean.firstOrNull { EPISODE.containsMatchIn(it.text) && it != byIdTitle }?.text)

        // 2) Linie de episod („S1:E3 …”): serialul e textul de dinainte (sau de după).
        val epIndex = clean.indexOfFirst { EPISODE.containsMatchIn(it.text) }
        if (epIndex >= 0) {
            val ep = clean[epIndex].text
            val before = clean.take(epIndex).lastOrNull { !EPISODE.containsMatchIn(it.text) }
            val after = clean.drop(epIndex + 1).firstOrNull { !EPISODE.containsMatchIn(it.text) }
            val show = before ?: after
            // Totul pe o singură linie: „Serial S1:E3 Episod”.
            val m = EPISODE.find(ep)!!
            val inlineShow = ep.substring(0, m.range.first).trim { it in SEPARATORS }
                .takeUnless { SEASON_ONLY.matches(it) }.orEmpty()
            // Apple TV: „S1, E1 · Numele episodului, Serialul” (serialul e ultimul, după virgulă).
            val tail = ep.substring(m.range.last + 1)
            val trailingShow = tail.trim { it in SEPARATORS }
                .takeIf { tail.trimStart().firstOrNull() in setOf('·', '•') && it.contains(", ") }?.substringAfterLast(", ")?.trim { it in SEPARATORS }
                ?.takeIf { it.length in 2..60 && it.any(Char::isLetterOrDigit) && !EPISODE.containsMatchIn(it) }
            return when {
                inlineShow.length > 1 -> ScreenGuess(inlineShow, ep.substring(m.range.first).trim())
                trailingShow != null -> ScreenGuess(trailingShow, ep.substring(0, ep.length - trailingShow.length).trim { it in SEPARATORS })
                show != null -> ScreenGuess(show.text, ep)
                else -> null
            }
        }

        // 3) Film: primul text rămas (de obicei titlul e primul pe ecranul de pauză).
        return ScreenGuess(clean.first().text, null)
    }

    private val SEASON_ONLY = Regex("""(?i)^(S|T|season|sezonul|sezon)\s*\d{1,2}$""")
    private val RESOURCE_KEY = Regex("""^[A-Z][A-Za-z]*(\.[A-Za-z]+){2,}$""")

    private const val SEPARATORS = " \t:;-–—·•|,\"'“”„«»()[]"

    private val CLOCK = Regex("""([-−]?)(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val PLAY_WORDS = setOf("play", "resume", "redare", "reda", "continua", "continua vizionarea", "watch now", "vizioneaza acum", "start", "play episode", "reda episodul", "restart", "de la inceput", "start over")

    /** Ecranul are un buton de pornire (pagina de detalii a unui titlu, nu un meniu oarecare). */
    fun hasPlayButton(items: List<ScreenItem>): Boolean = items.any { item ->
        val n = normalize(item.text)
        n in PLAY_WORDS || n.startsWith("play ") || n.startsWith("reda ") || n.startsWith("resume ") || n.startsWith("continua ")
    }

    /**
     * Timpii din player: „12:34 / 58:10”, „12:34” + „-45:36”, „-45:36” singur, sau bara de progres.
     */
    fun times(items: List<ScreenItem>, ranges: List<ScreenRange> = emptyList()): ScreenTimes {
        var position: Long? = null
        var duration: Long? = null
        var remaining: Long? = null
        val plain = mutableListOf<Long>()
        for (item in items) {
            val t = item.text.trim()
            if (t.length > 24) continue
            val found = CLOCK.findAll(t).toList()
            if (found.isEmpty()) continue
            // Doar texte care sunt aproape numai timp (nu „Episodul 3: 12:30 PM”).
            val rest = CLOCK.replace(t, "").replace(Regex("[\\s/|·•]+"), "")
            if (rest.length > 2) continue
            for (m in found) {
                val ms = clockMs(m) ?: continue
                if (m.groupValues[1].isNotEmpty()) remaining = remaining ?: ms else plain += ms
            }
        }
        when {
            plain.size >= 2 && plain[1] > plain[0] -> { position = plain[0]; duration = plain[1] }
            plain.size == 1 && remaining != null -> { position = plain[0]; duration = plain[0] + remaining }
            plain.size >= 2 -> position = plain[0]
        }
        // Bara de progres: secunde sau milisecunde (după mărimea maximului) ori procent.
        for (r in ranges) {
            if (r.max <= 0f || r.current < 0f) continue
            val max = r.max.toDouble()
            val cur = r.current.toDouble()
            when {
                r.isPercent || max == 100.0 || max == 1.0 -> {
                    val d = duration
                    if (position == null && d != null) position = (d * cur / max).toLong()
                }
                max in 300.0..21_600.0 -> { // secunde (5 min – 6 h)
                    if (position == null) position = (cur * 1000).toLong()
                    if (duration == null) duration = (max * 1000).toLong()
                }
                max in 300_000.0..21_600_000.0 -> { // milisecunde
                    if (position == null) position = cur.toLong()
                    if (duration == null) duration = max.toLong()
                }
            }
        }
        if (duration != null && remaining != null && position == null) position = (duration - remaining).coerceAtLeast(0)
        return ScreenTimes(position, duration, remaining)
    }

    private fun clockMs(m: MatchResult): Long? {
        val a = m.groupValues[2].toLong()
        val b = m.groupValues[3].toLong()
        val c = m.groupValues[4].takeIf { it.isNotEmpty() }?.toLong()
        if (b > 59 || (c != null && c > 59)) return null
        val seconds = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
        return seconds * 1000
    }

    private fun idHas(item: ScreenItem, word: String) = item.viewId?.substringAfterLast('/')?.lowercase()?.contains(word) == true

    private fun keep(t: String): Boolean {
        if (t.length < 2 || t.length > 120) return false
        if (t.count(Char::isLetter) < 2) return false
        if (TIME.matches(t) || REMAINING.matches(t)) return false
        if (t.endsWith("%")) return false
        if (RESOURCE_KEY.matches(t)) return false // „ATVE.ScreenReader.Loading”
        return normalize(t) !in UI
    }

    fun normalize(s: String): String {
        val noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noMarks.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\d+]+"), " ").trim()
            .replace(" and ", " ")
    }
}
