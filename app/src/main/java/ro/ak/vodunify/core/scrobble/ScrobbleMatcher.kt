package ro.ak.vodunify.core.scrobble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbFindResult
import ro.ak.vodunify.core.tmdb.TmdbHttpException
import ro.ak.vodunify.core.tmdb.TmdbListItem
import ro.ak.vodunify.core.tmdb.TmdbPage
import ro.ak.vodunify.core.tmdb.TmdbSeasonDetails
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Titlul găsit în TMDb pentru ce rulează, cu durata lui (pentru procent, când aplicația nu o dă). */
data class ScrobbleMatch(val media: MediaRef, val runtimeMs: Long?)

/** Alegerea candidatului (pură, testabilă): doar potriviri exacte de nume, fără ghicit. */
object MatchRules {
    data class Candidate(val id: Long, val names: List<String?>, val year: Int?, val countries: List<String> = emptyList())

    fun pickExact(query: String, candidates: List<Candidate>, year: Int? = null, country: String? = null): Candidate? {
        val q = TitleParser.normalize(query)
        if (q.isEmpty()) return null
        val exact = candidates.filter { c -> c.names.any { it != null && TitleParser.normalize(it) == q } }
        if (year != null) exact.firstOrNull { it.year == year }?.let { return it }
        if (country != null) exact.firstOrNull { country in it.countries }?.let { return it }
        return exact.firstOrNull()
    }

    private val QUALIFIER = Regex("""^(.*\S)\s*\(([^()]{1,12})\)$""")
    private val COUNTRIES = mapOf("us" to "US", "usa" to "US", "u s" to "US", "u s a" to "US", "uk" to "GB", "u k" to "GB", "gb" to "GB")

    /**
     * „The Office (U.S.)” → („The Office”, „US”): Netflix pune țara în nume, TMDb nu.
     * Anul („(2019)”) îl tratează deja TitleParser. null = fără calificativ de țară.
     */
    fun countryQualifier(name: String): Pair<String, String>? {
        val m = QUALIFIER.find(name.trim()) ?: return null
        val code = COUNTRIES[TitleParser.normalize(m.groupValues[2])] ?: return null
        return m.groupValues[1] to code
    }

    fun sameName(a: String?, b: String?): Boolean =
        a != null && b != null && TitleParser.normalize(a).let { it.isNotEmpty() && it == TitleParser.normalize(b) }

    private val EPISODE_PART_SUFFIX = Regex(
        """(?i)(?:[:,(\s]+(?:part|parts|partea)\s+\d+(?:\s*(?:&|and|și|/|-)\s*\d+)?\)?|\s*\(\d+\))$""",
    )

    /** Elimină sufixul de părți („Pilot Part 1 & 2” → „Pilot”, „Nuca, Partea 1” → „Nuca”) pentru comparații secundare. */
    fun stripEpisodePartSuffix(name: String): String? {
        val base = name.replace(EPISODE_PART_SUFFIX, "").trim(' ', ',', ':', '-', '–', '(', ')')
        val norm = TitleParser.normalize(base)
        return base.takeIf { norm.length >= 4 && norm != TitleParser.normalize(name) }
    }

    fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()

    /** Verifică dacă durata raportată de player e compatibilă cu durata filmului în TMDb (evită potriviri false episod↔film). */
    fun compatibleDuration(runtimeMs: Long?, reportedMs: Long?): Boolean {
        if (runtimeMs == null || runtimeMs <= 0L || reportedMs == null || reportedMs < 3 * 60_000L) return true
        val diff = kotlin.math.abs(runtimeMs - reportedMs)
        return diff <= (maxOf(runtimeMs, reportedMs) * 0.50).toLong()
    }
}

/**
 * Caută în TMDb titlul din ipotezele [TitleParser]. Cere potrivire exactă a numelui (în română sau
 * engleză, ori titlul original), ca să nu scriem niciodată progresul pe alt film. Răspunsurile TMDb
 * sunt păstrată în cache-ul existent; pentru documentare / titluri localizate lipsă pe TMDb
 * consultăm și IMDb / Wikidata → TMDb `find`.
 */
