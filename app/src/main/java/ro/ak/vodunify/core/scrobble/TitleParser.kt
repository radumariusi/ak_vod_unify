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
        "display_title", "title", "display_subtitle", "subtitle", "artist", "album", "album_artist",
        "display_description", "description", "writer", "author",
    )

    private val SEASON_EPISODE = listOf(
        // S1:E3, S01E03, S1 E3, T1:E3, S1 Ep. 3
        Regex("""(?i)(?<![\p{L}\d])[ST](\d{1,2})\s*[:.\-]?\s*(?:E|Ep\.?\s?)(\d{1,3})(?!\d)"""),
        // Season 1 Episode 3, Sezonul 1 Episodul 3, Staffel 1 Folge 3 …
        Regex(
            """(?i)(?:season|sezonul|sezon|staffel|temporada|saison|stagione|seizoen)\s*(\d{1,2})\D{0,20}?""" +
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

        // 3) Fără numere: serial + nume de episod (în ambele ordini), apoi film.
        val firstThree = texts.take(3)
        for (a in firstThree) for (b in firstThree) {
            if (a != b) out += TitleGuess.NamedEpisode(show = stripYear(b), episodeTitle = stripYear(a))
        }
        texts.take(2).forEach { t -> out += TitleGuess.Movie(stripYear(t), year(t)) }
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
            .filter { it.length > 1 && it.any(Char::isLetter) && it.length < 200 }
            .filterNot { isNoise(it) || normalize(it) == app }
            .filter { seen.add(normalize(it)) }
    }

    private val IGNORED_KEYS = setOf("title_source", "netflix_id", "media_id", "media_uri", "genre", "date", "duration", "year", "track_number", "disc_number", "num_tracks", "rating", "user_rating")

    private fun isNoise(s: String) = normalize(s) in NOISE_NORMALIZED

    private val NOISE_NORMALIZED by lazy { NOISE.map(::normalize).toSet() }

    private fun stripYear(s: String) = s.replace(YEAR, "").trim { it in SEPARATORS }

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
