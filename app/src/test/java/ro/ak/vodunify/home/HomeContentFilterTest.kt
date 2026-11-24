package ro.ak.vodunify.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRow
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleOrigin
import ro.ak.vodunify.ui.home.HomeContentFilter
import ro.ak.vodunify.ui.home.HomePopularityChart
import ro.ak.vodunify.ui.home.PersonalItem
import ro.ak.vodunify.ui.home.PersonalRows
import ro.ak.vodunify.ui.home.PersonalState

class HomeContentFilterTest {
    @Test fun hiddenProductionNeedsARegionalRankingOutsideItsOrigin() {
        val chart = HomePopularityChart(titles = mapOf(
            "movie:hit local" to mapOf("IN" to 1, "US" to 8),
            "movie:departe de casa" to mapOf("GB" to 7, "RO" to 9),
            "tv:serial popular" to mapOf("FR" to 3),
        ))
        val filter = HomeContentFilter(setOf(ContentFilter.INDIAN, ContentFilter.TURKISH), chart)
        val indian = TitleOrigin("hi", listOf("IN"), null)
        assertFalse(filter.allows(TitleKind.MOVIE, "Hit local", indian, emptyList()))
        assertTrue(filter.allows(TitleKind.MOVIE, "Departe de casă!", indian, emptyList()))
        assertTrue(filter.allows(TitleKind.TV, "Serial Popular", TitleOrigin("tr", listOf("TR")), emptyList()))
        assertFalse(filter.allows(TitleKind.MOVIE, "Serial Popular", indian, emptyList()))
        assertTrue(filter.copy(hidden = emptySet()).allows(TitleKind.MOVIE, "Hit local", indian, emptyList()))
    }

    @Test fun calendarCategoriesOnlyChangeTheRequestedHomeRows() {
        val filter = HomeContentFilter(setOf(ContentFilter.CHINESE))
        val origin = TitleOrigin("zh")
        val title = CatalogItem(1, TitleKind.MOVIE, "Film", 2026, "", null, null, 9.8, origin = origin)
        val popular = CatalogRow("movies", "Filme populare", listOf(title))
        val trending = CatalogRow("trending_movies", "Filme în tendințe", listOf(title))
        val service = CatalogRow("service_voyo_RO", "Pe VOYO (RO)", listOf(title))
        val personal = PersonalItem("test", TitleKind.MOVIE, 1, "Film", null, null, null, null, false, origin = origin)
        val home = filter.catalog(HomeCatalog(listOf(title), listOf(popular, trending, service)))
        assertEquals(listOf(service), home.rows)
        assertEquals(emptyList<CatalogItem>(), home.hero)
        val rows = filter.personal(PersonalRows(PersonalState.CONNECTED, continueMovies = listOf(personal),
            newMovies = listOf(personal), soonMovies = listOf(personal)))
        assertEquals(listOf(personal), rows.continueMovies)
        assertTrue(rows.newMovies.isEmpty())
        assertTrue(rows.soonMovies.isEmpty())
    }
}
