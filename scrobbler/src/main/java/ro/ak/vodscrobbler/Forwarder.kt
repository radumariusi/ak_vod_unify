package ro.ak.vodscrobbler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** O intrare din jurnalul „Trimise recent”. */
data class SentEntry(val at: Long, val appLabel: String, val title: String, val positionMs: Long, val reason: String, val result: String)

/** Reprezentare pură (testabilă) a unui eveniment din coada locală persistentă. */
data class QueuedEvent(
    val id: String,
    val pkg: String,
    val app: String,
    val reason: String,
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val at: Long,
    val playId: String,
    val estimated: Boolean,
    val fields: Map<String, String>,
)

internal object QueueCompactor {
    /**
     * Adaugă [incoming] în coada [existing]:
     * - Pauzele, opririle și evenimentele cu alt `playId` se păstrează întotdeauna;
     * - Un `tick` nou pentru același `playId` îl înlocuiește doar pe un `tick` anterior fără pauză/oprire între ele,
     *   păstrând câmpurile deja descoperite (de ex. `prime_gti`, `netflix_id`) și titlul mai bun.
     */
    fun append(existing: List<QueuedEvent>, incoming: QueuedEvent, maxSize: Int = 200): List<QueuedEvent> {
        val out = existing.toMutableList()
        if (incoming.reason == SendReason.TICK.wire && incoming.playId.isNotEmpty()) {
            val prevTickIdx = out.indexOfLast { it.playId == incoming.playId }
            if (prevTickIdx >= 0 && out[prevTickIdx].reason == SendReason.TICK.wire) {
                val prev = out[prevTickIdx]
                val mergedFields = prev.fields + incoming.fields
                out.removeAt(prevTickIdx)
                out.add(incoming.copy(fields = mergedFields))
                return trim(out, maxSize)
            }
        }
        out.add(incoming)
        return trim(out, maxSize)
    }

    private fun trim(list: MutableList<QueuedEvent>, maxSize: Int): List<QueuedEvent> {
        while (list.size > maxSize) {
            val dropTick = list.indexOfFirst { it.reason == SendReason.TICK.wire }
            list.removeAt(if (dropTick >= 0) dropTick else 0)
        }
        return list
    }
}

/**
 * Coada locală persistentă a AK Scrobbler.
 *
 * Scrobblerul nu mai contactează și nu mai pornește AK VOD Unify în fundal (pentru a evita
 * `DEPENDENCY DIED` / `tguard-kill` pe Android TV): doar scrie durabil aici, iar AK VOD Unify
 * citește și confirmă loturile când intră în prim-plan.
 */
