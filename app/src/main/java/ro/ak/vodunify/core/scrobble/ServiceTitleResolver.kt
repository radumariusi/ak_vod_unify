package ro.ak.vodunify.core.scrobble

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Titlul aflat de pe pagina publică a serviciului, după codul trimis de aplicație. */
data class ServiceTitle(val show: String, val season: Int? = null, val episode: Int? = null, val episodeTitle: String? = null)

/**
 * Parsarea paginilor publice (pură, testabilă).
 *  - Prime Video: sesiunea media are MEDIA_ID = „amzn1.dv.gti.…” (codul titlului).
 *  - Netflix: codul numeric (din jurnalul de sistem, dacă AK Scrobbler are voie să-l citească).
 */
object ServicePages {
    val PRIME_GTI = Regex("""amzn1\.dv\.gti\.[0-9a-fA-F-]{36}""")
    val NETFLIX_ID = Regex("""^\d{6,9}$""")

    private val OG_TITLE = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OG_TITLE_REV = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:title["']""", RegexOption.IGNORE_CASE)
    private val TITLE_TAG = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val SEASON = Regex("""(?i)[\s:–—\-]*\b(?:season|sezonul|sezon|sez\.?|staffel|temporada|saison)\s*(\d{1,2})\b.*$""")
    private val PRIME_INLINE_EP = Regex("""(?i)\b(?:season|sezonul|sezon|sez\.?)\s*(\d{1,2})\s*[,·•\-:\s]+\s*(?:ep\.?|episode|episodul|episod)\s*(\d{1,3})\b""")
    private val PRIME_LD_EPISODE = Regex(""""episodeNumber"\s*:\s*"?(\d{1,3})"?""")
    private val PRIME_LD_SEQ = Regex(""""(?:episodeNumber|sequenceNumber)"\s*:\s*"?(\d{1,3})"?""")
    private val PRIME_LD_SEASON = Regex(""""seasonNumber"\s*:\s*"?(\d{1,2})"?""")

    fun pageTitle(html: String): String? {
        val raw = OG_TITLE.find(html)?.groupValues?.get(1)
            ?: OG_TITLE_REV.find(html)?.groupValues?.get(1)
            ?: TITLE_TAG.find(html)?.groupValues?.get(1)
            ?: return null
        return unescape(raw).trim().takeIf { it.isNotEmpty() }
    }

    /** „Watch The Boys - Season 4 | Prime Video” → The Boys, sezonul 4 (+ episodul, dacă îl găsim lângă cod). */
    fun prime(html: String, gti: String): ServiceTitle? {
        val rawTitle = pageTitle(html) ?: return null
        var t = rawTitle
            .replace(Regex("""(?i)\s*[|–—-]\s*(amazon\.com\s*:\s*|amazon\s+)?prime\s*video.*$"""), "")
            .replace(Regex("""(?i)^(amazon\.com:\s*|prime video:\s*|watch\s+|vizioneaz[aă]\s+|urm[aă]re[sș]te\s+)"""), "")
            .trim()
        val inline = PRIME_INLINE_EP.find(t)
        val season = inline?.groupValues?.get(1)?.toIntOrNull()
            ?: SEASON.find(t)?.groupValues?.get(1)?.toIntOrNull()
            ?: PRIME_LD_SEASON.find(html)?.groupValues?.get(1)?.toIntOrNull()
        val show = t
            .replace(PRIME_INLINE_EP, "")
            .replace(SEASON, "")
            .trim(' ', ':', '-', '–', '—', ',')
            .trim()
        if (show.isEmpty() || show.equals("prime video", ignoreCase = true) || show.equals("amazon.com", ignoreCase = true)) {
            return null
        }
        val ep = inline?.groupValues?.get(2)?.toIntOrNull()
            ?: episodeNear(html, gti)
            ?: PRIME_LD_EPISODE.find(html)?.groupValues?.get(1)?.toIntOrNull()
        return ServiceTitle(show, season, ep, null)
    }

    /** În JSON-ul paginii, numărul episodului e în apropierea codului lui. */
    private fun episodeNear(html: String, gti: String): Int? {
        var idx = html.indexOf(gti)
        while (idx >= 0) {
            val from = (idx - 1500).coerceAtLeast(0)
            val to = minOf(html.length, idx + 4000)
            val window = html.substring(from, to)
            PRIME_LD_SEQ.find(window)?.let { return it.groupValues[1].toInt() }
            idx = html.indexOf(gti, idx + gti.length)
        }
        return null
    }

