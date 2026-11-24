package ro.ak.vodunify.core.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * Cache brut pentru răspunsurile TMDb. Cheia include calea, limba și piața (regiunea),
 * deci același titlu are intrări separate pe RO/US și pe limbă.
 */
@Entity(tableName = "tmdb_cache")
data class TmdbCacheEntity(
    @PrimaryKey val key: String,
    val body: String,
    val fetchedAt: Long,
)

/** Disponibilitate (TMDb Watch Providers / JustWatch) pe titlu și piață. */
@Entity(tableName = "availability", primaryKeys = ["mediaKey", "market"])
data class AvailabilityEntity(
    val mediaKey: String, // ex. "movie:tmdb:603"
    val market: String, // "RO" / "US"
    val link: String?,
    /** Furnizori pe abonament, separați prin „|” (ex. "8:Netflix|337:Disney Plus"). */
    val flatrate: String,
    val free: String,
    val rent: String,
    val buy: String,
    val updatedAt: Long,
)

@Dao
interface TmdbCacheDao {
    @Query("SELECT * FROM tmdb_cache WHERE `key` = :key")
    suspend fun get(key: String): TmdbCacheEntity?

    @Upsert
    suspend fun put(entry: TmdbCacheEntity)

    @Query("SELECT * FROM tmdb_cache WHERE `key` BETWEEN :first AND :last ORDER BY `key`")
    suspend fun window(first: String, last: String): List<TmdbCacheEntity>

    @Upsert
    suspend fun putAll(entries: List<TmdbCacheEntity>)

    @Query("DELETE FROM tmdb_cache WHERE fetchedAt < :olderThan")
    suspend fun prune(olderThan: Long)

    @Query("DELETE FROM tmdb_cache")
    suspend fun clear()
}

@Dao
interface AvailabilityDao {
    @Upsert
    suspend fun upsert(items: List<AvailabilityEntity>)

    @Query("SELECT * FROM availability WHERE mediaKey = :mediaKey")
    suspend fun forMedia(mediaKey: String): List<AvailabilityEntity>
}
