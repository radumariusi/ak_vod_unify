package ro.ak.vodunify.core.catalog

import ro.ak.vodunify.core.prefs.Market
import java.time.LocalDate

/** Ordinea rezultatelor în Descoperă. */
enum class DiscoverSort(val label: String) {
    POPULAR("Cele mai populare"),
    TOP_RATED("Cele mai bine notate"),
    NEWEST("Cele mai noi"),
    MOST_VOTED("Cele mai votate"),
}

/** Perioada de lansare: un an anume, un deceniu sau oricând. */
data class DiscoverPeriod(val label: String, val from: LocalDate?, val to: LocalDate?) {
    companion object {
        val ANY = DiscoverPeriod("Oricând", null, null)

        fun options(today: LocalDate = LocalDate.now()): List<DiscoverPeriod> = buildList {
            add(ANY)
            add(DiscoverPeriod("Ultimele 3 luni", today.minusMonths(3), today))
            val year = today.year
            (year downTo year - 9).forEach { y -> add(DiscoverPeriod("$y", LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31))) }
            listOf(2010, 2000, 1990, 1980, 1970).forEach { d ->
                add(DiscoverPeriod("Anii $d", LocalDate.of(d, 1, 1), LocalDate.of(d + 9, 12, 31)))
            }
            add(DiscoverPeriod("Înainte de 1970", null, LocalDate.of(1969, 12, 31)))
        }
    }
}

/** Serviciul de streaming filtrat, în biblioteca (piața) în care îl folosești. */
data class DiscoverService(val name: String, val providerIds: Set<Int>, val market: Market, val owned: Boolean) {
    val label: String get() = "$name (${market.code})"
}

data class DiscoverFilters(
    val kind: TitleKind = TitleKind.MOVIE,
    val sort: DiscoverSort = DiscoverSort.POPULAR,
    val genreId: Int? = null,
    val period: DiscoverPeriod = DiscoverPeriod.ANY,
    val service: DiscoverService? = null,
    val minRating: Int? = null,
    /** Țara de origine (ISO, ex. „US”, „KR”). */
    val country: String? = null,
    /** Limba originală (ISO 639-1, ex. „en”, „ko”). */
    val language: String? = null,
)

/** Construiește parametrii /discover din filtre (pur, testabil). */
object DiscoverQuery {
    fun params(f: DiscoverFilters, page: Int, apiLanguage: String, today: LocalDate = LocalDate.now()): Map<String, String?> {
        val movie = f.kind == TitleKind.MOVIE
        val dateField = if (movie) "primary_release_date" else "first_air_date"
        val sortBy = when (f.sort) {
            DiscoverSort.POPULAR -> "popularity.desc"
            DiscoverSort.TOP_RATED -> "vote_average.desc"
            DiscoverSort.NEWEST -> "$dateField.desc"
            DiscoverSort.MOST_VOTED -> "vote_count.desc"
        }
        // Notele contează doar cu destule voturi; „Cele mai noi” nu arată ce n-a apărut încă.
        val minVotes = when {
            f.sort == DiscoverSort.TOP_RATED -> 300
            f.minRating != null -> 100
            f.sort == DiscoverSort.NEWEST -> 5
            else -> 20
        }
        val to = listOfNotNull(f.period.to, if (f.sort == DiscoverSort.NEWEST) today else null).minOrNull()
        return mapOf(
            "language" to apiLanguage,
            "sort_by" to sortBy,
            "page" to page.toString(),
            "vote_count.gte" to minVotes.toString(),
            "with_genres" to f.genreId?.toString(),
            "$dateField.gte" to f.period.from?.toString(),
            "$dateField.lte" to to?.toString(),
            "vote_average.gte" to f.minRating?.toString(),
            "with_origin_country" to f.country,
            "with_original_language" to f.language,
            "with_watch_providers" to f.service?.providerIds?.sorted()?.joinToString("|"),
            "watch_region" to f.service?.market?.code,
            "with_watch_monetization_types" to f.service?.let { "flatrate|free|ads" },
        )
    }

    val countries: List<Pair<String?, String>> = listOf(
        null to "Oricare",
        "US" to "SUA",
        "GB" to "Marea Britanie",
        "RO" to "România",
        "KR" to "Coreea de Sud",
        "JP" to "Japonia",
        "FR" to "Franța",
        "DE" to "Germania",
        "ES" to "Spania",
        "IT" to "Italia",
        "SE" to "Suedia",
        "DK" to "Danemarca",
        "NO" to "Norvegia",
        "TR" to "Turcia",
        "IN" to "India",
        "CA" to "Canada",
        "AU" to "Australia",
        "MX" to "Mexic",
    )

    val languages: List<Pair<String?, String>> = listOf(
        null to "Oricare",
        "en" to "Engleză",
        "ro" to "Română",
        "ko" to "Coreeană",
        "ja" to "Japoneză",
        "es" to "Spaniolă",
        "fr" to "Franceză",
        "de" to "Germană",
        "it" to "Italiană",
        "tr" to "Turcă",
        "hi" to "Hindi",
        "sv" to "Suedeză",
        "da" to "Daneză",
    )
}
