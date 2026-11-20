package ro.ak.vodunify.core.simkl

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import ro.ak.vodunify.core.simkl.SimklMappers.Kind
import ro.ak.vodunify.core.simkl.SimklMappers.arr
import ro.ak.vodunify.core.simkl.SimklMappers.obj
import ro.ak.vodunify.core.simkl.SimklMappers.str
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Biblioteca SIMKL descărcată: intrările pe tip (cheie = id SIMKL) și redările în pauză. */
data class SimklSnapshot(
    val items: Map<Kind, Map<Long, JsonObject>>,
    val playback: List<JsonObject>,
)

/**
 * Sincronizare SIMKL după regulile lor: întâi `/sync/activities`; `/sync/all-items` se
 * cere doar pentru tipurile care s-au schimbat și doar cu `date_from` (altfel SIMKL
 * suspendă aplicația). Rezultatul se ține pe disc, ca să nu descărcăm totul la fiecare pornire.
 */
@Singleton
class SimklLibrary @Inject constructor(
    @ApplicationContext context: Context,
    private val api: SimklApi,
    private val json: Json,
) {
    private val file = File(context.noBackupFilesDir, "simkl_library.json")
    private val mutex = Mutex()
    @Volatile private var memory: Cached? = null
    @Volatile private var checkedAt = 0L

    private data class Cached(val activities: JsonObject?, val snapshot: SimklSnapshot)

    /** Biblioteca actuală; verifică activitățile cel mult o dată pe minut. */
    suspend fun snapshot(force: Boolean = false): SimklSnapshot = mutex.withLock {
        val now = System.currentTimeMillis()
        memory?.let { if (!force && now - checkedAt < FRESH_MS) return@withLock it.snapshot }
        val cached = memory ?: load()
        val activities = api.get("sync/activities").obj()
        val updated = update(cached, activities)
        memory = updated
        checkedAt = System.currentTimeMillis()
        if (updated !== cached) save(updated)
        updated.snapshot
    }

    /** După o scriere: următoarea citire verifică din nou activitățile. */
    fun invalidate() {
        checkedAt = 0L
    }

    suspend fun clear() = mutex.withLock {
        memory = null
        checkedAt = 0L
        withContext(Dispatchers.IO) { file.delete() }
    }

    private suspend fun update(cached: Cached?, activities: JsonObject?): Cached {
        val old = cached?.activities
        if (cached != null && old != null && activities != null && activities["all"].str() == old["all"].str()) return cached

        val items = Kind.entries.associateWith { (cached?.snapshot?.items?.get(it) ?: emptyMap()).toMutableMap() }
        for (kind in Kind.entries) {
            val newBucket = activities?.get(kind.activitiesKey).obj()
            val oldBucket = old?.get(kind.activitiesKey).obj()
            val target = items.getValue(kind)
            val since = oldBucket?.get("all").str()
            when {
                cached == null || old == null || since == null -> {
                    target.clear()
                    target += fetch(kind, null)
                }
                newBucket?.get("all").str() != since -> {
                    target += fetch(kind, since)
                    // Ștergerile nu apar la date_from: comparăm lista de id-uri.
                    if (newBucket?.get("removed_from_list").str() != oldBucket?.get("removed_from_list").str()) {
                        val ids = fetchIds(kind)
                        target.keys.retainAll(ids)
                    }
                }
            }
        }

        val playbackChanged = cached == null || old == null || Kind.entries.any { k ->
            activities?.get(k.activitiesKey).obj()?.get("playback").str() != old[k.activitiesKey].obj()?.get("playback").str()
        }
        val playback = if (playbackChanged) {
            api.get("sync/playback").arr()?.mapNotNull { it.obj() } ?: emptyList()
        } else {
            cached?.snapshot?.playback ?: emptyList()
        }
        return Cached(activities, SimklSnapshot(items.mapValues { it.value.toMap() }, playback))
    }

    private suspend fun fetch(kind: Kind, since: String?): Map<Long, JsonObject> {
        val params = mapOf(
            "extended" to "full",
            "episode_watched_at" to "yes",
            "date_from" to since,
        )
        val body = api.get("sync/all-items/${kind.path}", params).obj() ?: return emptyMap()
        val list = body[kind.path].arr() ?: return emptyMap()
        return list.mapNotNull { it.obj() }.mapNotNull { item -> SimklMappers.simklId(item, kind)?.let { it to item } }.toMap()
    }

    private suspend fun fetchIds(kind: Kind): Set<Long> {
        val body = api.get("sync/all-items/${kind.path}", mapOf("extended" to "simkl_ids_only")).obj() ?: return emptySet()
        return body[kind.path].arr()?.mapNotNull { it.obj()?.let { o -> SimklMappers.simklId(o, kind) } }?.toSet() ?: emptySet()
    }

    // ---------- Persistență ----------

    private suspend fun load(): Cached? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val root = json.parseToJsonElement(file.readText()).obj() ?: return@runCatching null
            val items = Kind.entries.associateWith { kind ->
                root[kind.path].arr()?.mapNotNull { it.obj() }
                    ?.mapNotNull { item -> SimklMappers.simklId(item, kind)?.let { it to item } }
                    ?.toMap() ?: emptyMap()
            }
            val playback = root["playback"].arr()?.mapNotNull { it.obj() } ?: emptyList()
            Cached(root["activities"].obj(), SimklSnapshot(items, playback))
        }.getOrNull()
    }

    private suspend fun save(c: Cached) = withContext(Dispatchers.IO) {
        val root = buildJsonObject {
            c.activities?.let { put("activities", it) }
            Kind.entries.forEach { kind -> put(kind.path, JsonArray(c.snapshot.items[kind].orEmpty().values.toList())) }
            put("playback", JsonArray(c.snapshot.playback))
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val FRESH_MS = 60_000L
    }
}
