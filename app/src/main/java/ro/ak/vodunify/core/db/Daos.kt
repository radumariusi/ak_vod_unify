package ro.ak.vodunify.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Upsert
    suspend fun upsert(items: List<MediaEntity>)

    @Query("SELECT * FROM media WHERE `key` = :key")
    suspend fun get(key: String): MediaEntity?

    @Query("SELECT COUNT(*) FROM media")
    fun count(): Flow<Int>
}

@Dao
interface TrackingDao {
    // ---------- Punct de reluare (playback) ----------
    @Query("DELETE FROM resume_points WHERE source = :source")
    suspend fun deleteResume(source: String)

    @Upsert
    suspend fun upsertResume(items: List<ResumePointEntity>)

    @Transaction
    suspend fun replaceResume(source: String, items: List<ResumePointEntity>) {
        deleteResume(source)
        upsertResume(items)
    }

    /** Peste 95% considerăm terminat (Trakt mută automat în istoric de la 80%+ la scrobble). */
    @Query("SELECT * FROM resume_points WHERE progress IS NULL OR progress < 0.95 ORDER BY updatedAt DESC")
    fun continueWatching(): Flow<List<ResumePointEntity>>

    @Query("SELECT * FROM resume_points WHERE mediaKey = :mediaKey ORDER BY updatedAt DESC")
    fun resumeFor(mediaKey: String): Flow<List<ResumePointEntity>>

    @Query("DELETE FROM resume_points WHERE id = :id")
    suspend fun deleteResumeById(id: String)

    @Query("SELECT * FROM resume_points ORDER BY updatedAt DESC")
    fun allResume(): Flow<List<ResumePointEntity>>

    // ---------- Watchlist ----------
    @Query("DELETE FROM watchlist WHERE source = :source")
    suspend fun deleteWatchlist(source: String)

    @Upsert
    suspend fun upsertWatchlist(items: List<WatchlistEntity>)

    @Transaction
    suspend fun replaceWatchlist(source: String, items: List<WatchlistEntity>) {
        deleteWatchlist(source)
        upsertWatchlist(items)
    }

