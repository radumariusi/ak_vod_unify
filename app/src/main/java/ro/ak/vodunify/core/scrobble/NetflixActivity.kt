package ro.ak.vodunify.core.scrobble

import kotlinx.serialization.Serializable
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Un rând din „Activitate de vizionare” Netflix, adus de AK Import (minutele sunt în secunde). */
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

/** Răspunsul AK Import RO / US: status ok | not_logged_in | error. */
@Serializable
data class NetflixRecentResult(
    val route: String = "",
    val status: String = "",
    val message: String? = null,
    val items: List<NetflixViewedItem> = emptyList(),
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
    private val NUMBERED = Regex("""(?i)^(episode|episodul|episod|ep\.?)\s*\d{1,3}$""")

    fun isNetflix(pkg: String) = pkg in PACKAGES

    /**
     * Rândul din activitate care se potrivește vizionării, sau null dacă nu e unul singur clar.
     * Întâi după ora vizionării (pagina „Watched” are ora exactă), apoi după minut (API-ul vechi).
     */
    fun match(event: ScrobbleEvent, items: List<NetflixViewedItem>): NetflixViewedItem? =
        if (items.any { it.watchedAt != null }) matchByTime(event, items) else matchByBookmark(event, items)

    /**
     * Netflix ține pentru fiecare titlu ora la care l-ai văzut ultima dată. Evenimentul păstrat e
     * ultimul al vizionării (pauză / oprire), deci titlul e cel cu ora cea mai apropiată de el,
     * cel mult [AFTER_MS] după (ceasuri, întârzieri Netflix) și cel mult [BEFORE_MS] înainte
     * (dacă Netflix notează ora la pornire). Două titluri la fel de aproape → nu ghicim.
     */
    private fun matchByTime(event: ScrobbleEvent, items: List<NetflixViewedItem>): NetflixViewedItem? {
        val candidates = items
            .filter { it.watchedAt != null && it.title.isNotBlank() }
            .map { it to it.watchedAt!! - event.at }
            .filter { (_, diff) -> diff in -BEFORE_MS..AFTER_MS }
            .map { (item, diff) -> item to abs(diff) }
            .sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        val rival = candidates.drop(1).firstOrNull { it.first.key() != best.first.key() }
        if (rival != null && rival.second - best.second < AMBIGUOUS_MS) return null
        return best.first
    }

    private fun matchByBookmark(event: ScrobbleEvent, items: List<NetflixViewedItem>): NetflixViewedItem? {
        if (event.positionMs <= 0) return null
        val candidates = items
            .filter { it.bookmark != null && it.title.isNotBlank() }
            .filter { it.date <= 0 || abs(it.date - event.at) <= MAX_DAY_GAP_MS }
            .map { it to abs(it.bookmark!! * 1000 - event.positionMs) }
            .filter { (_, diff) -> diff <= TOLERANCE_MS }
            .sortedBy { it.second }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)
        // Două titluri la fel de aproape: nu alegem la întâmplare.
        if (second != null && second.first.key() != best.first.key() && second.second - best.second < 30_000) return null
        return best.first
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
        val sn = item.seasonNumber
        val en = item.episodeNumber
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

    private fun NetflixViewedItem.key() = id.ifBlank { title }
}
