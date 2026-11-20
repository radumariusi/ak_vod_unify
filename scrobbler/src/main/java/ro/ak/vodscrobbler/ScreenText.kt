package ro.ak.vodscrobbler

import java.text.Normalizer

/** Un text de pe ecran, cu id-ul elementului (dacă aplicația îl are, ex. "player_title"). */
data class ScreenItem(val text: String, val viewId: String? = null)

/** Ce credem că e titlul de pe ecranul playerului. */
data class ScreenGuess(val title: String, val subtitle: String?)

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
        """(?i)((?<![\p{L}\d])[ST]\d{1,2}\s*[:.\-]?\s*(E|Ep\.?\s?)\d{1,3}(?!\d))|""" +
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
            return when {
                inlineShow.length > 1 -> ScreenGuess(inlineShow, ep.substring(m.range.first).trim())
                show != null -> ScreenGuess(show.text, ep)
                else -> null
            }
        }

        // 3) Film: primul text rămas (de obicei titlul e primul pe ecranul de pauză).
        return ScreenGuess(clean.first().text, null)
    }

    private const val SEPARATORS = " \t:;-–—·•|,\"'“”„«»()[]"

    private fun idHas(item: ScreenItem, word: String) = item.viewId?.substringAfterLast('/')?.lowercase()?.contains(word) == true

    private fun keep(t: String): Boolean {
        if (t.length < 2 || t.length > 120) return false
        if (t.count(Char::isLetter) < 2) return false
        if (TIME.matches(t) || REMAINING.matches(t)) return false
        if (t.endsWith("%")) return false
        return normalize(t) !in UI
    }

    fun normalize(s: String): String {
        val noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noMarks.lowercase().replace("&", " and ").replace(Regex("[^\\p{L}\\d+]+"), " ").trim()
            .replace(" and ", " ")
    }
}
