package ro.ak.vodscrobbler

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pentru aplicațiile care nu spun nimic despre ce redau (Netflix): citim jurnalul sistemului
 * (logcat) și căutăm codul titlului. Merge doar dacă AK Scrobbler are permisiunea READ_LOGS,
 * dată o singură dată prin ADB. Nu păstrăm jurnalul, doar codul găsit și câteva linii pentru
 * diagnostic.
 */
object LogcatProbe {
    private val executor = Executors.newSingleThreadExecutor()

    fun isAllowed(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun adbCommand(context: Context) = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"

    /** Ce am găsit: cod + liniile relevante (pentru jurnalul de diagnostic). */
    data class Result(val ids: Map<String, String>, val lines: List<String>)

    /** Citește ultimele linii din logcat pe un fir de fundal; [done] e chemat pe același fir. */
    fun probe(context: Context, packageName: String, done: (Result) -> Unit) {
        if (!isAllowed(context)) return
        executor.execute {
            val relevant = runCatching { readRelevant() }
                .getOrElse { listOf("(nu pot citi logcat: ${it.message})") }
            done(Result(LogcatIds.extract(packageName, relevant), relevant.takeLast(MAX_DIAG_LINES)))
        }
    }

    private fun readRelevant(): List<String> {
        val p = ProcessBuilder("logcat", "-d", "-v", "brief", "-t", "8000").redirectErrorStream(true).start()
        val matched = ArrayDeque<String>(256)
        BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
            for (line in lines) {
                if (RELEVANT.containsMatchIn(line)) {
                    if (matched.size >= MAX_MATCHED_LINES) matched.removeFirst()
                    matched.addLast(line)
                }
            }
        }
        p.waitFor(5, TimeUnit.SECONDS)
        p.destroy()
        return matched.toList()
    }

    private val RELEVANT = Regex(
        """(?i)(netflix|nf_|movieid|videoid|video_id|movie_id|viewable|episode|title|amzn1\.dv|gti|/watch/|playback|manifest|catalog|content_?id)""",
    )
    private const val MAX_MATCHED_LINES = 250
    private const val MAX_DIAG_LINES = 40
}
