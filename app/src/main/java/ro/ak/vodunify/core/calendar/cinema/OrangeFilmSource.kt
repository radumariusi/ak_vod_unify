package ro.ak.vodunify.core.calendar.cinema

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.core.net.readResponse
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Orange Film (orange.ro/info/film): this week's program in a city, for every chain there
 * (Cinema City, Cineplexx, Happy Cinema, Hollywood Multiplex, Movieplex, Inspire, Cinemax, …),
 * with "Premiera. DD.MM.YYYY" for each film. No dates ahead of this week.
 */
class OrangeFilmSource(
    private val http: OkHttpClient,
    private val cities: List<String> = DEFAULT_CITIES,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : RoCinemaSource {

    override val name: String = "Orange Film"

    override suspend fun releases(today: LocalDate): List<RoCinemaRelease> = coroutineScope {
        val pages = cities.map { city ->
            async {
                try {
                    fetch(baseUrl + "acum-la-cinema/" + URLEncoder.encode(city, "UTF-8").replace("+", "%20"))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }.awaitAll()
        if (pages.all { it == null }) throw IOException("Orange Film: nicio pagină nu s-a încărcat")
        pages.filterNotNull()
            .flatMap { parse(it) }
            // The site keeps a 2015 sample block on every page: only recent premieres count.
            .filter { !it.date.isBefore(today.minusDays(MAX_AGE_DAYS)) && !it.date.isAfter(today.plusDays(60)) }
            .distinctBy { RoCinemaProgram.normalize(it.title) }
    }

    private suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()).readResponse { r ->
            if (!r.isSuccessful) throw IOException("Orange Film: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.orange.ro/info/film/"
        /** The big cities: together they cover every chain (Inspire is in Craiova, Alba Iulia, …). */
        val DEFAULT_CITIES = listOf("Bucuresti", "Cluj", "Iasi", "Timisoara", "Constanta", "Brasov", "Craiova")
        private const val MAX_AGE_DAYS = 120L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

        private val filmLink = Regex(
            """<a\b[^>]*href\s*=\s*["']([^"']*/info/film/info/[^"'/]+/(\d+)[^"']*)["'][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val premiere = Regex("""Premiera\s*\.?\s*:?\s*(\d{1,2})\.(\d{1,2})\.(\d{4})""", RegexOption.IGNORE_CASE)
        private val original = Regex("""^\s*\(([^()]{2,150})\)\s*$""", RegexOption.MULTILINE)

        /** Films on one city page: title, original title in brackets, premiere date. */
        fun parse(html: String): List<RoCinemaRelease> {
            val links = filmLink.findAll(html).toList()
            if (links.isEmpty()) return emptyList()
            // One block per film: from its first link to the next link of another film.
            val blocks = mutableListOf<Triple<String, Int, Int>>() // id, start, end
            for (m in links) {
                val id = m.groupValues[2]
                if (blocks.lastOrNull()?.first == id) continue
                blocks.lastOrNull()?.let { blocks[blocks.lastIndex] = it.copy(third = m.range.first) }
                blocks += Triple(id, m.range.first, html.length)
            }
            val out = mutableListOf<RoCinemaRelease>()
            for ((id, start, end) in blocks) {
                val title = links.filter { it.groupValues[2] == id && it.range.first in start until end }
                    .map { text(it.groupValues[3]).trim() }
                    .firstOrNull { it.isNotBlank() } ?: continue
                val block = text(html.substring(start, end))
                val dateMatch = premiere.find(block) ?: continue
                val (d, mo, y) = dateMatch.destructured
                val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: continue
                val orig = original.find(block.substring(0, dateMatch.range.first))?.groupValues?.get(1)?.trim()
                out += RoCinemaRelease(
                    title = title.removePrefix("film ").trim(),
                    originalTitle = orig?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) },
                    date = date,
                    source = "Orange Film",
                )
            }
            return out.distinctBy { it.title to it.date }
        }

        /** Tags to line breaks, entities decoded, blank lines dropped. */
        internal fun text(html: String): String =
            decode(
                html.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), " ")
                    .replace(Regex("<[^>]*>"), "\n"),
            ).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
        private val named = mapOf(
            "amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ",
            "acirc" to "â", "Acirc" to "Â", "icirc" to "î", "Icirc" to "Î", "abreve" to "ă", "Abreve" to "Ă",
            "scedil" to "ş", "Scedil" to "Ş", "tcedil" to "ţ", "Tcedil" to "Ţ", "ndash" to "–", "mdash" to "—",
            "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "bdquo" to "„", "hellip" to "…",
        )

        internal fun decode(s: String): String = entity.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> named[e]
            } ?: m.value
        }
    }
}
