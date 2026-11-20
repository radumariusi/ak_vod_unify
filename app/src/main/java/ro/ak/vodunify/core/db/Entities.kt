package ro.ak.vodunify.core.db

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

@Entity(tableName = "watch_events", indices = [Index("mediaKey"), Index("source")])
data class WatchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaKey: String,
    val source: String,
    val season: Int?,
    val episode: Int?,
    val watchedAt: Long,
)

@Entity(tableName = "resume_points", primaryKeys = ["mediaKey", "source"])
data class ResumePointEntity(
    val mediaKey: String,
    val source: String,
    val progress: Float,
    val updatedAt: Long,
)
