package ro.ak.vodunify.core.diagnostics

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jurnal persistent de diagnostic pentru AK VOD Unify (erori, scrobble-uri, excepții neprinse).
 * Scrie asincron pe un singur fir de fundal, fără a bloca interfața TV.
 */
object AppDiagLog {
    private const val PREFS = "app_diag_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 500
    private const val SAVE_DELAY_MS = 400L

    private val lock = Any()
    private var lines: ArrayDeque<String>? = null
    private var appContext: Context? = null
    private val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)

    private val ioThread by lazy { HandlerThread("AppDiagLogIO").apply { start() } }
    private val ioHandler by lazy { Handler(ioThread.looper) }
    private val saveTask = Runnable { flushNow() }

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext == null) appContext = context.applicationContext
            if (lines == null) {
                ioHandler.post { synchronized(lock) { ensureLoaded() } }
            }
        }
    }

    fun installCrashHandler(context: Context) {
        init(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            add("CRASH (${thread.name}): ${throwable.javaClass.simpleName}: ${throwable.message}\n${throwable.stackTraceToString().take(1200)}")
            flushNow()
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun add(message: String) {
        val stamp = synchronized(fmt) { fmt.format(Date()) }
        synchronized(lock) {
            val buf = ensureLoaded()
            message.lines().filter { it.isNotBlank() }.forEachIndexed { i, l ->
                buf.addLast(if (i == 0) "$stamp  $l" else "                $l")
            }
            while (buf.size > MAX_LINES) buf.removeFirst()
            scheduleSave()
        }
    }

    fun asText(): String = synchronized(lock) { ensureLoaded().joinToString("\n") }

    fun clear() {
        synchronized(lock) {
            ensureLoaded().clear()
            ioHandler.removeCallbacks(saveTask)
            ioHandler.post { flushNow() }
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

    private fun scheduleSave() {
        if (appContext == null) return
        ioHandler.removeCallbacks(saveTask)
        ioHandler.postDelayed(saveTask, SAVE_DELAY_MS)
    }

    private fun flushNow() {
        val ctx: Context
        val snapshot: List<String>
        synchronized(lock) {
            ctx = appContext ?: return
            snapshot = ensureLoaded().toList()
        }
        val arr = JSONArray()
        snapshot.forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