@Singleton
class ScrobbleMatcher @Inject constructor(
    private val tmdb: TmdbClient,
    private val http: OkHttpClient,
    private val json: Json,
) {
    private val extClient by lazy { http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }
    private val externalMisses = Collections.synchronizedSet(HashSet<String>())

    private val memo = Collections.synchronizedMap(
        object : LinkedHashMap<String, ScrobbleMatch>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrobbleMatch>?) = size > 2_000
        },
    )

    private val misses = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 500
        },
    )

    val isConfigured: Boolean get() = tmdb.isConfigured

    suspend fun match(guesses: List<TitleGuess>, durationMs: Long?): ScrobbleMatch? {
        val key = "${durationMs?.div(60_000)}|${guesses.joinToString("|")}"
        memo[key]?.let { return it }
        // Aceeași căutare eșuată (ex. un serial fără sezon, scanat în 50 de sezoane) nu o repetăm
        // pentru fiecare eveniment al aceleiași vizionări: altfel un lot din coadă durează minute.
        val now = System.currentTimeMillis()
        misses[key]?.let { at -> if (now - at < MISS_TTL_MS) return null else misses.remove(key) }
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
        if (result != null) memo[key] = result else misses[key] = now
        return result
    }

    private suspend fun searchShow(name: String): TvShow? {
        searchShowExact(name, null)?.let { return it }
        // „The Office (U.S.)”: căutăm „The Office” și preferăm varianta din țara aceea.
        val (base, country) = MatchRules.countryQualifier(name) ?: return null
        return searchShowExact(base, country)
    }

    private suspend fun searchShowExact(name: String, country: String?): TvShow? {
        val suggestions = linkedMapOf<Long, TmdbListItem>()
        for (lang in LANGUAGES) {
            val page = tmdb.get("search/tv", mapOf("query" to name, "language" to lang), TmdbPage.serializer(TmdbListItem.serializer()))
            page.results.take(5).forEach { suggestions.putIfAbsent(it.id, it) }
            val pick = MatchRules.pickExact(
                name,
                page.results.map { MatchRules.Candidate(it.id, listOf(it.name, it.originalName), MatchRules.yearOf(it.firstAirDate), it.originCountry) },
                country = country,
            ) ?: continue
            val details = tmdb.get("tv/${pick.id}", mapOf("language" to "en-US"), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL)
            return TvShow(details, page.results.first { it.id == pick.id })
        }
        val aliases = suggestions.values.filter { country == null || country in it.originCountry }
        val aliasMatch = matchingAlias("tv", name, aliases)
        val item = aliasMatch ?: (if (country == null) externalTvItem(name) else null) ?: return null
        val details = tmdb.get("tv/${item.id}", mapOf("language" to "en-US"), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL)
        return TvShow(details, item)
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
                val episodes = season(show.id, season, lang)?.episodes ?: continue
                val ep = pickEpisodeByName(episodes, g.episodeTitle) ?: continue
                return episodeMatch(show, season, ep.episodeNumber, ep.name, ep.runtime)
            }
        }
        // Netflix numerotează uneori altfel („Partea 2”): căutăm în toate sezoanele.
        return findByName(show, g.episodeTitle, preferred = season)
    }

    private suspend fun findByName(show: TvShow, episodeTitle: String, preferred: Int? = null): ScrobbleMatch? {
        val seasons = show.details.seasons
            .filter { it.seasonNumber > 0 && it.seasonNumber != preferred }
            .sortedByDescending { it.seasonNumber }
            .take(MAX_SEASONS_SCANNED)
        for (lang in LANGUAGES) {
            for (s in seasons) {
                val details = season(show.id, s.seasonNumber, lang) ?: continue
                val ep = pickEpisodeByName(details.episodes, episodeTitle) ?: continue
                return episodeMatch(show, s.seasonNumber, ep.episodeNumber, ep.name, ep.runtime)
            }
        }
        return null
    }

    private fun pickEpisodeByName(episodes: List<ro.ak.vodunify.core.tmdb.TmdbEpisode>, target: String): ro.ak.vodunify.core.tmdb.TmdbEpisode? {
        episodes.firstOrNull { MatchRules.sameName(it.name, target) }?.let { return it }
        val base = MatchRules.stripEpisodePartSuffix(target) ?: return null
        val loose = episodes.filter { ep ->
            val n = ep.name ?: return@filter false
            MatchRules.sameName(n, base) || MatchRules.sameName(MatchRules.stripEpisodePartSuffix(n), base)
        }
        return loose.singleOrNull()
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
        val suggestions = linkedMapOf<Long, TmdbListItem>()
        for (lang in LANGUAGES) {
            val page = tmdb.get("search/movie", mapOf("query" to g.title, "language" to lang, "year" to g.year?.toString()), TmdbPage.serializer(TmdbListItem.serializer()))
            page.results.filterNot { it.adult }.take(5).forEach { suggestions.putIfAbsent(it.id, it) }
            val candidates = page.results.filter { !it.adult }
                .map { MatchRules.Candidate(it.id, listOf(it.title, it.originalTitle), MatchRules.yearOf(it.releaseDate)) }
            val exact = candidates.filter { c -> c.names.any { MatchRules.sameName(it, g.title) } }
            if (exact.isEmpty()) continue
            val compatible = if (durationMs != null && exact.size > 1) {
                exact.take(4).filter { MatchRules.compatibleDuration(movieRuntime(it.id), durationMs) }.ifEmpty { exact }
            } else {
                exact
            }
            // Mai multe filme cu același nume: anul, apoi durata cea mai apropiată.
            val byYear = g.year?.let { y -> compatible.firstOrNull { it.year == y } }
            val closest = if (compatible.size > 1 && durationMs != null) {
                compatible.take(4).minByOrNull { c -> kotlin.math.abs((movieRuntime(c.id) ?: (Long.MAX_VALUE / 2)) - durationMs) }
            } else {
                null
            }
            val pick = byYear ?: closest ?: compatible.first()
            val runtime = movieRuntime(pick.id)
            if (!MatchRules.compatibleDuration(runtime, durationMs)) continue
            val item = page.results.first { it.id == pick.id }
            return ScrobbleMatch(
                media = MediaRef(
                    type = MediaType.MOVIE,
                    ids = ExternalIds(tmdb = pick.id),
                    title = item.title ?: item.originalTitle ?: g.title,
                    year = pick.year,
                ),
                runtimeMs = runtime,
            )
        }
        val item = matchingAlias("movie", g.title, suggestions.values)
            ?: externalMovieItem(g.title, g.year)
            ?: return null
        val runtime = movieRuntime(item.id)
        if (!MatchRules.compatibleDuration(runtime, durationMs)) return null
        return ScrobbleMatch(
            MediaRef(MediaType.MOVIE, ExternalIds(tmdb = item.id), item.title ?: item.originalTitle ?: g.title,
                MatchRules.yearOf(item.releaseDate)),
            runtime,
        )
    }

    // ---------- Rezervă documentare / titluri localizate: IMDb + Wikidata → TMDb ----------

    private suspend fun externalTvItem(name: String): TmdbListItem? {
        val key = "tv:${TitleParser.normalize(name)}"
        if (key in externalMisses) return null
        val imdbId = imdbSuggestionId(name, isTv = true, year = null)
        imdbId?.let { tmdbFind(it, isTv = true) }?.let { return it }
        val (tmdbTvId, wdImdbId) = wikidataExternalIds(name, isTv = true)
        tmdbTvId?.let { id ->
            runCatching { tmdb.get("tv/$id", mapOf("language" to "en-US"), TmdbListItem.serializer(), TmdbClient.LONG_TTL) }
                .getOrNull()?.let { return it }
        }
        wdImdbId?.let { tmdbFind(it, isTv = true) }?.let { return it }
        recordExternalMiss(key)
        return null
    }

    private suspend fun externalMovieItem(name: String, year: Int?): TmdbListItem? {
        val key = "movie:${TitleParser.normalize(name)}:${year ?: 0}"
        if (key in externalMisses) return null
        val imdbId = imdbSuggestionId(name, isTv = false, year = year)
        imdbId?.let { tmdbFind(it, isTv = false) }?.let { return it }
        val (tmdbMovieId, wdImdbId) = wikidataExternalIds(name, isTv = false)
        tmdbMovieId?.let { id ->
            runCatching { tmdb.get("movie/$id", mapOf("language" to "en-US"), TmdbListItem.serializer(), TmdbClient.LONG_TTL) }
                .getOrNull()?.takeIf { !it.adult }?.let { return it }
        }
        wdImdbId?.let { tmdbFind(it, isTv = false) }?.let { return it }
        recordExternalMiss(key)
        return null
    }

    private suspend fun tmdbFind(imdbId: String, isTv: Boolean): TmdbListItem? {
        val res = try {
            tmdb.get("find/$imdbId", mapOf("external_source" to "imdb_id", "language" to "en-US"), TmdbFindResult.serializer(), TmdbClient.LONG_TTL)
        } catch (e: TmdbHttpException) {
            if (e.code == 404) return null else throw e
        }
        return if (isTv) res.tvResults.firstOrNull() else res.movieResults.firstOrNull { !it.adult }
    }

    private suspend fun imdbSuggestionId(name: String, isTv: Boolean, year: Int?): String? {
        val encoded = URLEncoder.encode(name.trim().lowercase(), "UTF-8").replace("+", "%20")
        val body = httpGet("https://v3.sg.media-imdb.com/suggestion/x/$encoded.json") ?: return null
        return parseImdbSuggestion(body, name, isTv, year)
    }

    private suspend fun wikidataExternalIds(name: String, isTv: Boolean): Pair<Long?, String?> {
        val encoded = URLEncoder.encode(name.trim(), "UTF-8")
        var qid: String? = null
        for (lang in listOf("ro", "en")) {
            val searchBody = httpGet("https://www.wikidata.org/w/api.php?action=wbsearchentities&search=$encoded&language=$lang&format=json&limit=5")
                ?: continue
            qid = parseWikidataSearchEntityId(searchBody, name)
            if (qid != null) break
        }
        qid ?: return null to null
        val entityBody = httpGet("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qid&props=claims&format=json")
            ?: return null to null
        return parseWikidataClaims(entityBody, qid, isTv)
    }

    private suspend fun httpGet(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "AKVodUnify/1.0").build()
            extClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        }.getOrNull()
    }

    private fun recordExternalMiss(key: String) {
        if (externalMisses.size > 500) externalMisses.clear()
        externalMisses.add(key)
    }

    internal fun parseImdbSuggestion(body: String, name: String, isTv: Boolean, year: Int?): String? = runCatching {
        val arr = json.parseToJsonElement(body).jsonObject["d"]?.jsonArray ?: return null
        val qNorm = TitleParser.normalize(name)
        val candidates = arr.mapNotNull { it as? JsonObject }.filter { obj ->
            val id = obj["id"]?.jsonPrimitive?.content ?: return@filter false
            if (!id.startsWith("tt")) return@filter false
            val title = obj["l"]?.jsonPrimitive?.content ?: return@filter false
            if (TitleParser.normalize(title) != qNorm) return@filter false
            val q = obj["q"]?.jsonPrimitive?.content.orEmpty().lowercase()
            val matchesKind = if (isTv) q.contains("tv series") || q.contains("mini-series") else !q.contains("tv series") && !q.contains("mini-series")
            matchesKind
        }
        val byYear = year?.let { y -> candidates.firstOrNull { it["y"]?.jsonPrimitive?.content?.toIntOrNull() == y } }
        (byYear ?: candidates.firstOrNull())?.get("id")?.jsonPrimitive?.content
    }.getOrNull()

    internal fun parseWikidataSearchEntityId(body: String, name: String): String? = runCatching {
        val qNorm = TitleParser.normalize(name)
        val arr = json.parseToJsonElement(body).jsonObject["search"]?.jsonArray ?: return null
        arr.mapNotNull { it as? JsonObject }.firstOrNull { item ->
            val label = item["label"]?.jsonPrimitive?.content
            val match = item["match"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            val desc = item["description"]?.jsonPrimitive?.content.orEmpty().lowercase()
            val nameOk = TitleParser.normalize(label.orEmpty()) == qNorm || TitleParser.normalize(match.orEmpty()) == qNorm
            val mediaDesc = desc.isEmpty() || listOf("film", "movie", "documentary", "documentar", "series", "serial", "television", "televiziune", "miniseries", "short").any { it in desc }
            nameOk && mediaDesc
        }?.get("id")?.jsonPrimitive?.content
    }.getOrNull()

    internal fun parseWikidataClaims(body: String, qid: String, isTv: Boolean): Pair<Long?, String?> = runCatching {
        val claims = json.parseToJsonElement(body).jsonObject["entities"]?.jsonObject
            ?.get(qid)?.jsonObject?.get("claims")?.jsonObject ?: return null to null
        fun firstClaimString(prop: String): String? = claims[prop]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("mainsnak")?.jsonObject?.get("datavalue")?.jsonObject?.get("value")?.jsonPrimitive?.content
        val tmdbProp = if (isTv) "P4947" else "P4983"
        val tmdbId = firstClaimString(tmdbProp)?.toLongOrNull()
        val imdbId = firstClaimString("P345")?.takeIf { it.startsWith("tt") }
        tmdbId to imdbId
    }.getOrNull() ?: (null to null)

    @Serializable
    private data class AlternativeName(val title: String = "")

    @Serializable
    private data class AlternativeNames(
        val titles: List<AlternativeName> = emptyList(),
        val results: List<AlternativeName> = emptyList(),
    )

    /** Un alias este acceptat când TMDb îl declară și există un singur candidat pentru el. */
    private suspend fun matchingAlias(kind: String, name: String, candidates: Collection<TmdbListItem>): TmdbListItem? {
        val matches = mutableListOf<TmdbListItem>()
        for (item in candidates.take(5)) {
            val aliases = try {
                tmdb.get("$kind/${item.id}/alternative_titles", emptyMap(), AlternativeNames.serializer(), TmdbClient.LONG_TTL)
            } catch (e: TmdbHttpException) {
                if (e.code == 404) continue else throw e
            }
            if ((aliases.titles + aliases.results).any { MatchRules.sameName(it.title, name) }) matches += item
        }
        return matches.singleOrNull()
    }

    private suspend fun movieRuntime(id: Long): Long? = try {
        tmdb.get("movie/$id", mapOf("language" to "en-US"), TmdbTitleDetails.serializer(), TmdbClient.LONG_TTL).runtime
    } catch (e: TmdbHttpException) {
        if (e.code == 404) null else throw e
    }?.takeIf { it > 0 }?.let { TimeUnit.MINUTES.toMillis(it.toLong()) }

    private companion object {
        val LANGUAGES = listOf("ro-RO", "en-US")
        const val MAX_SEASONS_SCANNED = 50
        const val MISS_TTL_MS = 10 * 60_000L
    }
}