    @Query("SELECT * FROM watchlist ORDER BY listedAt DESC")
    fun watchlist(): Flow<List<WatchlistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM watchlist WHERE mediaKey = :mediaKey)")
    fun inWatchlist(mediaKey: String): Flow<Boolean>

    @Query("DELETE FROM watchlist WHERE mediaKey = :mediaKey")
    suspend fun removeFromWatchlist(mediaKey: String)

    // ---------- Următorul episod ----------
    @Query("DELETE FROM up_next WHERE source = :source")
    suspend fun deleteUpNext(source: String)

    @Upsert
    suspend fun upsertUpNext(items: List<UpNextEntity>)

    @Transaction
    suspend fun replaceUpNext(source: String, items: List<UpNextEntity>) {
        deleteUpNext(source)
        upsertUpNext(items)
    }

    @Query("DELETE FROM up_next WHERE id = :id")
    suspend fun deleteUpNextById(id: String)

    @Query("SELECT * FROM up_next ORDER BY lastWatchedAt DESC")
    fun upNext(): Flow<List<UpNextEntity>>

    // ---------- Titluri văzute ----------
    @Query("DELETE FROM watched_titles WHERE source = :source")
    suspend fun deleteWatched(source: String)

    @Upsert
    suspend fun upsertWatched(items: List<WatchedTitleEntity>)

    @Transaction
    suspend fun replaceWatched(source: String, items: List<WatchedTitleEntity>) {
        deleteWatched(source)
        upsertWatched(items)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM watched_titles WHERE mediaKey = :mediaKey)")
    fun isWatched(mediaKey: String): Flow<Boolean>

    @Query("DELETE FROM watched_titles WHERE mediaKey = :mediaKey")
    suspend fun removeWatched(mediaKey: String)

    @Query("SELECT * FROM watched_titles ORDER BY lastWatchedAt DESC")
    fun watchedTitles(): Flow<List<WatchedTitleEntity>>

    // ---------- Istoric ----------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(events: List<WatchEventEntity>)

    @Query("SELECT * FROM watch_events ORDER BY watchedAt DESC LIMIT :limit")
    fun recentHistory(limit: Int = 50): Flow<List<WatchEventEntity>>

    @Query("SELECT COUNT(*) FROM watch_events WHERE source = :source")
    fun historyCount(source: String): Flow<Int>

    /** Ultima vizionare a fiecărui element (film / episod), pe sursă. */
    @Query(
        "SELECT * FROM watch_events w WHERE w.watchedAt = (SELECT MAX(x.watchedAt) FROM watch_events x " +
            "WHERE x.source = w.source AND x.mediaKey = w.mediaKey AND x.season IS w.season AND x.episode IS w.episode)",
    )
    fun latestWatches(): Flow<List<WatchEventEntity>>

    /** Șterge vizionările unei surse pentru un film/serial (season/episode null) sau un episod anume. */
    @Query("DELETE FROM watch_events WHERE source = :source AND mediaKey = :mediaKey AND season IS :season AND episode IS :episode")
    suspend fun deleteHistory(source: String, mediaKey: String, season: Int?, episode: Int?)

    @Query("SELECT * FROM watch_events WHERE mediaKey = :mediaKey ORDER BY watchedAt DESC")
    fun historyFor(mediaKey: String): Flow<List<WatchEventEntity>>

    /** Câte vizionări ale aceluiași film / episod există deja (din orice sursă) în intervalul dat. */
    @Query(
        "SELECT COUNT(*) FROM watch_events WHERE mediaKey = :mediaKey AND season IS :season AND episode IS :episode " +
            "AND watchedAt BETWEEN :from AND :to",
    )
    suspend fun countWatchesBetween(mediaKey: String, season: Int?, episode: Int?, from: Long, to: Long): Int

    // ---------- Scrierile aplicației (pentru reconciliere) ----------
    @Upsert
    suspend fun putOwnWrites(items: List<OwnWriteEntity>)

    @Query("SELECT * FROM own_writes")
    fun ownWrites(): Flow<List<OwnWriteEntity>>

    @Query("DELETE FROM own_writes WHERE at < :before")
    suspend fun pruneOwnWrites(before: Long)

    // ---------- Stare sincronizare ----------
    @Query("SELECT * FROM sync_state WHERE source = :source")
    suspend fun syncState(source: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE source = :source")
    fun syncStateFlow(source: String): Flow<SyncStateEntity?>

    @Upsert
    suspend fun putSyncState(state: SyncStateEntity)

    /** La deconectare: șterge tot ce a venit din sursă. */
    @Transaction
    suspend fun clearSource(source: String) {
        deleteResume(source)
        deleteWatchlist(source)
        deleteUpNext(source)
        deleteWatched(source)
        deleteHistory(source)
        deleteSyncState(source)
        deleteOwnWrites(source)
    }

    @Query("DELETE FROM own_writes WHERE source = :source")
    suspend fun deleteOwnWrites(source: String)

    @Query("DELETE FROM watch_events WHERE source = :source")
    suspend fun deleteHistory(source: String)

    @Query("DELETE FROM sync_state WHERE source = :source")
    suspend fun deleteSyncState(source: String)
}

@Dao
interface PublishDao {
    @Upsert
    suspend fun upsert(record: PublishRecordEntity)

    @Query("SELECT * FROM publish_records WHERE id = :id")
    suspend fun get(id: String): PublishRecordEntity?

    @Query("SELECT * FROM publish_records ORDER BY attemptAt DESC")
    fun all(): Flow<List<PublishRecordEntity>>

    @Query("SELECT * FROM publish_records WHERE status IN (:statuses)")
    suspend fun withStatus(statuses: List<String>): List<PublishRecordEntity>

    @Query("DELETE FROM publish_records WHERE attemptAt < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM publish_records WHERE destination = :destination")
    suspend fun clearDestination(destination: String)
}
