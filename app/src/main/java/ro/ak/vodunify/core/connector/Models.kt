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

data class MediaRef(
    val type: MediaType,
    val ids: ExternalIds,
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
)

data class WatchEvent(
    val media: MediaRef,
    val watchedAt: Instant,
    val source: String,
)

data class ResumePoint(
    val media: MediaRef,
    /** Progres 0.0 – 1.0 */
    val progress: Float,
    val updatedAt: Instant,
    val source: String,
)