class Forwarder private constructor(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("forwarder", Context.MODE_PRIVATE)
    private val queueLock = Any()
    private val seq = AtomicLong(0L)

    fun send(o: Outgoing) {
        val wallClock = System.currentTimeMillis()
        val id = "$wallClock-${seq.incrementAndGet()}-${o.playId}"
        val event = QueuedEvent(
            id = id,
            pkg = o.packageName,
            app = o.appLabel,
            reason = o.reason.wire,
            state = o.state.name.lowercase(),
            positionMs = o.positionMs,
            durationMs = o.media.durationMs ?: -1L,
            at = wallClock,
            playId = o.playId,
            estimated = o.estimated,
            fields = LinkedHashMap(o.media.fields),
        )
        executor.execute {
            synchronized(queueLock) {
                val current = loadQueueLocked()
                val updated = QueueCompactor.append(current, event, MAX_PENDING)
                saveQueueLocked(updated)
                logLocked(event, "în coadă locală")
            }
        }
    }

    fun pendingCount(): Int = synchronized(queueLock) { loadQueueLocked().size }

    /** Citește cele mai vechi [limit] evenimente fără a le șterge din coadă. */
    fun peekBatch(limit: Int): JSONArray = synchronized(queueLock) {
        val list = loadQueueLocked()
        val out = JSONArray()
        for (e in list.take(limit.coerceIn(1, 100))) {
            out.put(toJson(e))
        }
        out
    }

    /** Șterge din coadă doar evenimentele confirmate explicit de AK VOD Unify. */
    fun ackIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        synchronized(queueLock) {
            val current = loadQueueLocked()
            val remaining = current.filterNot { it.id in ids }
            val removed = current.size - remaining.size
            if (removed > 0) {
                saveQueueLocked(remaining)
                markLogDeliveredLocked(ids)
            }
            return removed
        }
    }

    fun recent(): List<SentEntry> = synchronized(queueLock) {
        runCatching {
            val arr = JSONArray(prefs.getString(KEY_LOG, "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SentEntry(
                    at = o.optLong("at"),
                    appLabel = o.optString("app"),
                    title = o.optString("title"),
                    positionMs = o.optLong("position_ms"),
                    reason = o.optString("reason"),
                    result = o.optString("result"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun loadQueueLocked(): List<QueuedEvent> {
        val rawV2 = prefs.getString(KEY_QUEUE_V2, null)
        val rawLegacy = prefs.getString(KEY_PENDING, null)
        val result = mutableListOf<QueuedEvent>()
        var migrated = false

        if (!rawV2.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(rawV2)
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let(::fromJson)?.let(result::add)
                }
            }
        }
        if (!rawLegacy.isNullOrBlank() && rawLegacy != "[]") {
            runCatching {
                val arr = JSONArray(rawLegacy)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val e = fromJson(obj)
                    if (result.none { it.id == e.id }) result.add(e)
                }
                migrated = true
            }
        }
        if (migrated) {
            saveQueueLocked(result, clearLegacy = true)
        }
        return result
    }

    private fun saveQueueLocked(list: List<QueuedEvent>, clearLegacy: Boolean = false) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val edit = prefs.edit().putString(KEY_QUEUE_V2, arr.toString())
        if (clearLegacy || prefs.contains(KEY_PENDING)) edit.remove(KEY_PENDING)
        edit.commit()
    }

    private fun toJson(e: QueuedEvent): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("pkg", e.pkg)
        put("app", e.app)
        put("reason", e.reason)
        put("state", e.state)
        put("position_ms", e.positionMs)
        put("duration_ms", e.durationMs)
        put("at", e.at)
        put("play_id", e.playId)
        put("estimated", e.estimated)
        put("fields", JSONObject(e.fields as Map<*, *>))
    }

    private fun fromJson(o: JSONObject): QueuedEvent {
        val at = o.optLong("at")
        val playId = o.optString("play_id")
        val existingId = o.optString("id").takeIf { it.isNotBlank() }
            ?: "$at-legacy-${o.optString("pkg")}-${o.optString("reason")}-${o.optLong("position_ms")}"
        val fieldsObj = o.optJSONObject("fields")
        val fields = linkedMapOf<String, String>()
        if (fieldsObj != null) {
            val keys = fieldsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                fields[k] = fieldsObj.optString(k)
            }
        }
        return QueuedEvent(
            id = existingId,
            pkg = o.optString("pkg"),
            app = o.optString("app"),
            reason = o.optString("reason"),
            state = o.optString("state"),
            positionMs = o.optLong("position_ms"),
            durationMs = o.optLong("duration_ms", -1L),
            at = at,
            playId = playId,
            estimated = o.optBoolean("estimated", false),
            fields = fields,
        )
    }

    private fun logLocked(e: QueuedEvent, result: String) {
        val title = listOf("display_title", "title", "display_subtitle", "artist")
            .mapNotNull { k -> e.fields[k]?.takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString(" · ")
            .ifBlank {
                listOf("prime_gti", "netflix_id", "media_id")
                    .mapNotNull { k -> e.fields[k]?.takeIf { it.isNotBlank() }?.let { "$k:$it" } }
                    .joinToString(" · ")
            }
        val entry = JSONObject().apply {
            put("id", e.id)
            put("at", e.at)
            put("app", e.app)
            put("title", title)
            put("position_ms", e.positionMs)
            put("reason", e.reason)
            put("result", result)
        }
        val arr = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrElse { JSONArray() }
        val out = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), MAX_LOG - 1)) out.put(arr.get(i))
        prefs.edit().putString(KEY_LOG, out.toString()).apply()
    }

    private fun markLogDeliveredLocked(ids: Set<String>) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrElse { return }
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") in ids && o.optString("result") == "în coadă locală") {
                o.put("result", "preluat de AK VOD Unify")
                changed = true
            }
        }
        if (changed) prefs.edit().putString(KEY_LOG, arr.toString()).apply()
    }

    companion object {
        private const val KEY_PENDING = "pending"
        private const val KEY_QUEUE_V2 = "queue_v2"
        private const val KEY_LOG = "log"
        private const val MAX_PENDING = 200
        private const val MAX_LOG = 40

        @Volatile private var instance: Forwarder? = null

        fun get(context: Context): Forwarder =
            instance ?: synchronized(this) {
                instance ?: Forwarder(context.applicationContext).also { instance = it }
            }
    }
}
