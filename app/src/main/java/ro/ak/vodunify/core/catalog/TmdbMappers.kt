package ro.ak.vodunify.core.catalog

import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.tmdb.TmdbImages
import ro.ak.vodunify.core.tmdb.TmdbListItem
import ro.ak.vodunify.core.tmdb.TmdbProvider
import ro.ak.vodunify.core.tmdb.TmdbRegionProviders
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails

/** Conversii pure DTO → model de domeniu (testabile fără Android). */
object TmdbMappers {

    fun year(date: String?): Int? = date?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

    /** Alege titlul afișat după limba selectată. */
    fun displayTitle(translated: String?, original: String?, language: ContentLanguage): String {
        val primary = if (language == ContentLanguage.ORIGINAL) original else translated
        return primary?.takeIf { it.isNotBlank() } ?: translated ?: original ?: "Fără titlu"
    }

    /** `fallbackKind` se folosește pentru listele care nu au `media_type` (ex. /movie/popular). */
    fun item(dto: TmdbListItem, language: ContentLanguage, fallbackKind: TitleKind? = null): CatalogItem? {
        val kind = TitleKind.fromPath(dto.mediaType) ?: fallbackKind ?: return null
        val isMovie = kind == TitleKind.MOVIE
        return CatalogItem(
            id = dto.id,
            kind = kind,
            title = displayTitle(
                if (isMovie) dto.title else dto.name,
                if (isMovie) dto.originalTitle else dto.originalName,
                language,
            ),
            year = year(if (isMovie) dto.releaseDate else dto.firstAirDate),
            overview = dto.overview.orEmpty(),
            posterUrl = TmdbImages.poster(dto.posterPath),
            backdropUrl = TmdbImages.backdrop(dto.backdropPath),
            rating = dto.voteAverage?.takeIf { it > 0 },
            genreIds = dto.genreIds,
            note = dto.character ?: dto.job,
        )
    }

    fun items(list: List<TmdbListItem>, language: ContentLanguage, fallbackKind: TitleKind? = null): List<CatalogItem> =
        list.filterNot { it.adult }.mapNotNull { item(it, language, fallbackKind) }.distinctBy { it.mediaKey }

    fun provider(p: TmdbProvider) = ProviderInfo(p.providerId, p.providerName, TmdbImages.logo(p.logoPath))

    private fun providers(list: List<TmdbProvider>) =
        list.sortedBy { it.displayPriority }.distinctBy { it.providerId }.map(::provider)

    fun availability(market: Market, region: TmdbRegionProviders?): MarketAvailability = MarketAvailability(
        market = market,
        link = region?.link,
        flatrate = providers(region?.flatrate.orEmpty()),
        free = providers(region?.free.orEmpty() + region?.ads.orEmpty()),
        rent = providers(region?.rent.orEmpty()),
        buy = providers(region?.buy.orEmpty()),
    )

    /** Piața selectată prima, apoi celelalte (RO/US). */
    fun availabilityFor(details: TmdbTitleDetails, selected: Market): List<MarketAvailability> {
        val results = details.watchProviders?.results.orEmpty()
        return (listOf(selected) + Market.entries.filter { it != selected })
            .map { availability(it, results[it.code]) }
    }

    /**
     * Serviciile principale dintr-o piață, după prioritatea TMDb pentru regiune.
     * Variantele „with Ads” / „Amazon Channel” sunt eliminate ca să nu dubleze rândurile.
     */
    fun topProviders(all: List<TmdbProvider>, market: Market, count: Int): List<ProviderInfo> = all
        .filterNot { p ->
            val n = p.providerName.lowercase()
            "with ads" in n || "channel" in n || "standard" in n || "basic" in n
        }
        .sortedBy { it.displayPriorities[market.code] ?: (1000 + it.displayPriority) }
        .distinctBy { it.providerName.lowercase() }
        .take(count)
        .map(::provider)

    /** Amestecă două liste (ex. filme + seriale pe același serviciu). */
    fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            if (i < a.size) out += a[i]
            if (i < b.size) out += b[i]
        }
        return out
    }
}
