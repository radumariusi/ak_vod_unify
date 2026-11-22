package ro.ak.vodunify.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaEntity::class,
        WatchEventEntity::class,
        ResumePointEntity::class,
        WatchlistEntity::class,
        UpNextEntity::class,
        WatchedTitleEntity::class,
        SyncStateEntity::class,
        OwnWriteEntity::class,
        PublishRecordEntity::class,
        TmdbCacheEntity::class,
        AvailabilityEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class VodDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun trackingDao(): TrackingDao
    abstract fun publishDao(): PublishDao
    abstract fun tmdbCacheDao(): TmdbCacheDao
    abstract fun availabilityDao(): AvailabilityDao
}
