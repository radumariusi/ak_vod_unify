package ro.ak.vodunify.core.scrobble

import java.text.Normalizer

/** O ipoteză despre ce rulează, scoasă din textele sesiunii media. */
sealed interface TitleGuess {
    /** Serial + sezon/episod cunoscute (ex. „S1:E3”). [season] = null când lipsește (mini-serii). */
    data class NumberedEpisode(val show: String, val season: Int?, val episode: Int, val episodeTitle: String?) : TitleGuess

    /** Serial + numele episodului, fără numere: căutăm episodul după nume. */
    data class NamedEpisode(val show: String, val episodeTitle: String) : TitleGuess

    data class Movie(val title: String, val year: Int?) : TitleGuess

    /** Serial + (eventual) sezonul + numele episodului, ca în istoricul Netflix. */
    data class SeasonNamedEpisode(val show: String, val season: Int?, val episodeTitle: String) : TitleGuess
}

/**
 * Transformă câmpurile brute din sesiunea media (titlu, subtitlu, artist, album…) în ipoteze
 * ordonate de la cea mai sigură la cea mai puțin sigură. Fiecare aplicație le completează altfel,
 * așa că încercăm toate combinațiile rezonabile; potrivirea finală cere egalitate exactă în TMDb.
 */
object TitleParser {

    /** Ordinea în care ne uităm la câmpuri (nume scurte, ca în AK Scrobbler). */
    val FIELD_ORDER = listOf(
        "display_title", "title", "display_subtitle", "subtitle", "screen_title", "artist", "album", "album_artist",
        "alt_title", "alt_subtitle", "display_description", "description", "writer", "author",
    )

    private val SEASON_EPISODE = listOf(
        // S1:E3, S01E03, S1 E3, S3 · E7, T1:E3, S1 Ep. 3
        Regex("""(?i)(?<![\p{L}\d])[ST](\d{1,2})\s*[:.,\-·•|/]?\s*(?:E|Ep\.?\s?)(\d{1,3})(?!\d)"""),
        // Season 1 Episode 3, Sezonul 1 Episodul 3, Sez. 3 Ep. 7, Staffel 1 Folge 3 …
        Regex(
            """(?i)(?:season|sezonul|sezon|sez\.?|staffel|temporada|saison|stagione|seizoen)\s*(\d{1,2})\D{0,20}?""" +
                """(?:episode|episodul|episod|ep\.?|folge|episodio|épisode|aflevering)\s*(\d{1,3})""",
        ),
        // 1x03
        Regex("""(?<!\d)(\d{1,2})x(\d{2,3})(?!\d)"""),
    )

    /** Doar episodul („Episodul 3”, „Ep. 3”, „E3”). */
    private val EPISODE_ONLY = Regex(
        """(?i)(?<![\p{L}\d])(?:episode|episodul|episod|ep\.?|folge|episodio|épisode|aflevering|E)\s?(\d{1,3})(?!\d)""",
    )

    private val YEAR = Regex("""\((19\d{2}|20\d{2})\)""")
    private const val SEPARATORS = " \t:;-–—·•|,\"'“”„«»()[]"

    /** Texte care nu sunt titluri (numele aplicației, mesaje de stare). */
    private val NOISE = setOf(
        "netflix", "prime video", "primevideo", "amazon prime video", "video", "movie", "episode", "tv", "unknown", "disney", "disney+", "max", "hbo max", "hbo", "skyshowtime",
        "peacock", "paramount+", "apple tv", "apple tv+", "stremio", "nuvio", "voyo", "now playing", "playing",
        "se redă", "redare", "live", "trailer", "preview", "advertisement", "ad", "reclamă",
    )

    fun guesses(fields: Map<String, String>, appLabel: String? = null): List<TitleGuess> {
        NetflixActivity.guesses(fields)?.let { return it }
        val texts = orderedTexts(fields, appLabel)
        if (texts.isEmpty()) return emptyList()
        val out = mutableListOf<TitleGuess>()

        // 1) Sezon + episod în clar, în oricare câmp.
        for ((i, text) in texts.withIndex()) {
            val found = SEASON_EPISODE.firstNotNullOfOrNull { r -> r.find(text) } ?: continue
            val season = found.groupValues[1].toInt()
            val episode = found.groupValues[2].toInt()
            addNumbered(out, texts, i, text, found, season, episode)
        }
        // 2) Doar episodul („Episodul 3”), pentru mini-serii.
        if (out.isEmpty()) {
            for ((i, text) in texts.withIndex()) {
                val found = EPISODE_ONLY.find(text) ?: continue
                addNumbered(out, texts, i, text, found, null, found.groupValues[1].toInt())
            }
        }
        if (out.isNotEmpty()) return out.distinct()

        // 3) Fără numere: serial + nume de episod (în ambele ordini), apoi film (dacă nu avem indiciu de sezon).
        val firstThree = texts.take(3)
        for (a in firstThree) for (b in firstThree) {
            if (a != b) out += TitleGuess.NamedEpisode(show = stripYear(b), episodeTitle = stripYear(a))
        }
        val altTitle = fields["alt_title"]?.trim()?.takeIf { it.length > 1 && !isNoise(it) }
        val altSubtitle = fields["alt_subtitle"]?.trim()?.takeIf { it.length > 1 && !isNoise(it) }
        if (altTitle != null && altSubtitle != null && !altTitle.equals(altSubtitle, ignoreCase = true)) {
            out += TitleGuess.NamedEpisode(show = stripYear(altTitle), episodeTitle = stripYear(altSubtitle))
        }
        val isConfirmedMovie = fields["title_source"] == "netflix_activity"
        if ("season_hint" !in fields) {
            texts.take(2).forEach { t ->
                val clean = stripYear(t)
                if (!isGenericEpisodeTitle(clean) || isConfirmedMovie) out += TitleGuess.Movie(clean, year(t))
            }
            if (altTitle != null && !isGenericEpisodeTitle(stripYear(altTitle))) {
                out += TitleGuess.Movie(stripYear(altTitle), year(altTitle))
            }
        }
        // Documentare / seriale formatate într-un singur câmp („Serial: Nume episod” / „Serial - Nume episod”).
        texts.take(2).forEach { t ->
            splitColonEpisode(stripYear(t))?.let { (show, ep) ->
                out += TitleGuess.NamedEpisode(show = show, episodeTitle = ep)
            }
        }
        return out.distinct()
    }

