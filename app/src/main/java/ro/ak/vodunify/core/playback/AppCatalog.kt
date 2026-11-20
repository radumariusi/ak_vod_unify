package ro.ak.vodunify.core.playback

import java.net.URLEncoder

/** Serviciu de streaming oficial (abonament), recunoscut din TMDb Watch Providers. */
enum class StreamingService(
    val id: String,
    val displayName: String,
    /** Id-urile TMDb ale furnizorului (inclusiv variantele „cu reclame”, canale, magazin). */
    val tmdbProviderIds: Set<Int>,
    val appId: String,
) {
    NETFLIX("netflix", "Netflix", setOf(8, 175, 1796), "netflix"),
    PRIME_VIDEO("prime", "Prime Video", setOf(9, 10, 119, 2100), "primevideo"),
    DISNEY_PLUS("disney", "Disney+", setOf(337, 390), "disneyplus"),
    HBO_MAX("hbomax", "HBO Max", setOf(384, 1899, 1825), "hbomax"),
    ;

    companion object {
        fun byId(id: String): StreamingService? = entries.firstOrNull { it.id == id }

        /** Potrivire după id TMDb, apoi după nume (TMDb mai adaugă variante noi). */
        fun fromProvider(providerId: Int, providerName: String): StreamingService? {
            entries.firstOrNull { providerId in it.tmdbProviderIds }?.let { return it }
            val n = providerName.lowercase().trim()
            return when {
                "netflix" in n -> NETFLIX
                "prime video" in n || n == "amazon video" -> PRIME_VIDEO
                "disney" in n -> DISNEY_PLUS
                n == "max" || "hbo max" in n || n.startsWith("max ") || n == "hbo" -> HBO_MAX
                else -> null
            }
        }
    }
}

enum class AppRole { OFFICIAL, PLAYER }

/** O aplicație-țintă pe care o putem detecta și deschide. */
enum class TargetApp(
    val id: String,
    val displayName: String,
    val role: AppRole,
    /** Pachete cunoscute (TV întâi, apoi telefon). Trebuie declarate și în `<queries>` din manifest. */
    val packages: List<String>,
    /** Folosite când pachetul e necunoscut/neoficial: potrivire după numele aplicației din launcher. */
    val labelHints: List<String> = emptyList(),
) {
    NETFLIX("netflix", "Netflix", AppRole.OFFICIAL, listOf("com.netflix.ninja", "com.netflix.mediaclient")),
    PRIME_VIDEO(
        "primevideo", "Prime Video", AppRole.OFFICIAL,
        listOf("com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod.thirdpartyclient", "com.amazon.avod"),
    ),
    DISNEY_PLUS("disneyplus", "Disney+", AppRole.OFFICIAL, listOf("com.disney.disneyplus")),
    HBO_MAX("hbomax", "HBO Max", AppRole.OFFICIAL, listOf("com.wbd.stream", "com.hbo.hbonow")),
    NUVIO("nuvio", "Nuvio", AppRole.PLAYER, listOf("com.nuvio.tv", "com.nuvio.app"), listOf("nuvio")),
    STREMIO("stremio", "Stremio", AppRole.PLAYER, listOf("com.stremio.one"), listOf("stremio")),
    DEBRID_STREAM(
        "debridstream", "DebridStream", AppRole.PLAYER,
        listOf("com.debridstream.tv", "com.debridstream.app", "com.debridstream"),
        listOf("debridstream", "debrid stream"),
    ),
    ;

    companion object {
        fun byId(id: String): TargetApp? = entries.firstOrNull { it.id == id }
        val players: List<TargetApp> get() = entries.filter { it.role == AppRole.PLAYER }
    }
}

/**
 * Linkurile candidate pentru o aplicație, de la cel mai precis la cel mai slab.
 * Funcție pură: validarea (dacă aplicația instalată acceptă linkul) o face [TargetResolver].
 *
 * Formatele:
 * - Stremio: `stremio:///detail/{movie|series}/{imdb}/{videoId}`, videoId pentru episod = `{imdb}:{sezon}:{episod}`.
 * - Nuvio (NuvioTV DeepLinkParser): `nuvio://movie/{imdb}`, `nuvio://series/{imdb}`, `nuvio://tmdb/{tip}/{id}`.
 *   Nuvio deschide pagina titlului; nu are link direct la episod.
 * - Netflix / Prime Video / Disney+ / HBO Max: nu există un id public al titlului în TMDb,
 *   deci folosim căutarea după nume în aplicație (unde e acceptată), apoi simpla pornire.
 * - DebridStream: format nedocumentat; încercăm schemele compatibile Stremio/Nuvio, apoi pornirea.
 */
object LinkBuilder {
    fun candidates(app: TargetApp, r: PlaybackRequest): List<LinkCandidate> = when (app) {
        TargetApp.STREMIO -> stremio(r)
        TargetApp.NUVIO -> nuvio(r)
        TargetApp.DEBRID_STREAM -> stremio(r).filter { it.precision != LinkPrecision.SEARCH } + nuvio(r)
        TargetApp.NETFLIX -> search(r, "https://www.netflix.com/search?q=")
        TargetApp.PRIME_VIDEO -> search(r, "https://app.primevideo.com/search?phrase=", "https://www.primevideo.com/search?phrase=")
        TargetApp.DISNEY_PLUS -> search(r, "https://www.disneyplus.com/search?q=")
        TargetApp.HBO_MAX -> search(r, "https://play.hbomax.com/search?q=", "https://play.max.com/search?q=")
    }

    private fun stremio(r: PlaybackRequest): List<LinkCandidate> {
        val imdb = r.imdbId?.takeIf { it.startsWith("tt") }
            ?: return listOf(LinkCandidate(LinkPrecision.SEARCH, "stremio:///search?search=${enc(r.title)}"))
        return when (r.type) {
            PlaybackRequest.Type.MOVIE -> listOf(LinkCandidate(LinkPrecision.TITLE, "stremio:///detail/movie/$imdb/$imdb"))
            PlaybackRequest.Type.SHOW -> listOf(LinkCandidate(LinkPrecision.SERIES, "stremio:///detail/series/$imdb"))
            PlaybackRequest.Type.EPISODE -> buildList {
                if (r.season != null && r.episode != null) {
                    add(LinkCandidate(LinkPrecision.EPISODE, "stremio:///detail/series/$imdb/$imdb:${r.season}:${r.episode}"))
                }
                add(LinkCandidate(LinkPrecision.SERIES, "stremio:///detail/series/$imdb"))
            }
        }
    }

    private fun nuvio(r: PlaybackRequest): List<LinkCandidate> {
        val isMovie = r.type == PlaybackRequest.Type.MOVIE
        val precision = if (isMovie) LinkPrecision.TITLE else LinkPrecision.SERIES
        val type = if (isMovie) "movie" else "series"
        return buildList {
            r.imdbId?.takeIf { it.startsWith("tt") }?.let { add(LinkCandidate(precision, "nuvio://$type/$it")) }
            add(LinkCandidate(precision, "nuvio://tmdb/$type/${r.tmdbId}"))
        }
    }

    private fun search(r: PlaybackRequest, vararg prefixes: String): List<LinkCandidate> =
        prefixes.map { LinkCandidate(LinkPrecision.SEARCH, it + enc(r.title)) }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
