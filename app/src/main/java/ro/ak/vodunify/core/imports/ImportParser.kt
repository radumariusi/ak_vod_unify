package ro.ak.vodunify.core.imports

import ro.ak.vodunify.core.scrobble.TitleGuess
import ro.ak.vodunify.core.scrobble.TitleParser

/**
 * Transformă un element de istoric în ipoteze pentru TMDb. Netflix scrie episoadele ca
 * „Serial: Sezonul 1: Numele episodului” (în CSV) sau în câmpuri separate (în istoricul de pe site).
 */
object ImportParser {

    private val SEASON_LABEL = Regex(
        """(?i)^(?:season|sezonul|sezon|staffel|temporada|saison|stagione|seizoen|series|seria|part|partea|volume|volumul|vol\.?|book|cartea|collection|colecția|colectia)\s*(\d{1,3})$""",
    )
    private val SEASON_LABEL_SUFFIX = Regex("""(?i)^(\d{1,3})(?:st|nd|rd|th)?\s*(?:season|sezon)$""")
    private val LIMITED = Regex(
        """(?i)^(?:limited series|miniseries|mini-series|miniserie|miniserial|serial limitat|serie limitată|serie limitata)$""",
    )

    /** Numărul sezonului din eticheta lui; 1 pentru mini-serii; null dacă nu e o etichetă de sezon. */
    /** „Episode 3”, „Episodul 3”, „Ep. 3”: numele episodului e doar numărul lui. */
    private val EPISODE_ONLY = Regex("""(?i)^(?:episode|episodul|episod|ep\.?|folge|episodio|épisode)\s*(\d{1,3})$""")

    fun seasonNumber(label: String?): Int? {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return null
        SEASON_LABEL.find(l)?.let { return it.groupValues[1].toInt() }
        SEASON_LABEL_SUFFIX.find(l)?.let { return it.groupValues[1].toInt() }
        if (LIMITED.matches(l)) return 1
        return null
    }

    fun isSeasonLabel(label: String) = seasonNumber(label) != null

    fun guesses(item: ImportItem): List<TitleGuess> {
        val series = item.series?.trim()?.takeIf { it.isNotEmpty() }
        if (series != null) {
            val episodeTitle = item.episode?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.title.removePrefix(series).trim { it == ':' || it.isWhitespace() }.takeIf { it.isNotEmpty() && it != item.title }
            val season = seasonNumber(item.season) ?: item.seasonNumber?.takeIf { it > 0 }
            val numbered = item.episodeNumber?.takeIf { it > 0 }
                ?: episodeTitle?.let { EPISODE_ONLY.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            return buildList {
                // Numărul episodului (Netflix îl dă; Prime scrie uneori doar „Episode 3”): cea mai sigură potrivire.
                if (numbered != null && season != null) add(TitleGuess.NumberedEpisode(series, season, numbered, episodeTitle))
                if (episodeTitle != null && (numbered == null || !EPISODE_ONLY.matches(episodeTitle))) {
                    add(TitleGuess.SeasonNamedEpisode(series, season, episodeTitle))
                    // „S1:E3” în numele episodului.
                    addAll(TitleParser.guesses(mapOf("title" to episodeTitle, "artist" to series)).filterIsInstance<TitleGuess.NumberedEpisode>())
                }
            }.distinct()
        }

        val title = item.title.trim()
        if (title.isEmpty()) return emptyList()
        val parts = title.split(": ").map { it.trim() }.filter { it.isNotEmpty() }
        val seasonIndex = parts.indexOfFirst { isSeasonLabel(it) }
        if (seasonIndex >= 1) {
            val show = parts.subList(0, seasonIndex).joinToString(": ")
            val ep = parts.subList(seasonIndex + 1, parts.size).joinToString(": ").takeIf { it.isNotEmpty() }
            val season = seasonNumber(parts[seasonIndex])
            return if (ep != null) {
                listOf(TitleGuess.SeasonNamedEpisode(show, season, ep))
            } else {
                listOf(TitleGuess.Movie(title, null)) // ex. „Kill Bill: Vol. 1”
            }
        }
        return buildList {
            if (parts.size >= 2) {
                // „Serial: Episod” (fără sezon) sau un film cu „:” în titlu („Dune: Part Two”).
                add(TitleGuess.Movie(title, null))
                add(TitleGuess.SeasonNamedEpisode(parts.first(), null, parts.drop(1).joinToString(": ")))
            } else {
                add(TitleGuess.Movie(title, null))
            }
        }
    }
}
