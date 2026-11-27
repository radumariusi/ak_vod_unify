package ro.ak.vodunify.calendar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.calendar.engine.loadCalendarTitles
import ro.ak.vodunify.core.calendar.format.weekLabel
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.EpisodeAir
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.catalog.TitleReleases
import ro.ak.vodunify.ui.calendar.CalendarMode
import ro.ak.vodunify.ui.calendar.CalendarPosition
import ro.ak.vodunify.ui.calendar.MonthLogoGrid
import ro.ak.vodunify.ui.calendar.groupSameDayReleases
import ro.ak.vodunify.ui.calendar.monthLogoGrid
import ro.ak.vodunify.ui.calendar.monthLogoRowSizes
import ro.ak.vodunify.ui.calendar.monthPreview
import ro.ak.vodunify.ui.calendar.monthWeekKey
import ro.ak.vodunify.ui.calendar.monthWeekNumber
import ro.ak.vodunify.ui.calendar.monthWeekWindow
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
        val (group, tomorrow) = groupSameDayReleases(original + next)
        assertEquals("S01E01–03, E05 · S02E01", group.posterLabel)
        assertEquals(listOf(1, 2, 3, 5, 1), group.episodeMembers.map { it.episode!!.episodeNumber })
        assertTrue(group.inProgress)
        assertFalse(group.watched)
        assertEquals(next, tomorrow)
    }

    @Test fun calendarCombinesOnlySameDayCinemaMarkets() {
        fun movie(kind: MovieReleaseKind, region: Region, date: LocalDate = day) = MovieEntry(
            movieId = 12, title = "Film", originalTitle = null, posterUrl = "https://example.com/12.jpg",
            backdropUrl = null, overview = null, kind = kind, region = region,
            releaseAt = date.atStartOfDay(RomaniaTime.ZONE), hasTime = false,
        ).toItem(false)

        val ro = movie(MovieReleaseKind.CINEMA_RO, Region.RO)
        val us = movie(MovieReleaseKind.CINEMA_US, Region.US)
        val digital = movie(MovieReleaseKind.DIGITAL, Region.US)
        val otherDate = movie(MovieReleaseKind.CINEMA_US, Region.US, day.plusDays(1))
        val grouped = groupSameDayReleases(listOf(us, ro, digital, otherDate))
        assertEquals(listOf("RO", "US"), grouped.first().cinemaRegions)
        assertEquals(groupSameDayReleases(listOf(ro)).single().key, grouped.first().key)
        assertEquals(digital, grouped[1])
        assertEquals(day.plusDays(1), grouped[2].date)
        assertEquals(listOf("US"), grouped[2].cinemaRegions)
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

    @Test fun weekOpenedAcrossNewYearReturnsToItsOriginalMonthAndButton() {
        val january = CalendarPosition(CalendarMode.MONTH, LocalDate.of(2027, 1, 1))
        val monday = LocalDate.of(2026, 12, 28)
        val weekly = january.openWeek(monday)
        assertEquals(CalendarMode.WEEK, weekly.mode)
        assertEquals(monday, weekly.anchor)
        assertEquals(53, monthWeekNumber(monday))
        assertEquals(1, monthWeekNumber(monday.plusWeeks(1)))
        assertTrue(weekLabel(monday, monday.plusDays(6)).contains("2026 –"))
        assertEquals(monthWeekKey(monday), weekly.monthFocusKey)
        assertEquals(january, weekly.back(day))
    }

    @Test fun weekWindowMovesOnlyAtAnEdgeAndFillsTheLastPage() {
        assertEquals(0, monthWeekWindow(current = 0, target = 2, visibleRows = 3, totalRows = 6))
        assertEquals(1, monthWeekWindow(current = 0, target = 3, visibleRows = 3, totalRows = 6))
        assertEquals(3, monthWeekWindow(current = 2, target = 5, visibleRows = 3, totalRows = 6))
        assertEquals(2, monthWeekWindow(current = 3, target = 2, visibleRows = 3, totalRows = 6))
        assertEquals(4, monthWeekWindow(current = 3, target = 5, visibleRows = 2, totalRows = 6))
    }

    @Test fun monthLogosFitBusyDaysAndGiveSingleTitlesAnUpperSizeLimit() {
        val count = 20
        val largeGrid = requireNotNull(monthLogoGrid(count, 880, 105, 53, 8))
        val moreRoom = requireNotNull(monthLogoGrid(count, 880, 192, 53, 8))
        val narrowGrid = requireNotNull(monthLogoGrid(count, 320, 88, 40, 4))
        fun assertFits(grid: MonthLogoGrid, width: Int, height: Int) {
            val rows = (count + grid.columns - 1) / grid.columns
            assertTrue(grid.columns * grid.width + (grid.columns - 1) * grid.gap <= width)
            assertTrue(rows * grid.height + (rows - 1) * grid.gap <= height)
        }
        assertFits(largeGrid, 880, 105)
        assertFits(narrowGrid, 320, 88)
        assertTrue(moreRoom.height > largeGrid.height)
        assertEquals(53, monthLogoGrid(1, 880, 192, 53, 8)?.height)
    }

    @Test fun denseMonthLogoRowsUseMostAvailableWidth() {
        val grid = requireNotNull(monthLogoGrid(21, 880, 105, 53, 8))
        val rows = monthLogoRowSizes(21, grid.columns)
        assertEquals(listOf(11, 10), rows)
        assertTrue(grid.width * 11 + grid.gap * 10 >= 880 * 9 / 10)
        assertTrue(grid.height * 2 + grid.gap <= 105)
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
