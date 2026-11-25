package ro.ak.vodunify.core.scrobble

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.db.VodDatabase
import javax.inject.Inject

/** Recuperează pachetul din jurnal numai pentru același eveniment care a salvat poziția. */
class PlaybackSourceRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrackingDao,
    private val db: VodDatabase,
    private val json: Json,
) {
    suspend fun restore(points: List<ResumePointEntity>): List<ResumePointEntity> {
        if (points.none { it.appPackage.isNullOrBlank() && it.positionMs != null }) return points
        return withContext(Dispatchers.IO) {
            val log = runCatching {
                context.getSharedPreferences(SCROBBLE_PREFS, Context.MODE_PRIVATE)
                    .getString(SCROBBLE_LOG, null)
                    ?.let { json.decodeFromString(ListSerializer(ScrobbleLogEntry.serializer()), it) }
            }.getOrNull().orEmpty()
            val packages = points.mapNotNull { point -> recordedPackage(point, log)?.let { point.id to it } }.toMap()
            if (packages.isEmpty()) return@withContext points
            db.withTransaction {
                points.map { point ->
                    val position = point.positionMs ?: return@map point
                    val pkg = packages[point.id] ?: return@map point
                    if (dao.restoreResumePackage(point.id, point.updatedAt, position, pkg) > 0) point.copy(appPackage = pkg)
                    else point
                }
            }
        }
    }
}

internal fun recordedPackage(point: ResumePointEntity, log: List<ScrobbleLogEntry>): String? {
    if (!point.appPackage.isNullOrBlank() || point.positionMs == null) return null
    val ref = point.ref
    val label = if (ref.type == "EPISODE") "${ref.title} S${ref.season}E${ref.episode}"
    else ref.title + (ref.year?.let { " ($it)" } ?: "")
    return log.asSequence().filter { entry ->
        entry.result == ScrobbleResult.SAVED.wire && entry.at == point.updatedAt && entry.positionMs == point.positionMs &&
            if (entry.itemKey != null) entry.itemKey == point.id.substringAfter('|')
            else entry.matched == label || (ref.type == "EPISODE" && entry.matched?.startsWith("$label · ") == true)
    }.map { it.pkg.trim() }.filter { it.isNotEmpty() }.distinct().toList().singleOrNull()
}
