package ro.ak.vodunify.core.calendar.model

import kotlinx.serialization.Serializable

@Serializable
data class UserStreamingApp(
    val appId: String,
    val installed: Boolean = false,
    val region: Region = Region.RO,
)

/** A show or movie the user follows or hid, remembered with its name for the Settings screen. */
@Serializable
data class SavedShow(val id: Int, val name: String)

/**
 * Quality gates for series.
 *
 * Returning shows must reach the IMDb thresholds (when an OMDb key is set and
 * OMDb knows the show) or else the TMDB thresholds. New shows (season 1) pass
 * when they are on an "anticipated" list or already reach the thresholds.
 */
@Serializable
data class QualityThresholds(
    val minImdbRating: Double = 7.0,
    val minImdbVotes: Int = 5_000,
    val minTmdbRating: Double = 7.0,
    val minTmdbVotes: Int = 300,
    /** New shows (season 1) only show up if they are on an "anticipated" list. */
    val newShowsOnlyIfAnticipated: Boolean = true,
)

/** Defaults for movies: a bit lower than for series, but with more IMDb votes. */
val DefaultMovieThresholds = QualityThresholds(
    minImdbRating = 6.5,
    minImdbVotes = 10_000,
    minTmdbRating = 6.5,
    minTmdbVotes = 200,
    newShowsOnlyIfAnticipated = true,
)

