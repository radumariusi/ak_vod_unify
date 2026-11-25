package ro.ak.vodscrobbler

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** O intrare din jurnalul „Trimise recent”. */
data class SentEntry(val at: Long, val appLabel: String, val title: String, val positionMs: Long, val reason: String, val result: String)

/**
 * Trimite evenimentele către AK VOD Unify prin ContentProvider-ul lui (protejat de o permisiune
 * „signature”, deci doar aplicațiile noastre pot scrie). Dacă aplicația principală lipsește sau
 * nu răspunde, evenimentul intră într-o coadă salvată și se retrimite la următorul eveniment.
 *
 * Totul rulează pe un singur fir de fundal, în ordine.
 */
class Forwarder private constructor(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("forwarder", Context.MODE_PRIVATE)
    private val uri: Uri = Uri.parse("content://${BuildConfig.MAIN_APP_ID}.scrobble/events")

    fun send(o: Outgoing) {
        val wallClock = System.currentTimeMillis()
        val payload = JSONObject().apply {
            put("pkg", o.packageName)
            put("app", o.appLabel)
            put("reason", o.reason.wire)
            put("state", o.state.name.lowercase())
            put("position_ms", o.positionMs)
            put("duration_ms", o.media.durationMs ?: -1L)
            put("at", wallClock)
            put("play_id", o.playId)
            put("estimated", o.estimated)
            put("fields", JSONObject(o.media.fields as Map<*, *>))
        }
        executor.execute {
            flushPendingNow()
            val result = deliver(payload)
            if (result == null) enqueue(payload)
            log(payload, result ?: "în așteptare (AK VOD Unify n-a răspuns)")
            DiagLog.add("${payload.optString("app")}: răspuns AK VOD Unify → ${result ?: "niciun răspuns, rămâne în coadă (${pendingCount()})"}")
        }
    }

    fun flushPending() {
        executor.execute { flushPendingNow() }
    }

    fun pendingCount(): Int = runCatching { JSONArray(prefs.getString(KEY_PENDING, "[]")).length() }.getOrDefault(0)

    fun recent(): List<SentEntry> = runCatching {
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

    /** Întoarce rezultatul de la aplicația principală, sau null dacă trebuie reîncercat. */
    private fun deliver(payload: JSONObject): String? = try {
        val values = ContentValues().apply { put("payload", payload.toString()) }
        context.contentResolver.insert(uri, values)?.lastPathSegment
    } catch (e: Exception) {
        // IllegalArgumentException = aplicația principală nu e instalată; SecurityException = altă semnătură.
        Log.w(TAG, "Nu pot trimite către AK VOD Unify: ${e.message}")
        DiagLog.add("EROARE la trimitere către AK VOD Unify: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun flushPendingNow() {
        val arr = runCatching { JSONArray(prefs.getString(KEY_PENDING, "[]")) }.getOrElse { JSONArray() }
        if (arr.length() == 0) return
        val left = JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            if (left.length() > 0) {
                left.put(p)
                continue
            }
            val r = deliver(p)
            if (r == null) left.put(p) else log(p, "$r (din coadă)")
        }
        prefs.edit().putString(KEY_PENDING, left.toString()).apply()
    }

    private fun enqueue(payload: JSONObject) {
        val arr = runCatching { JSONArray(prefs.getString(KEY_PENDING, "[]")) }.getOrElse { JSONArray() }
        arr.put(payload)
        val trimmed = JSONArray()
        val start = (arr.length() - MAX_PENDING).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_PENDING, trimmed.toString()).apply()
    }

    private fun log(payload: JSONObject, result: String) {
        val fields = payload.optJSONObject("fields")
        val title = listOf("display_title", "title", "display_subtitle", "artist")
            .mapNotNull { k -> fields?.optString(k)?.takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString(" · ")
            .ifBlank {
                listOf("prime_gti", "netflix_id", "media_id")
                    .mapNotNull { k -> fields?.optString(k)?.takeIf { it.isNotBlank() }?.let { "$k:$it" } }
                    .joinToString(" · ")
            }
        val entry = JSONObject().apply {
            put("at", payload.optLong("at"))
            put("app", payload.optString("app"))
            put("title", title)
            put("position_ms", payload.optLong("position_ms"))
            put("reason", payload.optString("reason"))
            put("result", result)
        }
        val arr = runCatching { JSONArray(prefs.getString(KEY_LOG, "[]")) }.getOrElse { JSONArray() }
        val out = JSONArray().put(entry)
        for (i in 0 until minOf(arr.length(), MAX_LOG - 1)) out.put(arr.get(i))
        prefs.edit().putString(KEY_LOG, out.toString()).apply()
    }

    companion object {
        private const val TAG = "AKScrobbler"
        private const val KEY_PENDING = "pending"
        private const val KEY_LOG = "log"
        private const val MAX_PENDING = 100
        private const val MAX_LOG = 40

        @Volatile private var instance: Forwarder? = null

        fun get(context: Context): Forwarder =
            instance ?: synchronized(this) {
                instance ?: Forwarder(context.applicationContext).also { instance = it }
            }
    }
}
