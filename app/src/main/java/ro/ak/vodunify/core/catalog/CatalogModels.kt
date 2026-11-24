package ro.ak.vodunify.core.catalog

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.playback.StreamingService

enum class TitleKind(val path: String, val label: String) {
    MOVIE("movie", "Film"),
    TV("tv", "Serial"),
    ;

    companion object {
        fun fromPath(path: String?): TitleKind? = entries.firstOrNull { it.path == path }
    }
}

enum class StudioKind(val path: String, val label: String) {
    COMPANY("company", "Studio"),
    NETWORK("network", "Rețea TV"),
    ;

    companion object {
        fun fromPath(path: String?): StudioKind? = entries.firstOrNull { it.path == path }
    }
}

@Serializable
data class CatalogItem(
    val id: Long,
    val kind: TitleKind,
    val title: String,
    val year: Int?,
    val overview: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val genreIds: List<Int> = emptyList(),
    /** Ex. personajul jucat, pentru filmografia unei persoane. */
    val note: String? = null,
    /** Numărul de episoade (doar la seriale, din pagina titlului). */
    val episodeCount: Int? = null,
    val origin: TitleOrigin = TitleOrigin(),
) {
    /** Cheie stabilă comună cu tabela `media` (ex. "movie:tmdb:603"). */
    val mediaKey: String get() = mediaKey(kind, id)

    val subtitle: String
        get() = listOfNotNull(year?.toString(), kind.label, note?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

@Serializable
data class TitleOrigin(
    val language: String? = null,
    val countries: List<String> = emptyList(),
    val originalTitle: String? = null,
    val type: String? = null,
)

fun mediaKey(kind: TitleKind, id: Long) = "${kind.path}:tmdb:$id"

/** Un episod cu data lui de difuzare (ultimul apărut sau următorul). */
data class EpisodeAir(val season: Int, val episode: Int, val name: String?, val airDate: String?) {
    val code: String get() = "S%02dE%02d".format(season, episode)
}

/** Pe unde apare un film: în cinema, pe un serviciu de streaming sau digital (închiriere / cumpărare). */
enum class ReleaseChannel {
    CINEMA, STREAMING, DIGITAL,

    /** Știm doar data din TMDb, nu și unde apare. */
    UNKNOWN,
}

/**
 * O lansare a unui film, pentru „În curând” / „Noutăți”: unde, în ce țară (RO / US), când și, la streaming,
 * pe ce serviciu ([serviceId] = id-ul din calendar, ex. „netflix”; [mine] = e pe aplicațiile tale).
 */
@Serializable
data class MovieReleaseEvent(
    val channel: ReleaseChannel,
    val region: String,
    val date: String,
    val serviceId: String? = null,
    val serviceName: String? = null,
    val mine: Boolean = false,
)

/** Titlul plus datele de lansare: data filmului, ultimul și următorul episod la seriale. */
data class TitleReleases(
    val item: CatalogItem,
    val releaseDate: String?,
    val lastEpisode: EpisodeAir?,
    val nextEpisode: EpisodeAir?,
    /** Lansările filmului (cinema / streaming / digital, RO și US); goale = știm doar [releaseDate]. */
    val movieReleases: List<MovieReleaseEvent> = emptyList(),
)

data class PersonSummary(
    val id: Long,
    val name: String,
    val profileUrl: String?,
    /** Rolul (personaj) sau funcția (regie, scenariu). */
    val role: String?,
)

data class StudioRef(val id: Long, val kind: StudioKind, val name: String, val logoUrl: String?)

data class ProviderInfo(val id: Int, val name: String, val logoUrl: String?)

data class MarketAvailability(
    val market: Market,
    /** Pagina TMDb/JustWatch cu toate ofertele pentru piață. */
    val link: String?,
    val flatrate: List<ProviderInfo>,
    val free: List<ProviderInfo>,
    val rent: List<ProviderInfo>,
    val buy: List<ProviderInfo>,
) {
    val isEmpty: Boolean get() = flatrate.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
}

data class SeasonSummary(val number: Int, val name: String, val episodeCount: Int, val year: Int?)

data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillUrl: String?,
    val airDate: String?,
    val runtimeMinutes: Int?,
    /** Nota TMDb a episodului. */
    val rating: Double? = null,
    /** Nota IMDb a episodului (prin OMDb, dacă e setată cheia). */
    val imdbRating: Double? = null,
)

data class Season(val number: Int, val name: String, val overview: String, val episodes: List<Episode>)

data class TitleDetails(
    val item: CatalogItem,
    val originalTitle: String?,
    val tagline: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val seasonCount: Int?,
    val episodeCount: Int?,
    val status: String?,
    val voteCount: Int?,
    val cast: List<PersonSummary>,
    val crew: List<PersonSummary>,
    val studios: List<StudioRef>,
    val seasons: List<SeasonSummary>,
    /** RO și US; fiecare serviciu contează doar în biblioteca lui. */
    val availability: List<MarketAvailability>,
    val recommendations: List<CatalogItem>,
    val imdbId: String?,
    val trailerYoutubeKey: String?,
    val nextEpisode: String?,
    /** Sigla titlului (TMDb logo); null = titlul se scrie cu text. */
    val logoUrl: String? = null,
    val lastAiredEpisode: EpisodeAir? = null,
)

/** Biblioteca unui serviciu, independent de aplicațiile instalate. */
data class ServiceLibrary(val service: StreamingService, val market: Market) {
    val region: String get() = if (service == StreamingService.ARTE) "FR" else market.code
}

/** Un rând „Pe Netflix (RO)” din Acasă pentru o aplicație instalată. */
data class ServiceShelf(val service: StreamingService, val packageName: String, val market: Market) {
    val library: ServiceLibrary get() = ServiceLibrary(service, market)
    val rowId: String get() = "service_${service.id}_${library.region}"
    val rowTitle: String get() = "Pe ${service.displayName} (${library.region})"

    fun row(items: List<CatalogItem> = emptyList(), status: ServiceCatalogStatus? = null) =
        CatalogRow(
            rowId, rowTitle, items, status?.let { ServiceFallback(packageName, it) },
            service = if (items.isEmpty()) null else CatalogService(service.id, "${service.displayName} (${library.region})", packageName, market),
        )
}

data class ServiceCatalogPage(val items: List<CatalogItem>, val status: ServiceCatalogStatus? = null)

/** Lista furnizorilor pentru tipul de titlu și piața cerută are prioritate peste ID-urile de rezervă. */
internal fun homeProviderIds(service: StreamingService, directory: List<ProviderInfo>?): Set<Int> =
    directory?.filter { StreamingService.fromProvider(it.id, it.name) == service }
        ?.mapTo(linkedSetOf()) { it.id }
        ?: service.tmdbProviderIds

data class PersonDetails(
    val id: Long,
    val name: String,
    val biography: String,
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val profileUrl: String?,
    val department: String?,
    val movies: List<CatalogItem>,
    val shows: List<CatalogItem>,
    /** Filmografia, filme și seriale împreună, după popularitate. */
    val credits: List<CatalogItem> = movies + shows,
)

data class StudioDetails(
    val ref: StudioRef,
    val description: String?,
    val headquarters: String?,
    val country: String?,
    val movies: List<CatalogItem>,
    val shows: List<CatalogItem>,
)

@Serializable
data class CatalogRow(
    val id: String,
    val title: String,
    val items: List<CatalogItem>,
    val serviceFallback: ServiceFallback? = null,
    val service: CatalogService? = null,
)

/** Platforma confirmată pentru titlurile din rând. Biblioteca aleasă stabilește ordinea iconiței. */
@Serializable
data class CatalogService(val id: String, val label: String, val packageName: String, val market: Market)

@Serializable
data class ServiceFallback(val packageName: String, val reason: ServiceCatalogStatus)

enum class ServiceCatalogStatus { CHECKING, NO_PROVIDER, NO_TITLES, UNAVAILABLE }

@Serializable
data class HomeCatalog(val hero: List<CatalogItem>, val rows: List<CatalogRow>)

data class Genre(val id: Int, val name: String)

enum class SearchType(val label: String) {
    ALL("Toate"),
    MOVIE("Filme"),
    TV("Seriale"),
    PERSON("Persoane"),
}

data class SearchFilters(
    val type: SearchType = SearchType.ALL,
    val genreId: Int? = null,
    val year: Int? = null,
) {
    val isEmpty: Boolean get() = type == SearchType.ALL && genreId == null && year == null
}

sealed interface SearchResult {
    data class Title(val item: CatalogItem) : SearchResult
    data class Person(val person: PersonSummary, val knownFor: String?) : SearchResult
}

data class SearchPage(val results: List<SearchResult>, val page: Int, val totalPages: Int)
