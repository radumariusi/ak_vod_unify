package ro.ak.vodunify.core.scrobble

import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbHttpException
import ro.ak.vodunify.core.tmdb.TmdbListItem
import ro.ak.vodunify.core.tmdb.TmdbPage
import ro.ak.vodunify.core.tmdb.TmdbSeasonDetails
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Titlul găsit în TMDb pentru ce rulează, cu durata lui (pentru procent, când aplicația nu o dă). */
data class ScrobbleMatch(val media: MediaRef, val runtimeMs: Long?)

/** Alegerea candidatului (pură, testabilă): doar potriviri exacte de nume, fără ghicit. */
object MatchRules {
    data class Candidate(val id: Long, val names: List<String?>, val year: Int?)

    fun pickExact(query: String, candidates: List<Candidate>, year: Int? = null): Candidate? {
        val q = TitleParser.normalize(query)
        if (q.isEmpty()) return null
        val exact = candidates.filter { c -> c.names.any { it != null && TitleParser.normalize(it) == q } }
        if (year != null) exact.firstOrNull { it.year == year }?.let { return it }
        return exact.firstOrNull()
    }

    fun sameName(a: String?, b: String?): Boolean =
        a != null && b != null && TitleParser.normalize(a).let { it.isNotEmpty() && it == TitleParser.normalize(b) }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()
}

/**
 * Caută în TMDb titlul din ipotezele [TitleParser]. Cere potrivire exactă a numelui (în română sau
 * engleză, ori titlul original), ca să nu scriem niciodată progresul pe alt film. Răspunsurile TMDb
 * sunt păstrate în cache-ul existent, deci un episod revăzut nu mai cere rețea.
 */
@Singleton
class ScrobbleMatcher @Inject constructor(private val tmdb: TmdbClient) {

    private val memo = ConcurrentHashMap<String, Optional>()

    private data class Optional(val value: ScrobbleMatch?)

    val isConfigured: Boolean get() = tmdb.isConfigured

    suspend fun match(guesses: List<TitleGuess>, durationMs: Long?): ScrobbleMatch? {
        val key = guesses.joinToString("|")
        memo[key]?.let { return it.value }
        var result: ScrobbleMatch? = null
        // Episoade numerotate: întâi ipotezele în care și numele episodului se potrivește cu TMDb
        // (așa alegem corect care câmp e serialul), apoi oricare cu sezon/episod valid.
        val numberedGuesses = guesses.filterIsInstance<TitleGuess.NumberedEpisode>()
        for (g in numberedGuesses.filter { it.episodeTitle != null }) {
            result = numbered(g, strict = true)
            if (result != null) break
        }
        if (result == null) {
            for (g in guesses) {
                result = when (g) {
                    is TitleGuess.NumberedEpisode -> numbered(g, strict = false)
                    is TitleGuess.NamedEpisode -> named(g)
                    is TitleGuess.SeasonNamedEpisode -> seasonNamed(g)
                    is TitleGuess.Movie -> movie(g, durationMs)
                }
                if (result != null) break
            }
        }
        if (memo.size > 5000) memo.clear()
        memo[key] = Optional(result)
        return result
    }

    private suspend fun searchShow(name: String): TvShow? {
        for (lang in LANGUAGES) {
            val page = tmdb.get("search/tv", mapOf("query" to name, "language" to lang), TmdbPage.serializer(TmdbListItem.serializer()))
            val pick = MatchRules.pickExact(
                name,
                page.results.map { MatchRules.Candidate(it.id, listOf(it.name, it.originalName), MatchRules.yearOf(it.firstAirDate)) },
            ) ?: continue
            val details = tmdb.get("tv/${pick.id}", mapOf("language" to "en-US"), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL)
            return TvShow(details, page.results.first { it.id == pick.id })
        }
        return null
    }

    private data class TvShow(val details: TmdbTitleDetails, val item: TmdbListItem) {
        val id: Long get() = details.id
        val name: String get() = item.name ?: details.name ?: ""
        val year: Int? get() = MatchRules.yearOf(details.firstAirDate)
        val runtimeMs: Long? get() = details.episodeRunTime.firstOrNull()?.let { TimeUnit.MINUTES.toMillis(it.toLong()) }
    }

    /** Un sezon lipsă în TMDb (404) nu e o eroare de rețea: nu cerem reîncercarea. */
    private suspend fun season(showId: Long, number: Int, lang: String): TmdbSeasonDetails? = try {
        tmdb.get("tv/$showId/season/$number", mapOf("language" to lang), TmdbSeasonDetails.serializer(), TmdbClient.LONG_TTL)
    } catch (e: TmdbHttpException) {
        if (e.code == 404) null else throw e
    }

