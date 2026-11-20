package ro.ak.vodunify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.ProviderInfo
import ro.ak.vodunify.core.playback.LinkBuilder
import ro.ak.vodunify.core.playback.LinkPrecision
import ro.ak.vodunify.core.playback.MarketWarning
import ro.ak.vodunify.core.playback.OfferType
import ro.ak.vodunify.core.playback.PlaybackRequest
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.ResumeEstimator
import ro.ak.vodunify.core.playback.ResumeState
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.WatchOptionsBuilder
import ro.ak.vodunify.core.playback.formatPosition
import ro.ak.vodunify.core.prefs.Market

class PlaybackTest {
    private val movie = PlaybackRequest(PlaybackRequest.Type.MOVIE, 1726, "tt0371746", "Iron Man", 2008, runtimeMinutes = 126)
    private val show = PlaybackRequest(PlaybackRequest.Type.SHOW, 1399, "tt0944947", "Game of Thrones")
    private val episode = show.copy(type = PlaybackRequest.Type.EPISODE, season = 1, episode = 2, episodeTitle = "The Kingsroad")

    @Test fun stremioLinks() {
        assertEquals(
            listOf(LinkPrecision.TITLE to "stremio:///detail/movie/tt0371746/tt0371746"),
            LinkBuilder.candidates(TargetApp.STREMIO, movie).map { it.precision to it.uri },
        )
        val ep = LinkBuilder.candidates(TargetApp.STREMIO, episode)
        assertEquals(LinkPrecision.EPISODE, ep.first().precision)
        assertEquals("stremio:///detail/series/tt0944947/tt0944947:1:2", ep.first().uri)
        assertEquals("stremio:///detail/series/tt0944947", ep[1].uri)
        val noImdb = LinkBuilder.candidates(TargetApp.STREMIO, movie.copy(imdbId = null))
        assertEquals(LinkPrecision.SEARCH, noImdb.single().precision)
        assertEquals("stremio:///search?search=Iron%20Man", noImdb.single().uri)
    }

    @Test fun nuvioLinks() {
        assertEquals(
            listOf("nuvio://movie/tt0371746", "nuvio://tmdb/movie/1726"),
            LinkBuilder.candidates(TargetApp.NUVIO, movie).map { it.uri },
        )
        val ep = LinkBuilder.candidates(TargetApp.NUVIO, episode)
        assertEquals("nuvio://series/tt0944947", ep.first().uri)
        assertTrue(ep.all { it.precision == LinkPrecision.SERIES })
    }

    @Test fun officialAppsUseSearch() {
        val n = LinkBuilder.candidates(TargetApp.NETFLIX, movie)
        assertEquals("https://www.netflix.com/search?q=Iron%20Man", n.single().uri)
        assertTrue(LinkBuilder.candidates(TargetApp.HBO_MAX, show).all { it.precision == LinkPrecision.SEARCH })
    }

    @Test fun matchesProviders() {
        assertEquals(StreamingService.NETFLIX, StreamingService.fromProvider(8, "Netflix"))
        assertEquals(StreamingService.NETFLIX, StreamingService.fromProvider(99999, "Netflix Standard with Ads"))
        assertEquals(StreamingService.PRIME_VIDEO, StreamingService.fromProvider(119, "Amazon Prime Video"))
        assertEquals(StreamingService.HBO_MAX, StreamingService.fromProvider(5555, "Max"))
        assertEquals(StreamingService.DISNEY_PLUS, StreamingService.fromProvider(337, "Disney Plus"))
        assertNull(StreamingService.fromProvider(4444, "Voyo"))
        assertNull(StreamingService.fromProvider(4445, "Paramount Plus Apple TV Channel"))
    }

    private fun p(id: Int, name: String) = ProviderInfo(id, name, null)
    private fun avail(m: Market, flat: List<ProviderInfo> = emptyList(), rent: List<ProviderInfo> = emptyList()) =
        MarketAvailability(m, null, flat, emptyList(), rent, emptyList())

    @Test fun buildsOptionsWithPreferenceAndDedup() {
        val availability = listOf(
            avail(Market.RO, flat = listOf(p(8, "Netflix"), p(4444, "Voyo")), rent = listOf(p(10, "Amazon Video"), p(8, "Netflix"))),
            avail(Market.US, flat = listOf(p(9, "Amazon Prime Video"))),
        )
        val settings = PlaybackSettings(Market.RO, mapOf("netflix" to "stremio"))
        val o = WatchOptionsBuilder.build(availability, settings, setOf(TargetApp.STREMIO, TargetApp.NUVIO))
        assertNull(o.warning)
        assertEquals(listOf("Netflix", "Voyo", "Amazon Video"), o.services.map { it.provider.name })
        val netflix = o.services.first()
        assertEquals(TargetApp.STREMIO, netflix.app)
        assertTrue(netflix.installed && netflix.usesPreferredPlayer)
        assertEquals(OfferType.RENT, o.services.last().offer)
        assertEquals(netflix, o.recommendedService)
        assertEquals(listOf(TargetApp.NUVIO, TargetApp.STREMIO), o.players)
        assertEquals(listOf(TargetApp.DEBRID_STREAM), o.missingPlayers)
    }

    @Test fun preferredPlayerNotInstalledFallsBackToOfficial() {
        val o = WatchOptionsBuilder.build(
            listOf(avail(Market.RO, flat = listOf(p(8, "Netflix")))),
            PlaybackSettings(Market.RO, mapOf("netflix" to "nuvio")),
            setOf(TargetApp.NETFLIX),
        )
        assertEquals(TargetApp.NETFLIX, o.services.single().app)
        assertTrue(o.services.single().installed)
    }

    @Test fun warnsOnMarketMismatch() {
        val o = WatchOptionsBuilder.build(
            listOf(avail(Market.RO, rent = listOf(p(10, "Amazon Video"))), avail(Market.US, flat = listOf(p(1899, "Max")))),
            PlaybackSettings(Market.RO),
            emptySet(),
        )
        val w = o.warning as MarketWarning.OnlyElsewhere
        assertEquals(Market.US, w.otherMarket)
        assertEquals(listOf("Max"), w.services)
        assertNull(o.recommendedService)

        val none = WatchOptionsBuilder.build(listOf(avail(Market.RO), avail(Market.US)), PlaybackSettings(), emptySet())
        assertEquals(MarketWarning.NowhereStreaming, none.warning)
    }

    @Test fun resumeEstimates() {
        assertEquals(ResumeState.MANUAL, ResumeEstimator.fromProgress(null, 120, "trakt").state)
        assertEquals(ResumeState.MANUAL, ResumeEstimator.fromProgress(0.5f, null, "trakt").state)
        val est = ResumeEstimator.fromProgress(0.25f, 120, "trakt")
        assertEquals(ResumeState.ESTIMATED, est.state)
        assertEquals(30 * 60_000L, est.positionMs)
        assertEquals(ResumeState.PENDING, ResumeEstimator.fromProgress(0.25f, 120, "trakt", pendingSync = true).state)
        assertEquals(ResumeState.SYNCED, ResumeEstimator.fromProgress(null, null, "x", exactPositionMs = 5_000).state)
    }

    @Test fun formatsPosition() {
        assertEquals("00:05", formatPosition(5_000))
        assertEquals("12:34", formatPosition((12 * 60 + 34) * 1000L))
        assertEquals("1:02:03", formatPosition((3600 + 2 * 60 + 3) * 1000L))
    }

    @Test fun labels() {
        assertEquals("S1 E2 · The Kingsroad", episode.label)
        assertEquals("tv:tmdb:1399:s1e2", episode.key)
    }
}
