package ro.ak.vodunify.core.wetrakr

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.arr
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.bool
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.long
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.obj
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.str
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class WeTrakrSnapshot(
    /** Rândurile pe cheie (vezi [WeTrakrMappers.rowKey]). */
    val rows: Map<String, JsonObject>,
    /** Când am văzut prima oară fiecare rând marcat „planning” (WeTrakr nu dă data). */
    val plannedAt: Map<String, Long>,
    val inProgress: List<JsonObject>,
    val nextEpisodes: List<JsonObject>,
)

/**
 * Biblioteca WeTrakr ținută local: o dată un „snapshot” complet, apoi doar jurnalul
 * de schimbări (`/changes?since=`), exact ca add-on-ul lor.
 */
@Singleton
class WeTrakrLibrary @Inject constructor(
    @ApplicationContext context: Context,
    private val api: WeTrakrApi,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, "wetrakr_library.json")
    private val mutex = Mutex()
    @Volatile private var memory: State? = null
    @Volatile private var checkedAt = 0L

    private data class State(val since: String?, val snapshot: WeTrakrSnapshot)

    suspend fun snapshot(force: Boolean = false): WeTrakrSnapshot = mutex.withLock {
        val now = System.currentTimeMillis()
        memory?.let { if (!force && now - checkedAt < FRESH_MS) return@withLock it.snapshot }
        val fresh = memory == null
        var state: State = memory ?: load()?.takeIf { it.since != null } ?: fullSnapshot()
        state = applyChanges(state, full = fresh)
        val next = runCatching {
            api.integration("next-episodes", mapOf("limit" to "50"))["items"].arr()?.mapNotNull { it.obj() }
        }.getOrNull() ?: state.snapshot.nextEpisodes
        state = state.copy(snapshot = state.snapshot.copy(nextEpisodes = next))
        memory = state
        checkedAt = System.currentTimeMillis()
        save(state)
        state.snapshot
    }

    fun invalidate() {
        checkedAt = 0L
    }

    suspend fun clear() = mutex.withLock {
        memory = null
        checkedAt = 0L
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun fullSnapshot(): State {
        val rows = LinkedHashMap<String, JsonObject>()
        var since: String? = null
        for (section in SECTIONS) {
            var cursor: String? = null
            var pages = 0
            do {
                val page = api.integration("snapshot", mapOf("section" to section, "cursor" to cursor))
                if (since == null) since = page["cursor_since"].str()
                page["titles"].arr()?.forEach { r -> r.obj()?.let { row -> merge(rows, row) } }
                cursor = page["next_cursor"].str()
                pages++
            } while (cursor != null && pages < MAX_PAGES)
        }
        val now = System.currentTimeMillis()
        val planned = rows.filterValues { it["planning"].bool() }.mapValues { now }
        return State(since ?: Instant.now().toString(), WeTrakrSnapshot(rows, planned, emptyList(), emptyList()))
    }

    /** Secțiunile snapshot-ului (ex. „tracks” și „ratings”) se completează reciproc pe același titlu. */
    private fun merge(rows: MutableMap<String, JsonObject>, row: JsonObject) {
        val key = WeTrakrMappers.rowKey(row) ?: return
        val old = rows[key]
        rows[key] = if (old == null) row else JsonObject(old + row.filterValues { it !is JsonNull })
    }

    private suspend fun applyChanges(start: State, full: Boolean): State {
        var state = start
        var useFull = full
        var pages = 0
        while (pages < MAX_PAGES) {
            val changes = try {
                api.integration("changes", mapOf("since" to state.since, "full" to if (useFull) "1" else null))
            } catch (e: WeTrakrHttpException) {
                if (e.error == "SNAPSHOT_REQUIRED" || e.error == "JOURNAL_EXPIRED") {
                    // Prea mult timp fără sincronizare: jurnalul nu mai ajunge atât de departe.
                    state = fullSnapshot()
                    useFull = true
                    pages++
                    continue
                }
                throw e
            }
            val nextSince = changes["next_since"].str() ?: state.since
            if (changes["unchanged"].bool()) return state.copy(since = nextSince)

            val rows = state.snapshot.rows.toMutableMap()
            val planned = state.snapshot.plannedAt.toMutableMap()
            val now = System.currentTimeMillis()
            changes["titles"].arr()?.forEach { r ->
                val row = r.obj() ?: return@forEach
                val key = WeTrakrMappers.rowKey(row) ?: return@forEach
                rows[key] = row
                if (row["planning"].bool()) planned.putIfAbsent(key, now) else planned.remove(key)
            }
            val progress = changes["in_progress"].arr()?.mapNotNull { it.obj() } ?: state.snapshot.inProgress
            state = State(nextSince, state.snapshot.copy(rows = rows, plannedAt = planned, inProgress = progress))
            useFull = true
            pages++
            if (!changes["has_more"].bool()) break
        }
        return state
    }

    // ---------- Persistență ----------

    private suspend fun load(): State? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val root = json.parseToJsonElement(file.readText()).obj() ?: return@runCatching null
            val rows = root["rows"].arr()?.mapNotNull { it.obj() }
                ?.mapNotNull { row -> WeTrakrMappers.rowKey(row)?.let { it to row } }?.toMap() ?: emptyMap()
            val planned = root["planned_at"].obj()?.mapNotNull { (k, v) -> v.long()?.let { k to it } }?.toMap() ?: emptyMap()
            State(
                root["since"].str(),
                WeTrakrSnapshot(
                    rows,
                    planned,
                    root["in_progress"].arr()?.mapNotNull { it.obj() } ?: emptyList(),
                    root["next_episodes"].arr()?.mapNotNull { it.obj() } ?: emptyList(),
                ),
            )
        }.getOrNull()
    }

    private suspend fun save(s: State) = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            s.since?.let { put("since", it) }
            put("rows", JsonArray(s.snapshot.rows.values.toList()))
            put("planned_at", JsonObject(s.snapshot.plannedAt.mapValues { JsonPrimitive(it.value) }))
            put("in_progress", JsonArray(s.snapshot.inProgress))
            put("next_episodes", JsonArray(s.snapshot.nextEpisodes))
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val FRESH_MS = 60_000L
        const val MAX_PAGES = 200
        val SECTIONS = listOf("tracks", "ratings")
    }
}
