package ro.ak.vodunify.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
interface WatchEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(events: List<WatchEventEntity>)

    @Query("SELECT * FROM watch_events ORDER BY watchedAt DESC LIMIT :limit")
    fun recent(limit: Int = 50): Flow<List<WatchEventEntity>>
}

@Dao
interface ResumePointDao {
    @Upsert
    suspend fun upsert(points: List<ResumePointEntity>)

    @Query("SELECT * FROM resume_points WHERE progress < 0.95 ORDER BY updatedAt DESC")
    fun continueWatching(): Flow<List<ResumePointEntity>>
}
