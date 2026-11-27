package ro.ak.vodscrobbler

import java.text.Normalizer

/** Un text de pe ecran, cu id-ul elementului (dacă aplicația îl are, ex. "player_title"). */
data class ScreenItem(val text: String, val viewId: String? = null)

/** Ce credem că e titlul de pe ecranul playerului. */
data class ScreenGuess(val title: String, val subtitle: String?)

/** O bară de progres de pe ecran (SeekBar), cum o raportează accesibilitatea. */
data class ScreenRange(val current: Float, val max: Float, val isPercent: Boolean)

/** Minutul și durata citite de pe ecran (oricare poate lipsi). [remainingMs] = „-45:12”. */
data class ScreenTimes(
    val positionMs: Long?,
    val durationMs: Long?,
    val remainingMs: Long?,
    /**
     * Doi timpi fără alt indiciu („43:53 | 02:12”): al doilea poate fi durata sau timpul rămas.
     * [ScreenPlayback] decide după mai multe citiri (rămasul scade cât crește minutul).
     */
    val rawPair: Pair<Long, Long>? = null,
) {
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
    private val SCHEDULE_RANGE = Regex("""^\d{1,2}:\d{2}\s*[–—-]\s*\d{1,2}:\d{2}$""")
    private val BADGE_TEXT = Regex("""(?i)^(?:\d+\s+(?:episodes?|sezoane|episoade|playlists?)|\d+\s*h(?:\s*\d+\s*m)?|\d+\s*min|tv-[a-z0-9]+|pg-?13|nc-?17|age\s*\d+\s*\+?)$""")
    private val REMAINING = Regex("""(?i)^(\d+\s*(h|ore|oră|min|m|s)\b\s*)+(rămase|remaining|left)?$""")
    private val EPISODE = Regex(
        """(?i)((?<![\p{L}\d])[ST]\d{1,2}\s*[:.,\-·•|/]?\s*(?:E|Ep\.?\s?)\d{1,3}(?!\d))|""" +
            """((?:season|sezonul|sezon|sez\.?)\s*\d{1,2})|((?:episode|episodul|episod|ep\.?)\s*\d{1,3})|((?<![\p{L}\d])E\d{1,3}(?!\d))""",
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
        "hbo max", "skyshowtime", "peacock", "peacocktv", "tubi", "pluto tv", "plex", "xumo", "xumo play",
        "fawesome", "voyo", "arte", "curiositystream", "curiosity stream", "magellantv", "magellan tv",
        "player", "video", "video player", "video player view", "seek bar", "progress", "progres",
        "now playing", "se reda", "up next", "urmeaza", "watch now", "vizioneaza acum",
        "channel guide", "live tv", "on demand", "kids mode", "hide player controls", "show player controls",
        "play queue", "add to watchlist", "press ok to select", "favorite channel",
        "dpad top button", "dpad bottom button", "dpad left button", "dpad right button",
        "evalueaza", "redare episod", "repornire", "informatii", "episoade complete", "difuzat la tv", "similare",
        "profilul principal", "tv live", "shows", "filme", "sport", "copii", "cautare", "sezonul", "episoade",
        "available in", "availabe in", "preview now", "watch with ads", "rent buy", "bundle save",
        "cast", "ratings more info", "more like this", "navigation menu", "curated playlists", "new releases",
        "my lists", "genres", "stream free", "deals", "hubs", "my library",
        "ad", "ads", "advertisement", "reclama", "publicitate", "sponsored", "sponsorizat", "commercial",
        "ad break", "reclame",
    )

    private val AD_TEXT = Regex(
        """(?i)^(?:ad|ads|advertisement|reclam[aă]|publicitate|sponsored|sponsorizat|commercial|ad\s*break)(?:\s*[·:\-]\s*\d.*|\s+\d+\s*(?:of|din|/)\s*\d+\b.*)?$|\b(?:your video will resume|video will resume|content will resume|video-ul va continua|reclama se termin[aă]|(?:this\s+)?ad\s+(?:will\s+end|ends)\s+in|skip\s+ad|sari peste reclam[aă]|during\s+ad\s+break)\b""",
    )

    /** True dacă pe ecran rulează o reclamă (nu filmul / episodul principal). */
    fun isAdScreen(items: List<ScreenItem>): Boolean =
        items.any { item ->
            val t = item.text.trim()
            t.isNotEmpty() && !t.contains('{') && !t.startsWith("ad_marker", ignoreCase = true) && AD_TEXT.containsMatchIn(t)
        }

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
        n in PLAY_WORDS || n.startsWith("play ") || n.startsWith("reda ") || n.startsWith("redare ") ||
            n.startsWith("resume ") || n.startsWith("continua ")
    }

    private val MERIDIEM = Regex("""(?i)^(am|pm|a\.m\.|p\.m\.)$""")
    private val WITH_MERIDIEM = Regex("""(?i)^(\d{1,2}):(\d{2})\s*(am|pm|a\.m\.|p\.m\.)$""")
    private val ENDS_AT = Regex(
        """(?i)(ends at|ends|finishes at|se termin[aă] la|termin[aă] la|sf[aâ]r[sș]it la|final la)\s*(\d{1,2}):(\d{2})\s*(am|pm|a\.m\.|p\.m\.)?""",
    )

    /** Un timp citit de pe ecran: [ms] ca minut:secundă, [dayMinute] dacă ar fi ora din zi (ore:minute). */
    private class Found(val ms: Long, val negative: Boolean, val dayMinute: Int?, val isClock: Boolean)

    private fun dayMinute(h: Int, m: Int, meridiem: String?): Int? {
        if (m > 59) return null
        val mer = meridiem?.lowercase()?.replace(".", "")
        return when (mer) {
            "am" -> if (h in 1..12) (h % 12) * 60 + m else null
            "pm" -> if (h in 1..12) (h % 12 + 12) * 60 + m else null
            else -> if (h in 0..23) h * 60 + m else null
        }
    }

    /**
     * Timpii din player: „12:34 / 58:10”, „12:34” + „-45:36”, „-45:36” singur, sau bara de progres.
     *
     * Unele playere (DebridStream) arată și ora din zi („12:46” + „AM”) și „Ends at 1:32 AM”,
     * iar timpul rămas fără minus („43:53 | 02:12”). Ora o aruncăm, iar din „Ends at” − ora aflăm
     * cât a rămas, ca să știm dacă al doilea timp e durata sau timpul rămas.
     */
    fun times(items: List<ScreenItem>, ranges: List<ScreenRange> = emptyList()): ScreenTimes {
        var position: Long? = null
        var duration: Long? = null
        var remaining: Long? = null
        val found = mutableListOf<Found>()
        var endsAt: Int? = null
        val texts = items.map { it.text.trim() }
        for ((i, t) in texts.withIndex()) {
            ENDS_AT.find(t)?.let { m ->
                endsAt = endsAt ?: dayMinute(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].ifEmpty { null })
            }
            if (t.length > 24) continue
            // Interval orar EPG („00:00 - 02:00”, „20:00 – 22:00”): nu e poziție/durată de player.
            if (SCHEDULE_RANGE.matches(t)) continue
            val withMeridiem = WITH_MERIDIEM.matchEntire(t)
            if (withMeridiem != null) {
                val g = withMeridiem.groupValues
                found += Found(0, false, dayMinute(g[1].toInt(), g[2].toInt(), g[3]), isClock = true)
                continue
            }
            val matches = CLOCK.findAll(t).toList()
            if (matches.isEmpty()) continue
            // Doar texte care sunt aproape numai timp (nu „Episodul 3: 12:30 PM”).
            val rest = CLOCK.replace(t, "").replace(Regex("[\\s/|·•]+"), "")
            if (rest.length > 2) continue
            // „12:46” urmat de „AM” / „PM” = ora din zi, nu minutul filmului.
            val nextIsMeridiem = texts.getOrNull(i + 1)?.let { MERIDIEM.matches(it) } == true
            for (m in matches) {
                val ms = clockMs(m) ?: continue
                val dm = if (m.groupValues[4].isEmpty()) {
                    dayMinute(m.groupValues[2].toInt(), m.groupValues[3].toInt(), if (nextIsMeridiem) texts[i + 1] else null)
                } else {
                    null
                }
                found += Found(ms, m.groupValues[1].isNotEmpty(), dm, isClock = nextIsMeridiem && matches.size == 1)
            }
        }
        val clock = found.firstOrNull { it.isClock }?.dayMinute
        var plain = found.filter { !it.isClock && !it.negative }
        found.firstOrNull { !it.isClock && it.negative }?.let { remaining = it.ms }

        // Cât a rămas după „Ends at” (rezoluție de un minut).
        fun hintFrom(c: Int?): Long? {
            val e = endsAt ?: return null
            val cm = c ?: return null
            return (((e - cm) % 1440 + 1440) % 1440) * 60_000L
        }
        var hint = hintFrom(clock)
        // Ora fără AM/PM (format 24 h): una dintre cele trei valori e ora; o găsim după „Ends at”.
        if (hint == null && endsAt != null && plain.size >= 3) {
            for (c in plain) {
                val h = hintFrom(c.dayMinute) ?: continue
                val others = plain.filter { it !== c }
                if (others.size >= 2 && pairFits(others[0].ms, others[1].ms, h) != null) {
                    plain = others
                    hint = h
                    break
                }
            }
        }
        val values = plain.map { it.ms }
        if (values.size >= 2) {
            val a = values[0]
            val b = values[1]
            when (if (hint != null) pairFits(a, b, hint) else null) {
                PairKind.REMAINING -> { position = a; remaining = b; duration = a + b }
                PairKind.DURATION -> { position = a; duration = b }
                null -> when {
                    b > a -> { position = a; duration = b }
                    else -> position = a
                }
            }
        } else if (values.size == 1) {
            position = values[0]
            remaining?.let { duration = values[0] + it }
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
        val d = duration
        val r = remaining
        if (d != null && r != null && position == null) position = (d - r).coerceAtLeast(0)
        // Când b < a (ex. Voyo „45:54 | 00:29”), lăsăm interpretarea pe seama ScreenTimeResolver.
        if (values.size >= 2 && hint == null && values[1] < values[0]) {
            position = null
            duration = null
        }
        val pair = if (values.size >= 2 && hint == null) values[0] to values[1] else null
        return ScreenTimes(position, duration, remaining, pair)
    }

    private enum class PairKind { REMAINING, DURATION }

    /** Al doilea timp e timpul rămas (cât arată „Ends at” − ora) sau durata totală? */
    private fun pairFits(a: Long, b: Long, remainingHint: Long): PairKind? = when {
        kotlin.math.abs(b - remainingHint) <= HINT_TOLERANCE_MS -> PairKind.REMAINING
        b > a && kotlin.math.abs((b - a) - remainingHint) <= HINT_TOLERANCE_MS -> PairKind.DURATION
        else -> null
    }

    private const val HINT_TOLERANCE_MS = 90_000L

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
        if (TIME.matches(t) || REMAINING.matches(t) || BADGE_TEXT.matches(t)) return false
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
