package ro.ak.vodunify.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import ro.ak.vodunify.core.connector.seasonTrackingKey
import ro.ak.vodunify.core.publish.LOCAL_SOURCE

private fun TrackedRef.manualKeys(itemKey: String) =
    listOfNotNull(itemKey, mediaKey, season?.let { seasonTrackingKey(mediaKey, it) })

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

    @Query("SELECT * FROM resume_points WHERE id = :id")
    suspend fun resumeById(id: String): ResumePointEntity?

    @Query("UPDATE resume_points SET appPackage = :pkg WHERE id = :id AND updatedAt = :at AND positionMs = :positionMs AND (appPackage IS NULL OR TRIM(appPackage) = '')")
    suspend fun restoreResumePackage(id: String, at: Long, positionMs: Long, pkg: String): Int

    @Transaction
    suspend fun upsertResumeIfNewer(item: ResumePointEntity): Boolean {
        val current = resumeById(item.id)
        if (current != null && current.updatedAt > item.updatedAt) return false
        if (hasWatchAtOrAfter(item.source, item.ref.mediaKey, item.ref.season, item.ref.episode, item.updatedAt)) return false
        if (item.source == LOCAL_SOURCE && hasManualMarkAtOrAfter(item.ref.manualKeys(item.id.substringAfter('|')), item.updatedAt)) return false
        upsertResume(listOf(item.copy(appPackage = item.appPackage?.takeIf { it.isNotBlank() } ?: current?.appPackage)))
        return true
    }

    @Query(
        "SELECT EXISTS(SELECT 1 FROM watch_events WHERE source = :source AND mediaKey = :key " +
            "AND season IS :season AND episode IS :episode AND watchedAt >= :at)",
    )
    suspend fun hasWatchAtOrAfter(source: String, key: String, season: Int?, episode: Int?, at: Long): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM own_writes WHERE source = 'local' AND itemKey IN (:keys) " +
            "AND action IN ('WATCHED', 'UNWATCHED') AND at >= :at)",
    )
    suspend fun hasManualMarkAtOrAfter(keys: List<String>, at: Long): Boolean

    @Query("DELETE FROM resume_points WHERE id = :id AND updatedAt <= :at")
    suspend fun deleteResumeByIdBefore(id: String, at: Long)

    @Transaction
    suspend fun replaceResume(source: String, items: List<ResumePointEntity>) {
        deleteResume(source)
        upsertResume(items)
    }

    @Query("SELECT * FROM resume_points WHERE mediaKey = :mediaKey ORDER BY updatedAt DESC")
    fun resumeFor(mediaKey: String): Flow<List<ResumePointEntity>>

    @Query("DELETE FROM resume_points WHERE id = :id")
    suspend fun deleteResumeById(id: String)

    @Query("DELETE FROM resume_points WHERE mediaKey = :mediaKey")
    suspend fun deleteResumeFor(mediaKey: String)

    @Query("DELETE FROM resume_points WHERE id IN (:ids)")
    suspend fun deleteResumeItems(ids: List<String>)

    @Query("DELETE FROM resume_points WHERE mediaKey = :mediaKey AND season IN (:seasons)")
    suspend fun deleteSeasonResumes(mediaKey: String, seasons: List<Int>)

    @Query("SELECT * FROM resume_points ORDER BY updatedAt DESC")
    fun allResume(): Flow<List<ResumePointEntity>>

    @Query("SELECT * FROM resume_points ORDER BY updatedAt DESC")
    suspend fun readResume(): List<ResumePointEntity>

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
    suspend fun watchlist(): List<WatchlistEntity>

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
    suspend fun upNext(): List<UpNextEntity>

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

    @Query("DELETE FROM watched_titles WHERE mediaKey = :mediaKey")
    suspend fun removeWatched(mediaKey: String)

    @Query("DELETE FROM watched_titles WHERE mediaKey = :mediaKey AND source NOT IN (:keep)")
    suspend fun removeWatchedFromVisibleSources(mediaKey: String, keep: List<String>)

    @Query("SELECT * FROM watched_titles ORDER BY lastWatchedAt DESC")
    suspend fun watchedTitles(): List<WatchedTitleEntity>

    // ---------- Istoric ----------
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(events: List<WatchEventEntity>)

    @Query("SELECT * FROM watch_events ORDER BY watchedAt DESC LIMIT :limit")
    fun recentHistory(limit: Int = 50): Flow<List<WatchEventEntity>>

    @Query("SELECT COUNT(*) FROM watch_events WHERE source = :source")
    fun historyCount(source: String): Flow<Int>

    /** Ultima vizionare a fiecărui element (film / episod), pe sursă. */
    @Query(
        "SELECT w.* FROM watch_events w JOIN (SELECT source, mediaKey, season, episode, MAX(watchedAt) AS lastAt " +
            "FROM watch_events GROUP BY source, mediaKey, season, episode) latest ON latest.source = w.source " +
            "AND latest.mediaKey = w.mediaKey AND latest.season IS w.season AND latest.episode IS w.episode " +
            "AND latest.lastAt = w.watchedAt",
    )
    suspend fun latestWatches(): List<WatchEventEntity>

    @Query(
        "SELECT mediaKey, source, appPackage, MAX(COALESCE(playbackAt, watchedAt)) AS at FROM watch_events " +
            "WHERE (source = :local AND appPackage IS NOT NULL AND playbackAt IS NOT NULL) OR source IN (:imports) " +
            "GROUP BY mediaKey",
    )
    fun latestPlaybackApps(local: String, imports: List<String>): Flow<List<TitlePlaybackApp>>

    @Transaction
    suspend fun completePlayback(resumeId: String, event: WatchEventEntity, watched: WatchedTitleEntity?): Boolean {
        if (hasManualMarkAtOrAfter(event.ref.manualKeys(resumeId.substringAfter('|')), event.watchedAt)) return false
        insertHistory(listOf(event))
        if (watched != null) upsertWatched(listOf(watched))
        deleteResumeByIdBefore(resumeId, event.watchedAt)
        return true
    }

    /** Șterge vizionările unei surse pentru un film/serial (season/episode null) sau un episod anume. */
    @Query("DELETE FROM watch_events WHERE source = :source AND mediaKey = :mediaKey AND season IS :season AND episode IS :episode")
    suspend fun deleteHistory(source: String, mediaKey: String, season: Int?, episode: Int?)

    /**
     * Șterge vizionările sursei [drop] pentru filmele / episoadele care există deja în sursa [keep]
     * (ex. din Trakt nu păstrăm ce vine din istoricul Netflix). Întoarce câte rânduri a șters.
     */
    @Query(
        "DELETE FROM watch_events WHERE source = :drop AND EXISTS (SELECT 1 FROM watch_events k WHERE k.source = :keep " +
            "AND k.mediaKey = watch_events.mediaKey AND k.season IS watch_events.season AND k.episode IS watch_events.episode)",
    )
    suspend fun deleteHistoryCoveredBy(drop: String, keep: String): Int

    @Query("SELECT * FROM watch_events WHERE mediaKey = :mediaKey ORDER BY watchedAt DESC")
    fun historyFor(mediaKey: String): Flow<List<WatchEventEntity>>

    /** Șterge vizionările unui film / episod din toate sursele, în afară de [keep] (ex. Trakt/SIMKL ascunse). */
    @Query(
        "DELETE FROM watch_events WHERE mediaKey = :mediaKey AND season IS :season AND episode IS :episode " +
            "AND source NOT IN (:keep)",
    )
    suspend fun deleteItemHistory(mediaKey: String, season: Int?, episode: Int?, keep: List<String>): Int

    @Query("DELETE FROM watch_events WHERE mediaKey = :mediaKey AND season IN (:seasons) AND source NOT IN (:keep)")
    suspend fun deleteSeasonHistory(mediaKey: String, seasons: List<Int>, keep: List<String>): Int

    /** Șterge tot istoricul unui titlu (film sau toate episoadele serialului), în afară de sursele [keep]. */
    @Query("DELETE FROM watch_events WHERE mediaKey = :mediaKey AND source NOT IN (:keep)")
    suspend fun deleteTitleHistory(mediaKey: String, keep: List<String>): Int

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
    suspend fun ownWrites(): List<OwnWriteEntity>

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