    /** „Stranger Things | Netflix Official Site” / „Watch Stranger Things | Netflix” → Stranger Things. */
    fun netflix(html: String): ServiceTitle? {
        val t = pageTitle(html) ?: return null
        val show = t.replace(Regex("""(?i)\s*[|–—-]\s*netflix.*$"""), "")
            .replace(Regex("""(?i)^(watch\s+|vizioneaz[aă]\s+)"""), "")
            .trim()
        if (show.isEmpty() || show.equals("netflix", ignoreCase = true)) return null
        return ServiceTitle(show)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    /** Câmpuri text de adăugat la eveniment, ca [TitleParser] să le folosească. */
    fun asFields(t: ServiceTitle): Map<String, String> = buildMap {
        put("title", t.show)
        val s = t.season
        val e = t.episode
        when {
            s != null && e != null -> put("display_subtitle", "S$s:E$e" + (t.episodeTitle?.let { " $it" } ?: ""))
            e != null -> put("display_subtitle", "Episodul $e")
            t.episodeTitle != null -> put("display_subtitle", t.episodeTitle)
        }
        if (s != null && e == null) put("season_hint", s.toString())
    }
}

/**
 * Află titlul din codul trimis de aplicație (Prime Video GTI, Netflix id), de pe paginile publice
 * ale serviciilor. Rezultatele (și eșecurile) se țin în memorie, ca să nu cerem aceeași pagină
 * la fiecare minut de redare.
 */
@Singleton
class ServiceTitleResolver @Inject constructor(http: OkHttpClient) {
    private val client = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).followRedirects(true).build()
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, Optional>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Optional>?) = size > 500
        },
    )

    private data class Optional(val value: ServiceTitle?, val at: Long, val note: String, val transient: Boolean = false)

    /** Ce s-a întâmplat la ultima căutare (pentru jurnalul din Setări): „Prime: The Boys S4E3”, „Prime: HTTP 403”… */
    fun noteFor(event: ScrobbleEvent): String? = keyFor(event)?.let { cache[it]?.note }

    private fun keyFor(event: ScrobbleEvent): String? {
        val gti = event.fields.values.firstNotNullOfOrNull { ServicePages.PRIME_GTI.find(it)?.value }
        val netflixId = event.fields["netflix_id"]?.takeIf { ServicePages.NETFLIX_ID.matches(it) }
        return when {
            gti != null -> "prime:$gti"
            netflixId != null -> "netflix:$netflixId"
            else -> null
        }
    }

    /** Câmpurile de adăugat la eveniment, sau gol dacă nu avem cod / nu am găsit nimic. */
    suspend fun fieldsFor(event: ScrobbleEvent): Map<String, String> {
        val gti = event.fields.values.firstNotNullOfOrNull { ServicePages.PRIME_GTI.find(it)?.value }
        val netflixId = event.fields["netflix_id"]?.takeIf { ServicePages.NETFLIX_ID.matches(it) }
        val title = when {
            gti != null -> cached("prime:$gti") {
                val (html, httpNote) = get("https://www.primevideo.com/detail/$gti/")
                val parsed = html?.let { ServicePages.prime(it, gti) }
                if (parsed != null) parsed to httpNote else {
                    val (fbHtml, fbNote) = get("https://www.amazon.com/gp/video/detail/$gti/")
                    (fbHtml?.let { ServicePages.prime(it, gti) }) to "$httpNote / amazon: $fbNote"
                }
            }
            netflixId != null -> cached("netflix:$netflixId") {
                val (html, httpNote) = get("https://www.netflix.com/title/$netflixId")
                (html?.let { ServicePages.netflix(it) }) to httpNote
            }
            else -> null
        } ?: return emptyMap()
        return ServicePages.asFields(title)
    }

    private suspend fun cached(key: String, load: suspend () -> Pair<ServiceTitle?, String>): ServiceTitle? {
        val now = System.currentTimeMillis()
        cache[key]?.let { c ->
            // Eșecurile temporare (HTTP 503/429) se reîncearcă mai repede (2 min); altele după 30 min.
            val retryWindowMs = if (c.transient) TimeUnit.MINUTES.toMillis(2) else TimeUnit.MINUTES.toMillis(30)
            if (c.value != null || now - c.at < retryWindowMs) return c.value
        }
        val r = runCatching { load() }
        val pair = r.getOrNull()
        val v = pair?.first
        val httpNote = pair?.second.orEmpty()
        val transient = r.isFailure || httpNote.contains("HTTP 5") || httpNote.contains("HTTP 429")
        val service = key.substringBefore(':').replaceFirstChar { it.uppercase() }
        val note = when {
            v != null -> "$service: ${v.show}" + (v.season?.let { " S$it" } ?: "") + (v.episode?.let { "E$it" } ?: "")
            r.isFailure -> "$service: eroare ${r.exceptionOrNull()?.javaClass?.simpleName} ${r.exceptionOrNull()?.message?.take(60)}"
            else -> "$service: pagina nu are titlu $httpNote"
        }
        if (v == null) ro.ak.vodunify.core.diagnostics.AppDiagLog.add("ServiceTitleResolver ($key): $note")
        cache[key] = Optional(v, now, note, transient)
        return v
    }

    private suspend fun get(url: String): Pair<String?, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            .header("Accept-Language", "en-US,en;q=0.8,ro;q=0.6")
            .build()
        client.newCall(req).execute().use { r ->
            val httpNote = "(HTTP ${r.code} ${r.request.url.host}${r.request.url.encodedPath.take(40)})"
            (if (r.isSuccessful) r.body?.string() else null) to httpNote
        }
    }
}
