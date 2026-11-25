package ro.ak.vodunify.core.scrobble

import android.content.ContentProviderClient
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.vodimport.NetflixRecent
import ro.ak.vodunify.core.diagnostics.AppDiagLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Citește în loturi coada locală din AK Scrobbler atunci când AK VOD Unify intră în prim-plan.
 * Folosește exclusiv `acquireUnstableContentProviderClient` închis imediat după fiecare apel,
 * ca o eventuală oprire a unui proces să nu provoace niciodată `DEPENDENCY DIED` în celălalt.
 */
@Singleton
class ScrobblePuller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScrobbleRepository,
    private val json: Json,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pullMutex = Mutex()
    private val eventsSerializer = ListSerializer(ScrobbleEvent.serializer())

    fun pullInForegroundAsync(forceRetryUnresolved: Boolean = false) {
        scope.launch {
            pullInForeground(forceRetryUnresolved)
        }
    }

    suspend fun pullInForeground(forceRetryUnresolved: Boolean = false): Int = pullMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!repository.isScrobblerInstalled()) {
                if (forceRetryUnresolved) {
                    repository.retryUnresolved()
                    scanNetflixInForeground()
                }
                return@withContext 0
            }
            val auth = "${repository.scrobblerPackage}.diag"
            var totalAcked = 0
            for (round in 0 until MAX_BATCHES) {
                val batch = queryQueue(auth, BATCH_SIZE) ?: break
                if (batch.isEmpty()) break
                val confirmedIds = repository.importBatch(batch)
                if (confirmedIds.isNotEmpty()) {
                    val acked = ackQueue(auth, confirmedIds)
                    totalAcked += acked
                    AppDiagLog.add("ScrobblePuller: lot ${batch.size} -> confirmate=${confirmedIds.size}, șterse din Scrobbler=$acked")
                }
                if (batch.size < BATCH_SIZE || confirmedIds.isEmpty()) break
            }
            if (forceRetryUnresolved || repository.unresolved.value > 0) {
                repository.retryUnresolved()
                scanNetflixInForeground(auth)
            }
            totalAcked
        }
    }

    private suspend fun scanNetflixInForeground(auth: String = "${repository.scrobblerPackage}.diag") {
        if (!repository.hasRecentUnresolvedNetflix()) return
        val local = if (NetflixRecent.hasSession()) {
            NetflixRecent.scan(context)
        } else {
            queryNetflixFromScrobbler(auth) ?: return
        }
        val parsed = runCatching {
            json.decodeFromString(NetflixRecentResult.serializer(), local.toString())
        }.getOrNull() ?: return
        repository.applyNetflixRecent(parsed)
    }

    private fun queryQueue(authority: String, limit: Int): List<ScrobbleEvent>? {
        val uri = Uri.parse("content://$authority/queue?limit=$limit")
        val raw = withUnstableClient(uri) { client ->
            client.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } ?: return null
        if (raw.isBlank() || raw == "[]") return emptyList()
        return runCatching { json.decodeFromString(eventsSerializer, raw) }
            .onFailure { AppDiagLog.add("ScrobblePuller: JSON invalid din coadă: ${it.message}") }
            .getOrNull()
    }

    private fun ackQueue(authority: String, ids: List<String>): Int {
        val uri = Uri.parse("content://$authority/ack")
        val values = ContentValues().apply {
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            put("ids", arr.toString())
        }
        val res = withUnstableClient(uri) { client ->
            client.insert(uri, values)?.lastPathSegment?.toIntOrNull()
        }
        return res ?: 0
    }

    private fun queryNetflixFromScrobbler(authority: String): JSONObject? {
        val uri = Uri.parse("content://$authority/netflix_recent?trigger=1")
        val raw = withUnstableClient(uri) { client ->
            client.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private inline fun <T> withUnstableClient(uri: Uri, block: (ContentProviderClient) -> T): T? {
        val client = runCatching { context.contentResolver.acquireUnstableContentProviderClient(uri) }
            .onFailure { AppDiagLog.add("ScrobblePuller: client provider eșuat (${it.message})") }
            .getOrNull() ?: return null
        return try {
            block(client)
        } catch (e: Exception) {
            AppDiagLog.add("ScrobblePuller: eroare apel provider ${uri.lastPathSegment}: ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            closeClient(client)
        }
    }

    private fun closeClient(client: ContentProviderClient) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { client.close() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { client.release() }
        }
    }

    companion object {
        private const val BATCH_SIZE = 20
        private const val MAX_BATCHES = 20
    }
}
