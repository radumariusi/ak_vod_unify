package ro.ak.vodunify.catalog

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TmdbMappers
import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.tmdb.TmdbAuth
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbImages
import ro.ak.vodunify.core.tmdb.TmdbListItem
import ro.ak.vodunify.core.tmdb.TmdbPage
import ro.ak.vodunify.core.tmdb.TmdbProvider
import ro.ak.vodunify.core.tmdb.TmdbTitleDetails

class TmdbMappersTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test fun detectsCredentialType() {
        assertEquals(TmdbAuth.Missing, TmdbAuth.from(""))
        assertEquals(TmdbAuth.Missing, TmdbAuth.from(null))
        assertTrue(TmdbAuth.from("0123456789abcdef0123456789ABCDEF") is TmdbAuth.ApiKey)
        assertEquals(TmdbAuth.Bearer("eyJhbGciOi.x.y"), TmdbAuth.from("Bearer eyJhbGciOi.x.y"))
    }

    @Test fun cacheKeyIncludesLanguageAndRegionSorted() {
        val a = TmdbClient.cacheKey("/movie/popular", mapOf("region" to "RO", "language" to "ro-RO"))
        val b = TmdbClient.cacheKey("movie/popular", mapOf("language" to "ro-RO", "region" to "RO"))
        assertEquals(a, b)
        assertEquals("movie/popular?language=ro-RO&region=RO", a)
    }

    @Test fun imageUrls() {
        assertEquals("https://image.tmdb.org/t/p/w342/abc.jpg", TmdbImages.poster("/abc.jpg"))
        assertNull(TmdbImages.poster(null))
        assertNull(TmdbImages.poster(""))
    }

    @Test fun mapsListItemsByLanguage() {
        val page = json.decodeFromString(
            TmdbPage.serializer(TmdbListItem.serializer()),
            """
            {"page":1,"total_pages":3,"results":[
              {"id":1,"media_type":"movie","title":"Orașul","original_title":"La ciudad","release_date":"2024-05-01","vote_average":7.25,"poster_path":"/p.jpg"},
              {"id":2,"media_type":"tv","name":"Casa","original_name":"La casa","first_air_date":"2019-01-01"},
              {"id":3,"media_type":"person","name":"Cineva"},
              {"id":4,"media_type":"movie","title":"X","adult":true}
            ]}
            """.trimIndent(),
        )
        val ro = TmdbMappers.items(page.results, ContentLanguage.ROMANIAN)
        assertEquals(listOf("Orașul", "Casa"), ro.map { it.title })
        assertEquals(TitleKind.TV, ro[1].kind)
        assertEquals(2024, ro[0].year)
        assertEquals("movie:tmdb:1", ro[0].mediaKey)
        val orig = TmdbMappers.items(page.results, ContentLanguage.ORIGINAL)
        assertEquals(listOf("La ciudad", "La casa"), orig.map { it.title })
    }

    @Test fun fallbackKindForListsWithoutMediaType() {
        val dto = TmdbListItem(id = 9, name = "Serial")
        assertEquals(TitleKind.TV, TmdbMappers.item(dto, ContentLanguage.ROMANIAN, TitleKind.TV)?.kind)
        assertNull(TmdbMappers.item(dto, ContentLanguage.ROMANIAN))
    }

    @Test fun parsesWatchProvidersSelectedMarketFirst() {
        val details = json.decodeFromString(
            TmdbTitleDetails.serializer(),
            """
            {"id":603,"title":"Matrix","watch/providers":{"results":{
              "US":{"link":"https://www.themoviedb.org/movie/603/watch?locale=US",
                    "flatrate":[{"provider_id":1899,"provider_name":"Max","display_priority":2},
                                {"provider_id":8,"provider_name":"Netflix","display_priority":1}],
                    "rent":[{"provider_id":2,"provider_name":"Apple TV","display_priority":5}]},
              "RO":{"flatrate":[{"provider_id":8,"provider_name":"Netflix","display_priority":1}]}
            }}}
            """.trimIndent(),
        )
        val av = TmdbMappers.availabilityFor(details)
        assertEquals(listOf(Market.RO, Market.US), av.map { it.market })
        assertEquals(listOf("Netflix", "Max"), av[1].flatrate.map { it.name })
        assertEquals(1, av[1].rent.size)
        assertTrue(av[1].buy.isEmpty())
        assertEquals(listOf("Netflix"), av[0].flatrate.map { it.name })
    }

    @Test fun topProvidersUseRegionPriorityAndDropAdTiers() {
        val all = listOf(
            TmdbProvider(337, "Disney Plus", displayPriority = 3, displayPriorities = mapOf("RO" to 2)),
            TmdbProvider(8, "Netflix", displayPriority = 1, displayPriorities = mapOf("RO" to 1)),
            TmdbProvider(1796, "Netflix basic with Ads", displayPriority = 2, displayPriorities = mapOf("RO" to 0)),
            TmdbProvider(15, "Hulu", displayPriority = 4, displayPriorities = mapOf("US" to 3)),
        )
        val ro = TmdbMappers.topProviders(all, Market.RO, 2)
        assertEquals(listOf("Netflix", "Disney Plus"), ro.map { it.name })
    }

    @Test fun interleaves() {
        assertEquals(listOf(1, 10, 2, 20, 3), TmdbMappers.interleave(listOf(1, 2, 3), listOf(10, 20)))
    }

    @Test fun picksTitleLogoByLanguage() {
        val logos = listOf(
            ro.ak.vodunify.core.tmdb.TmdbImage("/a.png", "en", 5.0),
            ro.ak.vodunify.core.tmdb.TmdbImage("/b.svg", "ro", 9.0),
            ro.ak.vodunify.core.tmdb.TmdbImage("/c.png", "ro", 1.0),
            ro.ak.vodunify.core.tmdb.TmdbImage("/d.png", null, 9.0),
        )
        assertEquals("/c.png", TmdbMappers.logoPath(logos, "ro"))
        assertEquals("/a.png", TmdbMappers.logoPath(logos, "en"))
        assertNull(TmdbMappers.logoPath(emptyList(), "ro"))
    }
}