@Serializable
data class CalendarSettings(
    /** Optional OMDb key for IMDb ratings; overrides the one baked in at build time. */
    val omdbApiKey: String = "",
    val apps: List<UserStreamingApp> = StreamingCatalog.all.map { UserStreamingApp(it.id) },
    val thresholds: QualityThresholds = QualityThresholds(),
    val hiddenCategories: Set<ContentFilter> = ContentFilter.defaultHidden,
    /** Always shown, whatever the thresholds say. */
    val followedShows: List<SavedShow> = emptyList(),
    /** Never shown. */
    val hiddenShows: List<SavedShow> = emptyList(),
    /** Calendar shows only episodes the user can watch on an installed app. */
    val onlyInstalledApps: Boolean = false,
    /** Quality gates for movies; `newShowsOnlyIfAnticipated` means "new movies only if anticipated". */
    val movieThresholds: QualityThresholds = DefaultMovieThresholds,
    /** Which kinds of movie releases the movie calendar shows. */
    val movieKinds: Set<MovieReleaseKind> = MovieReleaseKind.entries.toSet(),
    /** Movies always shown, whatever the thresholds say. */
    val followedMovies: List<SavedShow> = emptyList(),
    /** Movies never shown. */
    val hiddenMovies: List<SavedShow> = emptyList(),
    /**
     * Series on the user's Trakt / SIMKL lists (watchlist, in progress); filled at runtime from
     * the tracking database, never persisted. They count as followed.
     */
    val trackedShows: List<SavedShow> = emptyList(),
    /** Movies on the user's Trakt / SIMKL watchlist; filled at runtime. They count as followed. */
    val trackedMovies: List<SavedShow> = emptyList(),
    /** Daily notification about what comes out today. */
    val notifications: NotificationSettings = NotificationSettings(),
    /** "Azi apare" channel on the Android TV home screen. */
    val tvChannel: Boolean = true,
) {
    /** Adds apps that were added to the catalog after the settings were saved and drops unknown ones. */
    fun normalized(): CalendarSettings {
        val known = apps.filter { StreamingCatalog.byId(it.appId) != null }.distinctBy { it.appId }
        val missing = StreamingCatalog.all
            .filter { app -> known.none { it.appId == app.id } }
            .map { UserStreamingApp(it.id) }
        return copy(
            apps = known + missing,
            followedShows = followedShows.distinctBy { it.id },
            hiddenShows = hiddenShows.distinctBy { it.id },
            followedMovies = followedMovies.distinctBy { it.id },
            hiddenMovies = hiddenMovies.distinctBy { it.id },
            notifications = notifications.copy(hour = notifications.hour.coerceIn(0, 23)),
        )
    }

    fun installedApps(): List<Pair<StreamingApp, Region>> = apps
        .filter { it.installed }
        .mapNotNull { user -> StreamingCatalog.byId(user.appId)?.let { it to user.region } }

    fun updateApp(appId: String, transform: (UserStreamingApp) -> UserStreamingApp): CalendarSettings =
        copy(apps = apps.map { if (it.appId == appId) transform(it) else it })

    /** Followed in the app, or on a Trakt / SIMKL list (unless hidden by hand). */
    fun isFollowed(showId: Int): Boolean =
        followedShows.any { it.id == showId } || (isTracked(showId) && !isHidden(showId))

    /** Shows always checked by the calendar: followed here plus the Trakt and SIMKL lists. */
    fun followedShowIds(): List<Int> =
        (followedShows.map { it.id } + trackedShows.map { it.id }.filterNot { isHidden(it) }).distinct()

    /** On a synced tracking list (Trakt or SIMKL). */
    fun isTracked(showId: Int): Boolean = trackedShows.any { it.id == showId }
    fun isHidden(showId: Int): Boolean = hiddenShows.any { it.id == showId }

    /** Following a show un-hides it, and the other way round. */
    fun follow(show: SavedShow): CalendarSettings =
        copy(followedShows = followedShows.filterNot { it.id == show.id } + show, hiddenShows = hiddenShows.filterNot { it.id == show.id })

    /** Only the local follow; a title on a Trakt / SIMKL list stays followed (hide it to drop it). */
    fun unfollow(showId: Int): CalendarSettings = copy(
        followedShows = followedShows.filterNot { it.id == showId },
    )

    fun hide(show: SavedShow): CalendarSettings =
        copy(hiddenShows = hiddenShows.filterNot { it.id == show.id } + show, followedShows = followedShows.filterNot { it.id == show.id })

    fun unhide(showId: Int): CalendarSettings = copy(hiddenShows = hiddenShows.filterNot { it.id == showId })

    fun isMovieFollowed(movieId: Int): Boolean =
        followedMovies.any { it.id == movieId } ||
            (trackedMovies.any { it.id == movieId } && !isMovieHidden(movieId))

    fun followedMovieIds(): List<Int> =
        (followedMovies.map { it.id } + trackedMovies.map { it.id }.filterNot { isMovieHidden(it) }).distinct()
    fun isMovieHidden(movieId: Int): Boolean = hiddenMovies.any { it.id == movieId }

    fun followMovie(movie: SavedShow): CalendarSettings = copy(
        followedMovies = followedMovies.filterNot { it.id == movie.id } + movie,
        hiddenMovies = hiddenMovies.filterNot { it.id == movie.id },
    )

    fun unfollowMovie(movieId: Int): CalendarSettings = copy(
        followedMovies = followedMovies.filterNot { it.id == movieId },
    )

    fun hideMovie(movie: SavedShow): CalendarSettings = copy(
        hiddenMovies = hiddenMovies.filterNot { it.id == movie.id } + movie,
        followedMovies = followedMovies.filterNot { it.id == movie.id },
    )

    fun unhideMovie(movieId: Int): CalendarSettings = copy(hiddenMovies = hiddenMovies.filterNot { it.id == movieId })
}

/** Who gets a notification in the morning. */
@Serializable
enum class NotificationMode(val label: String) {
    OFF("Oprite"),
    /** One notification per episode / movie the user follows (here or on Trakt / SIMKL). */
    FOLLOWED("Doar ce urmăresc"),
    /** Followed titles one by one, plus a summary of the whole day's calendar. */
    ALL("Ce urmăresc + rezumatul zilei"),
}

@Serializable
data class NotificationSettings(
    val mode: NotificationMode = NotificationMode.OFF,
    /** Hour (Romania time) of the daily notification. */
    val hour: Int = 9,
    val includeMovies: Boolean = true,
)