    /** [strict] = numele episodului trebuie să fie cel din TMDb (în engleză sau română). */
    private suspend fun numbered(g: TitleGuess.NumberedEpisode, strict: Boolean): ScrobbleMatch? {
        val show = searchShow(g.show) ?: return null
        val seasons = show.details.seasons.filter { it.seasonNumber > 0 }
        val seasonNumber = g.season ?: when {
            seasons.size == 1 -> seasons.first().seasonNumber
            // Fără sezon, dar cu numele episodului: îl căutăm după nume.
            g.episodeTitle != null -> return findByName(show, g.episodeTitle)
            else -> return null
        }
        val s = season(show.id, seasonNumber, "en-US") ?: return null
        val ep = s.episodes.firstOrNull { it.episodeNumber == g.episode } ?: return null
        if (strict) {
            val title = g.episodeTitle ?: return null
            val nameMatches = MatchRules.sameName(ep.name, title) ||
                season(show.id, seasonNumber, "ro-RO")?.episodes
                    ?.firstOrNull { it.episodeNumber == g.episode }
                    ?.let { MatchRules.sameName(it.name, title) } == true
            if (!nameMatches) return null
        }
        return episodeMatch(show, seasonNumber, ep.episodeNumber, ep.name, ep.runtime)
    }

    private suspend fun named(g: TitleGuess.NamedEpisode): ScrobbleMatch? {
        val show = searchShow(g.show) ?: return null
        return findByName(show, g.episodeTitle)
    }

    private suspend fun seasonNamed(g: TitleGuess.SeasonNamedEpisode): ScrobbleMatch? {
        val show = searchShow(g.show) ?: return null
        val season = g.season
        if (season != null && show.details.seasons.any { it.seasonNumber == season }) {
            for (lang in LANGUAGES) {
                val ep = season(show.id, season, lang)?.episodes?.firstOrNull { MatchRules.sameName(it.name, g.episodeTitle) } ?: continue
                return episodeMatch(show, season, ep.episodeNumber, ep.name, ep.runtime)
            }
        }
        // Netflix numerotează uneori altfel („Partea 2”): căutăm în toate sezoanele.
        return findByName(show, g.episodeTitle, preferred = season)
    }

    private suspend fun findByName(show: TvShow, episodeTitle: String, preferred: Int? = null): ScrobbleMatch? {
        val seasons = show.details.seasons.filter { it.seasonNumber > 0 && it.seasonNumber != preferred }.take(MAX_SEASONS_SCANNED)
        for (lang in LANGUAGES) {
            for (s in seasons) {
                val details = season(show.id, s.seasonNumber, lang) ?: continue
                val ep = details.episodes.firstOrNull { MatchRules.sameName(it.name, episodeTitle) } ?: continue
                return episodeMatch(show, s.seasonNumber, ep.episodeNumber, ep.name, ep.runtime)
            }
        }
        return null
    }

    private fun episodeMatch(show: TvShow, season: Int, episode: Int, name: String?, runtime: Int?) = ScrobbleMatch(
        media = MediaRef(
            type = MediaType.EPISODE,
            ids = ExternalIds(tmdb = show.id, imdb = show.details.externalIds?.imdbId),
            title = show.name,
            year = show.year,
            season = season,
            episode = episode,
            episodeTitle = name,
        ),
        runtimeMs = runtime?.let { TimeUnit.MINUTES.toMillis(it.toLong()) } ?: show.runtimeMs,
    )

    private suspend fun movie(g: TitleGuess.Movie, durationMs: Long?): ScrobbleMatch? {
        for (lang in LANGUAGES) {
            val page = tmdb.get("search/movie", mapOf("query" to g.title, "language" to lang), TmdbPage.serializer(TmdbListItem.serializer()))
            val candidates = page.results.filter { !it.adult }
                .map { MatchRules.Candidate(it.id, listOf(it.title, it.originalTitle), MatchRules.yearOf(it.releaseDate)) }
            val exact = candidates.filter { c -> c.names.any { MatchRules.sameName(it, g.title) } }
            if (exact.isEmpty()) continue
            // Mai multe filme cu același nume: anul, apoi durata cea mai apropiată.
            val byYear = g.year?.let { y -> exact.firstOrNull { it.year == y } }
            val closest = if (exact.size > 1 && durationMs != null) {
                exact.take(4).minByOrNull { c -> kotlin.math.abs((movieRuntime(c.id) ?: (Long.MAX_VALUE / 2)) - durationMs) }
            } else {
                null
            }
            val pick = byYear ?: closest ?: exact.first()
            val item = page.results.first { it.id == pick.id }
            return ScrobbleMatch(
                media = MediaRef(
                    type = MediaType.MOVIE,
                    ids = ExternalIds(tmdb = pick.id),
                    title = item.title ?: item.originalTitle ?: g.title,
                    year = pick.year,
                ),
                runtimeMs = movieRuntime(pick.id),
            )
        }
        return null
    }

    private suspend fun movieRuntime(id: Long): Long? = try {
        tmdb.get("movie/$id", mapOf("language" to "en-US"), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL).runtime
    } catch (e: TmdbHttpException) {
        if (e.code == 404) null else throw e
    }?.takeIf { it > 0 }?.let { TimeUnit.MINUTES.toMillis(it.toLong()) }

    private companion object {
        val LANGUAGES = listOf("ro-RO", "en-US")
        const val MAX_SEASONS_SCANNED = 20
    }
}
