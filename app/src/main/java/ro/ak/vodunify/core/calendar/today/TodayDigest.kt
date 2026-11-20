package ro.ak.vodunify.core.calendar.today

import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import java.time.LocalDate

/**
 * What comes out today, for the morning notification and the "Azi apare" TV channel.
 * Already watched titles (Simkl) are left out; followed ones come first.
 */
data class TodayDigest(
    val date: LocalDate,
    val episodes: List<EpisodeEntry>,
    val movies: List<MovieEntry>,
) {
    val followedEpisodes: List<EpisodeEntry> get() = episodes.filter { it.isFollowed }
    val followedMovies: List<MovieEntry> get() = movies.filter { it.isFollowed }
    val isEmpty: Boolean get() = episodes.isEmpty() && movies.isEmpty()

    companion object {
        fun build(
            date: LocalDate,
            episodes: List<EpisodeEntry>,
            movies: List<MovieEntry>,
            watchedEpisodes: Set<String> = emptySet(),
            watchedMovies: Set<Int> = emptySet(),
        ): TodayDigest = TodayDigest(
            date = date,
            episodes = episodes
                .filter { it.releaseDate == date && it.key !in watchedEpisodes }
                .distinctBy { it.key }
                .sortedWith(compareBy({ !it.isFollowed }, { it.releaseAt }, { it.showName }, { it.episodeNumber })),
            movies = movies
                .filter { it.releaseDate == date && it.movieId !in watchedMovies }
                // One line per movie, even when it opens in cinemas and on a streamer the same day.
                .distinctBy { it.movieId }
                .sortedWith(compareBy({ !it.isFollowed }, { -it.popularity }, { it.title })),
        )
    }
}
