package ro.ak.vodunify.calendar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.calendar.engine.loadCalendarTitles
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleReleases
import ro.ak.vodunify.ui.calendar.CalendarMode
import ro.ak.vodunify.ui.calendar.CalendarPosition
import ro.ak.vodunify.ui.calendar.groupSameDayEpisodes
import ro.ak.vodunify.ui.calendar.monthPreview
import ro.ak.vodunify.ui.calendar.toItem
import ro.ak.vodunify.ui.home.FollowedTitle
import ro.ak.vodunify.ui.home.HomeSections
import java.time.LocalDate

class CalendarPresentationTest {
    private val day = LocalDate.of(2026, 11, 21)

    private fun episode(
        number: Int,
        season: Int = 1,
        id: Int = 30,
        date: LocalDate = day,
        hour: Int = 8,
        followed: Boolean = true,
        watched: Boolean = false,
    ) = EpisodeEntry(
        showId = id, showName = "Serialul $id", posterUrl = "https://example.com/$id.jpg", stillUrl = null,
        seasonNumber = season, episodeNumber = number, episodeTitle = null, overview = null,
        releaseAt = date.atTime(hour, 0).atZone(RomaniaTime.ZONE), timeIsApproximate = false,
        watchOn = null, watchRegion = null, availability = emptyList(), tmdbRating = 8.0, tmdbVotes = 100,
        imdbId = null, categories = emptySet(), isSeasonPremiere = number == 1, isNewShow = season == 1,
        isFollowed = followed,
    ).toItem(watched)

    @Test fun groupsEpisodePostersByRomanianDayAndKeepsGapsAndIndividualActions() {
        val original = listOf(episode(3), episode(1, watched = true), episode(2), episode(5), episode(1, season = 2))
        val next = episode(2, season = 2, date = day.plusDays(1))
        val (group, tomorrow) = groupSameDayEpisodes(original + next)
        assertEquals("S01E01–03, E05 · S02E01", group.posterLabel)
        assertEquals(listOf(1, 2, 3, 5, 1), group.episodeMembers.map { it.episode!!.episodeNumber })
        assertTrue(group.inProgress)
        assertFalse(group.watched)
        assertEquals(next, tomorrow)
    }

    @Test fun monthlyPreviewPrefersFollowedUnwatchedShowsAndCountsDistinctFilmsSeparately() {
        val ordinary = episode(1, id = 49, hour = 4, followed = false)
        val completed = episode(1, id = 80, hour = 5, watched = true)
        val first = episode(1)
        val second = episode(2)
        val movie = ordinary.copy(key = "movie", tmdbId = first.tmdbId, isMovie = true, title = "Film", episode = null)
        val previews = monthPreview(listOf(ordinary, completed, first, second, movie), first.releaseAt.plusSeconds(60))
        assertEquals(listOf(first.key, completed.key, movie.key, ordinary.key), previews.map { it.key })
    }

    @Test fun dayOpenedFromMonthReturnsToThatMonthThenToday() {
        val original = CalendarPosition(CalendarMode.MONTH, day)
        val selected = day.minusDays(5)
        val daily = original.openDay(selected)
        assertEquals(CalendarMode.DAY, daily.mode)
        assertEquals(selected, daily.openedDay)
        assertEquals(original, daily.back(day))
        assertEquals(CalendarPosition(CalendarMode.TODAY, day), original.back(day))
        assertEquals(CalendarMode.TODAY, CalendarMode.entries.first())
    }

    @Test fun homeRangeIncludesOnlyTheSameReleaseDayAndUnseenEpisodes() {
        val item = CatalogItem(30, TitleKind.TV, "Serial", null, "", null, null, null)
        val last = EpisodeAir(1, 8, null, day.toString())
        val title = FollowedTitle(TitleReleases(item, null, last, null), lastSeen = 1 to 3)
        val card = HomeSections.newEpisodes(listOf(title), day).single()
        val episodes = (1..8).map { last.copy(episode = it) } + last.copy(episode = 9, airDate = day.plusDays(1).toString())
        assertEquals("S01E04–08", HomeSections.withEpisodeRange(card, episodes, title.lastSeen).episodeCode)
    }

    @Test fun yieldsFastEntriesWhileTheSlowTitleIsStillLoading() = runTest {
        val continueSlowTitle = CompletableDeferred<Unit>()
        val preview = CompletableDeferred<List<Int>>()
        val result = async {
            loadCalendarTitles(listOf(1, 2), concurrency = 2, onProgress = { preview.complete(it.toList()) }) { id ->
                if (id == 1) continueSlowTitle.await()
                listOf(id)
            }
        }
        assertEquals(listOf(2), preview.await())
        assertFalse(result.isCompleted)
        continueSlowTitle.complete(Unit)
        assertEquals(setOf(1, 2), result.await().flatMap { it.getOrThrow() }.toSet())
    }
}
