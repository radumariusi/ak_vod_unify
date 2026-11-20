package ro.ak.vodunify.core.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktIds(
    val trakt: Long? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Long? = null,
    val tvdb: Long? = null,
)

@Serializable
data class TraktMovie(val title: String? = null, val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktShow(val title: String? = null, val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktEpisode(
    val season: Int? = null,
    val number: Int? = null,
    val title: String? = null,
    val ids: TraktIds = TraktIds(),
)

/** /sync/playback: vizionări începute (progres parțial). */
@Serializable
data class TraktPlayback(
    val id: Long,
    val progress: Float? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    val type: String,
    val movie: TraktMovie? = null,
    val episode: TraktEpisode? = null,
    val show: TraktShow? = null,
)

/** /sync/history: vizionări complete. */
@Serializable
data class TraktHistoryItem(
    val id: Long,
    @SerialName("watched_at") val watchedAt: String,
    val action: String? = null,
    val type: String,
    val movie: TraktMovie? = null,
    val episode: TraktEpisode? = null,
    val show: TraktShow? = null,
)

/** /sync/watched/{movies|shows} */
@Serializable
data class TraktWatchedItem(
    val plays: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
)

/** /sync/watchlist */
@Serializable
data class TraktListItem(
    @SerialName("listed_at") val listedAt: String? = null,
    val type: String,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
)

@Serializable
data class TraktEpisodeProgress(
    val number: Int,
    val completed: Boolean = false,
)

@Serializable
data class TraktSeasonProgress(
    val number: Int,
    val aired: Int = 0,
    val completed: Int = 0,
    val episodes: List<TraktEpisodeProgress> = emptyList(),
)

/** /shows/{id}/progress/watched */
@Serializable
data class TraktShowProgress(
    val aired: Int = 0,
    val completed: Int = 0,
    @SerialName("last_watched_at") val lastWatchedAt: String? = null,
    val seasons: List<TraktSeasonProgress> = emptyList(),
    @SerialName("next_episode") val nextEpisode: TraktEpisode? = null,
)

@Serializable
data class TraktSearchResult(
    val type: String,
    val movie: TraktMovie? = null,
    val show: TraktShow? = null,
)

@Serializable
data class TraktDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Int = 5,
)

@Serializable
data class TraktToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("created_at") val createdAt: Long? = null,
    val scope: String? = null,
)

@Serializable
data class TraktUser(val username: String, val name: String? = null)

@Serializable
data class TraktUserSettings(val user: TraktUser)
