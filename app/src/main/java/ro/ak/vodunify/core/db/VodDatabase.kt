package ro.ak.vodunify.core.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaEntity::class,
        WatchEventEntity::class,
        ResumePointEntity::class,
        TmdbCacheEntity::class,
        AvailabilityEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class VodDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun watchEventDao(): WatchEventDao
    abstract fun resumePointDao(): ResumePointDao
    abstract fun tmdbCacheDao(): TmdbCacheDao
    abstract fun availabilityDao(): AvailabilityDao
}
