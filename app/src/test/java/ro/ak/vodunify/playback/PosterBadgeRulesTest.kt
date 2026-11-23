package ro.ak.vodunify.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.vodunify.core.playback.BadgePriority
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.PosterBadgeMode
import ro.ak.vodunify.core.playback.PosterBadgePolicy
import ro.ak.vodunify.core.playback.PosterBadgeRules
import ro.ak.vodunify.core.playback.PosterStage
import ro.ak.vodunify.core.playback.ServiceBadge
import ro.ak.vodunify.core.prefs.Market

class PosterBadgeRulesTest {
    private val player = PosterBadge("stremio", "Stremio")
    private val netflix = PosterBadge("netflix", "Netflix (RO)", "netflix")
    private val max = PosterBadge("max", "HBO Max (RO)", "hbomax")
    private val tubi = PosterBadge("tubi", "Tubi (US)", "tubi")
    private val available = listOf(ServiceBadge(tubi, Market.US), ServiceBadge(netflix, Market.RO), ServiceBadge(max, Market.RO))
    private val defaults = PosterBadgePolicy.default(PosterStage.IN_PROGRESS)

    @Test fun inProgressAndWatchedDefaultToTheRecordedAppFollowedByAllStreaming() {
        val expected = listOf(player, netflix, max, tubi)
        for (stage in listOf(PosterStage.IN_PROGRESS, PosterStage.WATCHED)) {
            val policy = PosterBadgePolicy.default(stage)
            assertEquals(expected, PosterBadgeRules.select(policy, emptyList(), available, player, player))
        }
    }

    @Test fun unseenShowsRomanianStreamingFirstWithoutAPlayerWhenStreamingExists() {
        val policy = PosterBadgePolicy.default(PosterStage.UNWATCHED)
        val dualMarket = available + ServiceBadge(netflix.copy(label = "Netflix (US)"), Market.US)
        assertEquals(listOf(netflix, max, tubi), PosterBadgeRules.select(policy, emptyList(), dualMarket, player, player))
        assertEquals(listOf(player), PosterBadgeRules.select(policy, emptyList(), emptyList(), null, player))
    }

    @Test fun preferringRomaniaCanReplaceAPlayerOrUsService() {
        val priorities = listOf(BadgePriority.STREAMING_RO) + defaults.priorities.filterNot { it == BadgePriority.STREAMING_RO }
        val policy = defaults.copy(mode = PosterBadgeMode.FIRST, priorities = priorities)
        for (last in listOf(player, tubi)) {
            assertEquals(listOf(max), PosterBadgeRules.select(policy, listOf("hbomax"), available, last, player))
        }
    }

    @Test fun allStreamingOrdersServicesAndLeavesPlayerForFallback() {
        val policy = defaults.copy(mode = PosterBadgeMode.ALL)
        assertEquals(listOf(max, netflix, tubi), PosterBadgeRules.select(policy, listOf("hbomax"), available, player, player))
        assertEquals(listOf(player), PosterBadgeRules.select(policy, emptyList(), emptyList(), null, player))
    }

    @Test fun allWithLastDoesNotRepeatTheSameServiceForAnotherPackage() {
        val policy = defaults.copy(mode = PosterBadgeMode.ALL_WITH_LAST)
        val previous = netflix.copy(packageName = "netflix.tv")
        assertEquals(listOf(previous, max, tubi), PosterBadgeRules.select(policy, emptyList(), available, previous, player))
        assertEquals(listOf(player, netflix, max, tubi), PosterBadgeRules.select(policy, emptyList(), available, player, player))
    }

    @Test fun hiddenBadgesIgnoreAvailableOptions() {
        val policy = defaults.copy(mode = PosterBadgeMode.NONE)
        assertEquals(emptyList<PosterBadge>(), PosterBadgeRules.select(policy, emptyList(), available, tubi, player))
    }
}
