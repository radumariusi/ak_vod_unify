package ro.ak.vodunify.core.scrobble

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.imports.ImportParser
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Un rând din „Activitate de vizionare” Netflix, din aplicația conectată (minutele sunt în secunde). */
@Serializable
data class NetflixViewedItem(
    val series: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val title: String = "",
    val date: Long = 0,
    val bookmark: Long? = null,
    val duration: Long? = null,
    val id: String = "",
    /** Ora exactă a vizionării (ms), din pagina „Watched”: Netflix nu mai dă minutul, dă ora. */
    val watchedAt: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/** Rezultatul citirii activității Netflix: status ok | not_logged_in | error. */
@Serializable
data class NetflixRecentResult(
    val route: String = "",
    val status: String = "",
    val message: String? = null,
    val items: List<NetflixViewedItem> = emptyList(),
)

/** O potrivire Netflix deja folosită de o sesiune anterioară (previne atribuirea aceluiași film la redarea următoare). */
@Serializable
data class NetflixClaim(
    val itemKey: String,
    val watchedAt: Long,
    val eventAt: Long,
    val playId: String,
)

/**
 * Aplicația Netflix de pe TV nu spune nimănui ce rulează, dar AK Scrobbler îi prinde minutul.
 * Netflix ține în „Activitate de vizionare” titlul și minutul la care te-ai oprit, deci o vizionare
 * fără titlu se recunoaște după zi + minut. Pur, ca să poată fi testat.
 */
object NetflixActivity {
    val PACKAGES = setOf("com.netflix.ninja", "com.netflix.mediaclient")

    /** Cât poate să difere minutul prins de scrobbler de cel salvat de Netflix. */
    val TOLERANCE_MS = TimeUnit.SECONDS.toMillis(90)
    private val MAX_DAY_GAP_MS = TimeUnit.HOURS.toMillis(36)
    private val AFTER_MS = TimeUnit.MINUTES.toMillis(15)
    private val BEFORE_MS = TimeUnit.HOURS.toMillis(4)
    private val AMBIGUOUS_MS = TimeUnit.MINUTES.toMillis(2)
    private val NUMBERED = Regex("""(?i)^(episode|episodul|episod|ep\.?)\s*(\d{1,3})$""")

    fun isNetflix(pkg: String) = pkg in PACKAGES

    /**
     * Rândul din activitate care se potrivește vizionării, sau null dacă nu e unul singur clar.
     * Întâi după ora vizionării (pagina „Watched” are ora exactă), apoi după minut (API-ul vechi).
     */
    fun match(
        event: ScrobbleEvent,
        items: List<NetflixViewedItem>,
        claims: Map<String, NetflixClaim> = emptyMap(),
    ): NetflixViewedItem? {
        val id = event.fields["netflix_id"]?.takeIf { it.isNotBlank() }
        if (id != null) return items.filter { it.id == id && it.title.isNotBlank() }
            .minByOrNull { abs((it.watchedAt ?: it.date) - event.at) }
        // Când avem minutul exact din „Continue Watching” (`/browse`) pentru titluri din aceeași fereastră de timp,
        // potrivirea după secundă separă sigur 3–4 filme schimbate rapid unul după altul.
        val timed = items.filter { it.watchedAt != null }
        val recentWithBookmark = items.filter {
            it.bookmark != null &&
                it.title.isNotBlank() &&
                !isClaimedByEarlierSession(it, event, claims) &&
                ((it.watchedAt ?: it.date) - event.at) in -BEFORE_MS..AFTER_MS
        }
        if (!event.estimated && event.positionMs >= 30_000L && recentWithBookmark.isNotEmpty()) {
            matchByBookmark(event, recentWithBookmark)?.let { return it }
        }
        return matchByTime(event, timed, claims)
            ?: matchByBookmark(event, items.filter { it.watchedAt == null && !isClaimedByEarlierSession(it, event, claims) })
    }

    private fun isClaimedByEarlierSession(item: NetflixViewedItem, event: ScrobbleEvent, claims: Map<String, NetflixClaim>): Boolean {
        val claim = claims[item.key()] ?: return false
        if (event.playId.isNotEmpty() && claim.playId == event.playId) return false
        if (claim.eventAt >= event.at - 60_000L) return false
        val itemAt = item.watchedAt ?: item.date
        return itemAt <= claim.watchedAt + 60_000L
    }

    /**
     * Netflix ține pentru fiecare titlu ora la care l-ai văzut ultima dată. Evenimentul păstrat e
     * ultimul al vizionării (pauză / oprire), deci titlul e cel cu ora cea mai apropiată de el,
     * cel mult [AFTER_MS] după (ceasuri, întârzieri Netflix) și cel mult [BEFORE_MS] înainte
     * (dacă Netflix notează ora la pornire). Două titluri la fel de aproape → nu ghicim.
     */
    private fun matchByTime(
        event: ScrobbleEvent,
        items: List<NetflixViewedItem>,
        claims: Map<String, NetflixClaim>,
    ): NetflixViewedItem? {
        val candidates = items
            .filter {
                it.watchedAt != null && it.title.isNotBlank() &&
                    compatibleDuration(event, it) && !isClaimedByEarlierSession(it, event, claims)
            }
            .map { it to it.watchedAt!! - event.at }
            .filter { (_, diff) -> diff in -BEFORE_MS..AFTER_MS }
            .map { (item, diff) -> item to abs(diff) }
            .sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        val rival = candidates.drop(1).firstOrNull { it.first.key() != best.first.key() }
        if (rival != null && rival.second - best.second < AMBIGUOUS_MS) {
            val nearby = candidates.filter { it.second - best.second < AMBIGUOUS_MS }.map { it.first }
            if (!event.estimated) return matchByBookmark(event, nearby)
            return null
        }
        return best.first
    }

    private fun matchByBookmark(event: ScrobbleEvent, items: List<NetflixViewedItem>): NetflixViewedItem? {
        if (event.positionMs <= 0 || event.estimated) return null
        val candidates = items
            .filter { it.bookmark != null && it.title.isNotBlank() && compatibleDuration(event, it) }
            .filter { val date = it.watchedAt ?: it.date; date > 0 && abs(date - event.at) <= MAX_DAY_GAP_MS }
            .map { it to abs(it.bookmark!! * 1000 - event.positionMs) }
            .filter { (_, diff) -> diff <= TOLERANCE_MS }
            .sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.drop(1).firstOrNull { it.first.key() != best.first.key() }
        // Două titluri la fel de aproape: nu alegem la întâmplare.
        if (second != null && second.first.key() != best.first.key() && second.second - best.second < 30_000) return null
        return best.first
    }

    private fun compatibleDuration(event: ScrobbleEvent, item: NetflixViewedItem): Boolean {
        val observed = event.duration ?: return true
        val catalogued = item.duration?.takeIf { it > 0 }?.times(1_000) ?: return true
        return abs(observed - catalogued) <= maxOf(3 * 60_000L, catalogued / 8)
    }

    /** Câmpurile de titlu pe care le înțelege [TitleParser]. */
    fun fields(item: NetflixViewedItem): Map<String, String> {
        val out = linkedMapOf("title_source" to "netflix_activity")
        val series = item.series?.takeIf { it.isNotBlank() }
        if (series == null) {
            out["title"] = item.title
            return out
        }
        out["title"] = series
        val episode = item.episode?.takeIf { it.isNotBlank() }
        val season = item.season?.takeIf { it.isNotBlank() }
        val sn = item.seasonNumber?.takeIf { it > 0 } ?: ImportParser.seasonNumber(season)
        val en = item.episodeNumber?.takeIf { it > 0 }
        // Structura exactă, pentru [guesses] (TitleParser nu le citește ca texte).
        out[NF_SERIES] = series
        episode?.let { out[NF_EPISODE] = it }
        sn?.let { out[NF_SEASON] = it.toString() }
        en?.let { out[NF_EPISODE_NUMBER] = it.toString() }
        when {
            // Numerele exacte, cu numele episodului după ele (le înțelege TitleParser).
            sn != null && en != null -> out["display_subtitle"] = "Season $sn Episode $en" + (episode?.let { " $it" } ?: "")
            // „Episode 3” singur nu spune nimic fără sezon: „Season 1 Episode 3”.
            episode != null && season != null && NUMBERED.matches(episode.trim()) -> out["display_subtitle"] = "$season $episode"
            episode != null -> out["display_subtitle"] = episode
            season != null -> out["display_subtitle"] = season
        }
        return out
    }

    /**
     * Ipotezele pentru un episod citit din Netflix: știm sigur care e serialul și care e episodul,
     * deci nu încercăm combinațiile generice (care ar putea lua numele episodului drept film,
     * ex. „Pilot”). null = nu e un episod Netflix structurat; se folosește TitleParser obișnuit.
     */
    fun guesses(fields: Map<String, String>): List<TitleGuess>? {
        if (fields["title_source"] != "netflix_activity") return null
        val series = fields[NF_SERIES]?.takeIf { it.isNotBlank() } ?: return null
        val episode = fields[NF_EPISODE]?.takeIf { it.isNotBlank() }
        val season = fields[NF_SEASON]?.toIntOrNull()
        val number = fields[NF_EPISODE_NUMBER]?.toIntOrNull()
            ?: episode?.let { NUMBERED.matchEntire(it.trim())?.groupValues?.get(2)?.toIntOrNull() }
        val out = mutableListOf<TitleGuess>()
        if (number != null && season != null) out += TitleGuess.NumberedEpisode(series, season, number, episode?.takeUnless { NUMBERED.matches(it.trim()) })
        if (episode != null && !NUMBERED.matches(episode.trim())) out += TitleGuess.SeasonNamedEpisode(series, season, episode)
        if (number != null && season == null) out += TitleGuess.NumberedEpisode(series, null, number, null)
        return out.distinct()
    }

    const val NF_SERIES = "nf_series"
    const val NF_EPISODE = "nf_episode"
    const val NF_SEASON = "nf_season"
    const val NF_EPISODE_NUMBER = "nf_episode_number"
    fun viewedKey(item: NetflixViewedItem): String = item.key()
    private fun NetflixViewedItem.key() = id.ifBlank { title }
}