    private fun addNumbered(
        out: MutableList<TitleGuess>,
        texts: List<String>,
        index: Int,
        text: String,
        found: MatchResult,
        season: Int?,
        episode: Int,
    ) {
        val before = text.substring(0, found.range.first).trim { it in SEPARATORS }
        val after = text.substring(found.range.last + 1).trim { it in SEPARATORS }
        val episodeTitle = after.takeIf { it.length > 1 }
        val others = texts.filterIndexed { j, _ -> j != index }
        if (before.length > 1 && !isNoise(before)) {
            out += TitleGuess.NumberedEpisode(stripYear(before), season, episode, episodeTitle ?: others.firstOrNull())
        }
        // Serialul e în alt câmp; numele episodului e restul câmpului sau celălalt câmp.
        for (show in others.take(3)) {
            val title = episodeTitle ?: others.firstOrNull { it != show }
            out += TitleGuess.NumberedEpisode(stripYear(show), season, episode, title)
        }
    }

    private fun orderedTexts(fields: Map<String, String>, appLabel: String?): List<String> {
        // Cheile proprii ale aplicațiilor („com.amazon….content_type”) nu sunt titluri.
        val keys = FIELD_ORDER + fields.keys.filter { it !in FIELD_ORDER && it !in IGNORED_KEYS && '.' !in it }
        val seen = mutableSetOf<String>()
        val app = appLabel?.let(::normalize)
        return keys.mapNotNull { k -> fields[k]?.trim() }
            .filter { it.length > 1 && (it.any(Char::isLetter) || NUMERIC_TITLE.matches(it)) && it.length < 200 }
            .filterNot { isNoise(it) || normalize(it) == app }
            .filter { seen.add(normalize(it)) }
    }

    /** Titluri doar din cifre: „1917”, „1883”, „12 12 12”. */
    private val NUMERIC_TITLE = Regex("""^(\d{3,4}|\d{1,4}([ .\-]\d{1,4})+)$""")

    private val IGNORED_KEYS = setOf("title_source", "season_hint", "netflix_id", "media_id", "media_uri", "genre", "date", "duration", "year", "track_number", "disc_number", "num_tracks", "rating", "user_rating",
        NetflixActivity.NF_SERIES, NetflixActivity.NF_EPISODE, NetflixActivity.NF_SEASON, NetflixActivity.NF_EPISODE_NUMBER)

    private val GENERIC_EPISODE_TITLES = setOf(
        "pilot", "premiere", "series premiere", "season premiere", "premiera", "prologue", "epilogue",
    )

    private val SEQUEL_SUFFIX = Regex(
        """(?i)^(?:part|partea|vol\.?|volume|chapter|capitolul)\s+(?:\d+|one|two|three|four|five|unu|doi|trei|[ivx]+)$""",
    )

    private fun isGenericEpisodeTitle(s: String) = normalize(s) in GENERIC_EPISODE_TITLES

    private fun splitColonEpisode(s: String): Pair<String, String>? {
        val idx = s.indexOf(": ").takeIf { it >= 3 }
            ?: s.indexOf(" - ").takeIf { it >= 3 }
            ?: s.indexOf(" – ").takeIf { it >= 3 }
            ?: return null
        val sepLen = if (s.startsWith(": ", idx)) 2 else 3
        val left = s.substring(0, idx).trim()
        val right = s.substring(idx + sepLen).trim()
        if (left.length < 3 || right.length < 3 || SEQUEL_SUFFIX.matches(right)) return null
        return left to right
    }

    private fun isNoise(s: String) = normalize(s) in NOISE_NORMALIZED

    private val NOISE_NORMALIZED by lazy { NOISE.map(::normalize).toSet() }

    private fun stripYear(s: String): String {
        val t = s.replace(YEAR, "").trim { it in SEPARATORS }
        // „The Office (U.S.)”: paranteza de la final face parte din titlu, n-o tăiem.
        return if (t.count { it == '(' } > t.count { it == ')' }) "$t)" else t
    }

    private fun year(s: String) = YEAR.find(s)?.groupValues?.get(1)?.toInt()

    /** Pentru comparații: fără diacritice, litere mici, doar litere/cifre separate de un spațiu. */
    fun normalize(s: String): String {
        val noMarks = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noMarks.lowercase()
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\d]+"), " ")
            .trim()
    }
}
