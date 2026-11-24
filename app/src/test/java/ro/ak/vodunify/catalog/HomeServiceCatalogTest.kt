package ro.ak.vodunify.catalog

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.CatalogRow
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.ProviderInfo
import ro.ak.vodunify.core.catalog.ServiceCatalogStatus
import ro.ak.vodunify.core.catalog.ServiceFallback
import ro.ak.vodunify.core.catalog.ServiceShelf
import ro.ak.vodunify.core.catalog.ServiceLibrary
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.VoyoPageMetadata
import ro.ak.vodunify.core.catalog.homeProviderIds
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.ui.home.mergeSavedCatalog
import ro.ak.vodunify.ui.home.withInstalledServices
import ro.ak.vodunify.core.prefs.Market
import java.time.Instant

class HomeServiceCatalogTest {
    @Test fun serviceRegionFollowsSettingsExceptForArte() {
        assertEquals("US", ServiceLibrary(StreamingService.NETFLIX, Market.US).region)
        assertEquals("RO", ServiceLibrary(StreamingService.TUBI, Market.RO).region)
        assertEquals("FR", ServiceLibrary(StreamingService.ARTE, Market.RO).region)
        val arte = ServiceShelf(StreamingService.ARTE, "tv.arte.plus7", Market.US)
        assertEquals("service_arte_FR", arte.rowId)
        assertEquals("Pe ARTE (FR)", arte.rowTitle)
        assertEquals(setOf(234), homeProviderIds(StreamingService.ARTE, listOf(
            ProviderInfo(234, "Arte", null), ProviderInfo(335, "Arte VOD", null)
        )))
    }

    @Test fun voyoOnlyIncludesCurrentRomanianOffersWithMatchingTmdbIds() {
        val json = Json
        val list = """
            <script type="application/ld+json">{"@type":"ItemList","itemListElement":[
              {"url":"https://whattowatch.ro/film/voyo-title"},
              {"url":"https://whattowatch.ro/serial/some-other-title"},
              {"url":"https://example.com/film/other"}
            ]}</script>
        """.trimIndent()
        assertEquals(listOf("/film/voyo-title"), VoyoPageMetadata.titlePaths(list, TitleKind.MOVIE, json))
        val detail = """
            <script type="application/ld+json">{"@type":"Movie",
              "sameAs":["https://www.themoviedb.org/tv/16","https://www.themoviedb.org/movie/123"],
              "potentialAction":[{"@type":"WatchAction","provider":{"name":"VOYO"},
                "actionAccessibilityRequirement":{"category":"subscription","eligibleRegion":{"name":"RO"},
                  "availabilityStarts":"2026-11-20T00:00:00+00:00","availabilityEnds":"2026-12-01T00:00:00+00:00"}}]
            }</script>
        """.trimIndent()
        val now = Instant.parse("2026-11-25T12:00:00Z")
        assertEquals(123L, VoyoPageMetadata.availableId(detail, TitleKind.MOVIE, json, now))
        assertEquals(null, VoyoPageMetadata.availableId(detail.replace("RO", "US"), TitleKind.MOVIE, json, now))
        assertEquals(null, VoyoPageMetadata.availableId(detail.replace("VOYO", "Netflix"), TitleKind.MOVIE, json, now))
        assertEquals(null, VoyoPageMetadata.availableId(detail, TitleKind.MOVIE, json, now.plusSeconds(8 * 86_400)))
    }

    @Test fun voyoLookupUsesOfficialPackageAndRegionalProviderDirectory() {
        assertEquals(TargetApp.VOYO, TargetApp.byPackage("net.cme.voyo.ro.tvapp"))
        assertEquals(emptySet<Int>(), homeProviderIds(StreamingService.VOYO, emptyList()))
        val directory = listOf(ProviderInfo(9012, "Voyo", null), ProviderInfo(8, "Netflix", null))
        assertEquals(setOf(9012), homeProviderIds(StreamingService.VOYO, directory))
        assertEquals(StreamingService.VOYO.tmdbProviderIds, homeProviderIds(StreamingService.VOYO, null))
    }

    @Test fun temporaryFailureRetainsCachedTitlesButVerifiedEmptyLibraryUsesShortcut() {
        val item = CatalogItem(101, TitleKind.MOVIE, "Film", null, "", null, null, null)
        val known = CatalogRow("service_voyo_RO", "Pe VOYO (RO)", listOf(item))
        val saved = HomeCatalog(emptyList(), listOf(known))
        val missing = known.copy(items = emptyList(), serviceFallback = ServiceFallback("net.cme.voyo.ro.tvapp", ServiceCatalogStatus.UNAVAILABLE))
        val failed = HomeCatalog(emptyList(), listOf(missing))
        assertEquals(saved, mergeSavedCatalog(failed, saved))
        val verified = failed.copy(rows = listOf(missing.copy(serviceFallback = missing.serviceFallback?.copy(reason = ServiceCatalogStatus.NO_TITLES))))
        assertEquals(verified, mergeSavedCatalog(verified, saved))
    }
    @Test fun installedServicesAppearBeforeNetworkResultsAndRemainOnFailure() {
        val voyo = ServiceShelf(StreamingService.VOYO, "net.cme.voyo.ro.tvapp", Market.RO)
        val plex = ServiceShelf(StreamingService.PLEX, "com.plexapp.android", Market.US)
        val popular = CatalogRow("movies", "Filme populare", emptyList())
        val old = ServiceShelf(StreamingService.NETFLIX, "com.netflix.ninja", Market.RO).row(status = ServiceCatalogStatus.NO_TITLES)
        val snapshot = HomeCatalog(emptyList(), listOf(popular, old))
        val shelves = listOf(voyo, plex)
        val checking = snapshot.withInstalledServices(shelves, ServiceCatalogStatus.CHECKING)
        assertEquals(listOf(voyo.rowId, plex.rowId, popular.id), checking.rows.map { it.id })
        assertEquals(ServiceFallback(voyo.packageName, ServiceCatalogStatus.CHECKING), checking.rows.first().serviceFallback)

        val failed = checking.withInstalledServices(shelves, ServiceCatalogStatus.UNAVAILABLE)
        assertEquals(listOf(voyo.rowId, plex.rowId, popular.id), failed.rows.map { it.id })
        assertEquals(ServiceFallback(voyo.packageName, ServiceCatalogStatus.UNAVAILABLE), failed.rows.first().serviceFallback)
    }

}
