package ro.ak.vodunify.core.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cache local pentru titluri (din TMDb + id-uri externe). */
@Entity(tableName = "media", indices = [Index("tmdbId"), Index("imdbId")])
data class MediaEntity(
    @PrimaryKey val key: String, // ex. "movie:tmdb:603"
    val type: String,
    val title: String,
    val year: Int?,
    val tmdbId: Long?,
    val imdbId: String?,
    val traktId: Long?,
    val simklId: Long?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val overview: String?,
    val updatedAt: Long,
)

/**
 * Descrierea unui film / serial / episod venit dintr-o sursă (Trakt, SIMKL…).
 * Pentru episoade, id-urile și titlul sunt ale serialului.
 */
data class TrackedRef(
    /** Cheia titlului: "movie:tmdb:603", "tv:tmdb:1399" (vezi MediaRef.titleKey). */
    val mediaKey: String,
    /** MOVIE / SHOW / EPISODE */
    val type: String,
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val tmdbId: Long?,
    val imdbId: String?,
    val traktId: Long?,
    val tvdbId: Long?,
    val simklId: Long? = null,
)

/** Istoric: vizionări complete. Id = "<sursă>|<id intrare>" ca să nu se dubleze la resincronizare. */
@Entity(
    tableName = "watch_events",
    indices = [
        Index("mediaKey"),
        Index("source"),
        Index("watchedAt"),
        Index(value = ["source", "mediaKey", "season", "episode", "watchedAt"], name = "idx_watch_events_lookup"),
        Index(value = ["mediaKey", "season", "episode", "watchedAt"], name = "idx_watch_events_item_time"),
    ],
)
data class WatchEventEntity(
    @PrimaryKey val id: String,
    val source: String,
    @Embedded val ref: TrackedRef,
    val watchedAt: Long,
    val appPackage: String? = null,
    val playbackAt: Long? = null,
)

data class TitlePlaybackApp(val mediaKey: String, val source: String, val appPackage: String?, val at: Long)

/**
 * Puncte de reluare (playback): vizionări începute. Separate de istoric.
 * [progress] = null înseamnă poziție necunoscută (nu 0%).
 */
@Entity(tableName = "resume_points", indices = [Index("mediaKey"), Index("source")])
data class ResumePointEntity(
    @PrimaryKey val id: String, // "<sursă>|<itemKey>"
    val source: String,
    @Embedded val ref: TrackedRef,
    val progress: Float?,
    val updatedAt: Long,
    val remoteId: Long?,
    /** Poziția exactă în ms, doar pentru poziții setate de tine („Setează minutul”, sursa „local”). */
    val positionMs: Long? = null,
    /** Aplicația (pachetul) în care ai văzut ultima dată, după AK Scrobbler; acolo continui standard. */
    val appPackage: String? = null,
)

/** Lista „De văzut”. */
@Entity(tableName = "watchlist", indices = [Index("mediaKey"), Index("source")])
data class WatchlistEntity(
    @PrimaryKey val id: String, // "<sursă>|<mediaKey>"
    val source: String,
    @Embedded val ref: TrackedRef,
    val listedAt: Long,
)

/** „Următorul episod”: câte un rând pe serial început. ref descrie episodul următor. */
@Entity(tableName = "up_next", indices = [Index("mediaKey"), Index("source")])
data class UpNextEntity(
    @PrimaryKey val id: String, // "<sursă>|<mediaKey>"
    val source: String,
    @Embedded val ref: TrackedRef,
    val aired: Int,
    val completed: Int,
    val lastWatchedAt: Long,
)

/** Titluri văzute (film văzut / serial început), cu ultima vizionare. */
@Entity(tableName = "watched_titles", indices = [Index("mediaKey"), Index("source")])
data class WatchedTitleEntity(
    @PrimaryKey val id: String, // "<sursă>|<mediaKey>"
    val source: String,
    @Embedded val ref: TrackedRef,
    val lastWatchedAt: Long,
)

/**
 * O scriere făcută de aplicație într-o sursă (marcat văzut, progres, watchlist).
 * Reconcilierea o folosește ca să recunoască „ecoul” când sursa ne-o trimite înapoi
 * și ca să nu lase un import mai vechi să anuleze o acțiune mai nouă.
 */
@Entity(tableName = "own_writes", indices = [Index("itemKey"), Index("at")])
data class OwnWriteEntity(
    @PrimaryKey val id: String, // "<sursă>|<itemKey>|<acțiune>"
    val source: String,
    /** Cheia elementului: titlu sau titlu + sezon/episod (MediaRef.itemKey). */
    val itemKey: String,
    /** Vezi [OwnWriteAction]. */
    val action: String,
    val progress: Float?,
    val at: Long,
)

enum class OwnWriteAction { WATCHED, UNWATCHED, RESUME, WATCHLIST_ADD, WATCHLIST_REMOVE }

/** Starea sincronizării pe sursă. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val source: String,
    val lastSyncAt: Long,
    /** Data ultimei intrări din istoric descărcate (pentru sincronizare incrementală). */
    val historyCursor: Long?,
    val lastError: String?,
)

/**
 * Starea publicării unui progres către o destinație (Trakt, SIMKL).
 * Id = "<destinație>|<itemKey>": o singură înregistrare pe element și destinație (idempotent).
 */
@Entity(tableName = "publish_records", indices = [Index("itemKey"), Index("destination")])
data class PublishRecordEntity(
    @PrimaryKey val id: String,
    val destination: String,
    /** Cheia canonică a elementului (după reconciliere). */
    val itemKey: String,
    @Embedded val ref: TrackedRef,
    /** Progresul trimis, 0–1. */
    val progress: Float,
    val positionMs: Long?,
    /** Sursa poziției publicate: "local", "trakt", "simkl". */
    val origin: String,
    /** Când a fost înregistrată poziția în sursă. */
    val originAt: Long,
    /** Vezi PublishStatus. */
    val status: String,
    val attemptAt: Long,
    val verifiedAt: Long?,
    /** Progresul citit înapoi din destinație după scriere. */
    val readBackProgress: Float?,
    val error: String?,
)
