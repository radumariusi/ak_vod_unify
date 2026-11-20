package ro.ak.vodscrobbler

import android.content.Context
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jurnal de diagnostic: tot ce vede scrobblerul (sesiuni, câmpuri brute, stări, poziții,
 * notificări media) și de ce a trimis sau NU a trimis ceva. Se păstrează și după ce aplicația
 * redată se închide, ca să poată fi citit (sau fotografiat / trimis) după test.
 */
object DiagLog {
    private const val PREFS = "diag"
    private const val KEY = "lines"
    private const val MAX_LINES = 400

    private val lock = Any()
    private var lines: ArrayDeque<String>? = null
    private var appContext: Context? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
            ensureLoaded()
        }
    }

    fun add(message: String) {
        synchronized(lock) {
            val buf = ensureLoaded()
            val stamp = fmt.format(Date())
            message.lines().filter { it.isNotBlank() }.forEachIndexed { i, l ->
                buf.addLast(if (i == 0) "$stamp  $l" else "                $l")
            }
            while (buf.size > MAX_LINES) buf.removeFirst()
            save(buf)
        }
    }

    /** Cele mai noi primele. */
    fun newestFirst(limit: Int = MAX_LINES): List<String> = synchronized(lock) {
        ensureLoaded().toList().asReversed().take(limit)
    }

    fun asText(): String = synchronized(lock) { ensureLoaded().joinToString("\n") }

    fun clear() {
        synchronized(lock) {
            val buf = ensureLoaded()
            buf.clear()
            save(buf)
        }
    }

    private fun ensureLoaded(): ArrayDeque<String> {
        lines?.let { return it }
        val buf = ArrayDeque<String>()
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY, null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) buf.addLast(arr.getString(i))
            }
        }
        lines = buf
        return buf
    }

    private fun save(buf: ArrayDeque<String>) {
        val ctx = appContext ?: return
        val arr = JSONArray()
        buf.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
