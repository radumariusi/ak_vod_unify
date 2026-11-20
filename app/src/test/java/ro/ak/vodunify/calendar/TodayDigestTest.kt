package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.calendar.today.TodayDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

class TodayDigestTest {
    private val today = LocalDate.of(2026, 11, 20)

    private fun episode(show: Int, ep: Int, hour: Int, followed: Boolean = false, date: LocalDate = today) = EpisodeEntry(
        showId = show, showName = "Show $show", posterUrl = null, stillUrl = null, seasonNumber = 1, episodeNumber = ep,
        episodeTitle = null, overview = null,
        releaseAt = ZonedDateTime.of(date, LocalTime.of(hour, 0), RomaniaTime.ZONE), timeIsApproximate = false,
        watchOn = null, watchRegion = null, availability = emptyList(), tmdbRating = 8.0, tmdbVotes = 1000, imdbId = null,
        categories = emptySet(), isSeasonPremiere = false, isNewShow = false, isFollowed = followed,
    )

    private fun movie(id: Int, kind: MovieReleaseKind, followed: Boolean = false, popularity: Double = 1.0) = MovieEntry(
        movieId = id, title = "Movie $id", originalTitle = null, posterUrl = null, backdropUrl = null, overview = null,
        kind = kind, region = Region.US, releaseAt = ZonedDateTime.of(today, LocalTime.MIDNIGHT, RomaniaTime.ZONE),
        hasTime = false, isFollowed = followed, popularity = popularity,
    )

    @Test
    fun `followed first, watched and other days left out`() {
        val digest = TodayDigest.build(
            date = today,
            episodes = listOf(episode(1, 1, 3), episode(2, 5, 22, followed = true), episode(3, 1, 10), episode(4, 1, 1, date = today.plusDays(1))),
            movies = listOf(
                movie(10, MovieReleaseKind.CINEMA_US, popularity = 5.0),
                movie(10, MovieReleaseKind.STREAMING, popularity = 5.0),
                movie(11, MovieReleaseKind.DIGITAL, followed = true),
                movie(12, MovieReleaseKind.CINEMA_RO),
            ),
            watchedEpisodes = setOf("3-1-1"),
            watchedMovies = setOf(12),
        )
        assertEquals(listOf("2-1-5", "1-1-1"), digest.episodes.map { it.key })
        assertEquals(listOf(2), digest.followedEpisodes.map { it.showId })
        assertEquals(listOf(11, 10), digest.movies.map { it.movieId })
        assertEquals(listOf(11), digest.followedMovies.map { it.movieId })
    }
}
