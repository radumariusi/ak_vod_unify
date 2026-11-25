package ro.ak.vodunify.core.scrobble

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.db.VodDatabase
import javax.inject.Inject

/** Recuperează pachetul din jurnal, din memoria permanentă a aplicațiilor sau din istoricul Room. */
class PlaybackSourceRecovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TrackingDao,
    private val db: VodDatabase,
    private val json: Json,
) {
    private val appHistorySerializer = MapSerializer(String.serializer(), String.serializer())

    suspend fun restore(points: List<ResumePointEntity>): List<ResumePointEntity> {
        if (points.none { it.appPackage.isNullOrBlank() }) return points
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(SCROBBLE_PREFS, Context.MODE_PRIVATE)
            val log = runCatching {
                prefs.getString(SCROBBLE_LOG, null)
                    ?.let { json.decodeFromString(ListSerializer(ScrobbleLogEntry.serializer()), it) }
            }.getOrNull().orEmpty()
            val appHistory = runCatching {
                prefs.getString(SCROBBLE_APP_HISTORY, null)
                    ?.let { json.decodeFromString(appHistorySerializer, it) }
            }.getOrNull().orEmpty()

            val packages = HashMap<String, String>()
            for (point in points) {
                if (!point.appPackage.isNullOrBlank()) continue
                val itemKey = point.id.substringAfter('|')
                val pkg = recordedPackage(point, log)
                    ?: relaxedLogPackage(point, log)
                    ?: appHistory[itemKey]?.takeIf { it.isNotBlank() }
                    ?: appHistory[point.ref.mediaKey]?.takeIf { it.isNotBlank() }
                    ?: dao.latestAppForMedia(point.ref.mediaKey)?.takeIf { it.isNotBlank() }
                if (pkg != null) packages[point.id] = pkg
            }
            if (packages.isEmpty()) return@withContext points
            db.withTransaction {
                points.map { point ->
                    val pkg = packages[point.id] ?: return@map point
                    if (dao.restoreResumePackageAny(point.id, pkg) > 0) point.copy(appPackage = pkg)
                    else point
                }
            }
        }
    }
}

internal fun relaxedLogPackage(point: ResumePointEntity, log: List<ScrobbleLogEntry>): String? {
    if (!point.appPackage.isNullOrBlank()) return null
    val itemKey = point.id.substringAfter('|')
    val titleKey = point.ref.mediaKey
    val valid = log.asSequence().filter {
        (it.result == ScrobbleResult.SAVED.wire || it.result == ScrobbleResult.WATCHED.wire) && it.pkg.isNotBlank()
    }
    return valid.firstOrNull { it.itemKey == itemKey }?.pkg?.trim()
        ?: valid.firstOrNull { entry ->
            val k = entry.itemKey ?: return@firstOrNull false
            k == titleKey || k.startsWith("$titleKey:")
        }?.pkg?.trim()
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
