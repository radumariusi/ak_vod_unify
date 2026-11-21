package ro.ak.vodunify.core.catalog

import ro.ak.vodunify.core.prefs.Market

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
) {
    /** Cheie stabilă comună cu tabela `media` (ex. "movie:tmdb:603"). */
    val mediaKey: String get() = mediaKey(kind, id)

    val subtitle: String
        get() = listOfNotNull(year?.toString(), kind.label, note?.takeIf { it.isNotBlank() }).joinToString(" · ")
}

fun mediaKey(kind: TitleKind, id: Long) = "${kind.path}:tmdb:$id"

/** Un episod cu data lui de difuzare (ultimul apărut sau următorul). */
data class EpisodeAir(val season: Int, val episode: Int, val name: String?, val airDate: String?) {
    val code: String get() = "S%02dE%02d".format(season, episode)
}

/** Titlul plus datele de lansare: data filmului, ultimul și următorul episod la seriale. */
data class TitleReleases(
    val item: CatalogItem,
    val releaseDate: String?,
    val lastEpisode: EpisodeAir?,
    val nextEpisode: EpisodeAir?,
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
)

/** Un rând „Pe Netflix (RO)” din Acasă: serviciul tău, în biblioteca lui. */
data class ServiceShelf(val name: String, val providerIds: Set<Int>, val market: Market)

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

data class CatalogRow(val id: String, val title: String, val items: List<CatalogItem>)

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
