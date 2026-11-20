package ro.ak.vodunify.core.connector

import java.time.Instant

enum class MediaType { MOVIE, SHOW, EPISODE }

/** Identificatori externi pentru același titlu, folosiți la reconciliere între surse. */
data class ExternalIds(
    val tmdb: Long? = null,
    val imdb: String? = null,
    val trakt: Long? = null,
    val simkl: Long? = null,
    val tvdb: Long? = null,
)

/**
 * Referință la un film, serial sau episod.
 * Pentru [MediaType.EPISODE], [ids], [title] și [year] descriu **serialul**, iar episodul
 * e dat de [season] / [episode] / [episodeTitle]. Așa un episod se poate potrivi între surse
 * doar cu id-ul serialului + numerele episodului.
 */
data class MediaRef(
    val type: MediaType,
    val ids: ExternalIds,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
) {
    /** Cheia titlului (film sau serial), comună cu tabela `media`: "movie:tmdb:603", "tv:tmdb:1399". */
    val titleKey: String
        get() {
            val kind = if (type == MediaType.MOVIE) "movie" else "tv"
            return when {
                ids.tmdb != null -> "$kind:tmdb:${ids.tmdb}"
                ids.imdb != null -> "$kind:imdb:${ids.imdb}"
                ids.trakt != null -> "$kind:trakt:${ids.trakt}"
                ids.simkl != null -> "$kind:simkl:${ids.simkl}"
                ids.tvdb != null -> "$kind:tvdb:${ids.tvdb}"
                else -> "$kind:title:${title.lowercase()}:${year ?: 0}"
            }
        }

    /** Cheia elementului: titlul, plus sezon/episod pentru episoade ("tv:tmdb:1399:s1e2"). */
    val itemKey: String
        get() = if (type == MediaType.EPISODE) "$titleKey:s${season ?: 0}e${episode ?: 0}" else titleKey

    /** Referința la serialul unui episod. */
    fun asShow(): MediaRef = copy(type = MediaType.SHOW, season = null, episode = null, episodeTitle = null)
}

/** O vizionare completă (istoric). */
data class WatchEvent(
    val media: MediaRef,
    val watchedAt: Instant,
    val source: String,
    /** Id-ul intrării în sursă (ex. id-ul din istoricul Trakt), pentru deduplicare. */
    val remoteId: Long? = null,
)

/**
 * Punct de reluare (vizionare începută, neterminată). Separat de istoric.
 * [progress] = null înseamnă „poziție necunoscută”, NU 0%.
 */
data class ResumePoint(
    val media: MediaRef,
    /** Progres 0.0 – 1.0, sau null dacă sursa nu știe poziția. */
    val progress: Float?,
    val updatedAt: Instant,
    val source: String,
    val remoteId: Long? = null,
)

data class WatchlistEntry(
    val media: MediaRef,
    val listedAt: Instant,
    val source: String,
)

data class EpisodeNumber(val season: Int, val episode: Int)

/** Progresul pe un serial: câte episoade difuzate ai văzut și care urmează. */
data class ShowProgress(
    val show: MediaRef,
    val aired: Int,
    val completed: Int,
    val watched: Set<EpisodeNumber>,
    /** Următorul episod de văzut (tip EPISODE), sau null dacă ești la zi. */
    val next: MediaRef?,
    val lastWatchedAt: Instant?,
    val source: String,
)

/** Contul conectat la o sursă. */
data class ConnectorAccount(val username: String, val displayName: String?)

/** Cod de autentificare afișat pe ecran (OAuth device code). */
data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresAt: Instant,
    val intervalSeconds: Int,
)

sealed interface DeviceAuthPoll {
    /** Utilizatorul n-a introdus încă codul. */
    data object Pending : DeviceAuthPoll

    /** Sursa cere să interogăm mai rar. */
    data object SlowDown : DeviceAuthPoll
    data class Approved(val account: ConnectorAccount) : DeviceAuthPoll
    data object Denied : DeviceAuthPoll
    data object Expired : DeviceAuthPoll
    data class Failed(val message: String) : DeviceAuthPoll
}
