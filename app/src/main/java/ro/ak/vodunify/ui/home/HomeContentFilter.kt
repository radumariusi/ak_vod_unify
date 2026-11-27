package ro.ak.vodunify.ui.home

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.calendar.filter.ContentClassifier
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleOrigin
import java.text.Normalizer
import java.util.Locale

/** Rankings from the published Netflix country Top 10s. */
@Serializable
internal data class HomePopularityChart(
    val week: String = "",
    val titles: Map<String, Map<String, Int>> = emptyMap(),
) {
    fun isPopularAbroad(kind: TitleKind, title: String, origin: TitleOrigin): Boolean {
        val countriesOfOrigin = origin.countries.map { it.uppercase(Locale.ROOT) }.toSet()
        return sequenceOf(origin.originalTitle, title).filterNotNull().distinct().any { name ->
            val ranks = titles["${kind.path}:${normalize(name)}"].orEmpty()
                .filter { (country, rank) -> country !in countriesOfOrigin && rank in 1..10 }
            ranks.size >= 2 || ranks.values.any { it <= 3 }
        }
    }

    private companion object {
        val marks = Regex("\\p{M}+")
        val nonLettersOrNumbers = Regex("[^\\p{L}\\p{N}]+")

        fun normalize(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "").replace(nonLettersOrNumbers, " ").trim()
    }
}

internal data class HomeContentFilter(
    val hidden: Set<ContentFilter> = ContentFilter.defaultHidden,
    val chart: HomePopularityChart = HomePopularityChart(),
) {
    fun allows(kind: TitleKind, title: String, origin: TitleOrigin, genres: List<Int>): Boolean =
        ContentClassifier.classify(origin.language.orEmpty(), origin.countries, genres, origin.type)
            .none { it in hidden } || chart.isPopularAbroad(kind, title, origin)

    fun catalog(catalog: HomeCatalog): HomeCatalog {
        fun allowed(item: ro.ak.vodunify.core.catalog.CatalogItem) = allows(item.kind, item.title, item.origin, item.genreIds)
        return catalog.copy(
            hero = catalog.hero.filter(::allowed),
            rows = catalog.rows.mapNotNull { row ->
                if (row.id !in recommendations) return@mapNotNull row
                val items = row.items.filter(::allowed)
                row.copy(items = items).takeIf { items.isNotEmpty() }
            },
        )
    }

    fun personal(rows: PersonalRows): PersonalRows {
        fun allowed(item: PersonalItem) = allows(item.kind, item.title, item.origin, item.genreIds)
        return rows.copy(
            newMovies = rows.newMovies.filter(::allowed),
            newEpisodes = rows.newEpisodes.filter(::allowed),
            soonMovies = rows.soonMovies.filter(::allowed),
            soonEpisodes = rows.soonEpisodes.filter(::allowed),
        )
    }

    private companion object {
        val recommendations = setOf("movies", "shows", "trending_movies", "trending_shows")
    }
}
