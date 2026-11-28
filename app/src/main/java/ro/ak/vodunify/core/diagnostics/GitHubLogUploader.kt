package ro.ak.vodunify.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.publish.RouteItem
import ro.ak.vodunify.core.scrobble.ScrobbleLogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Colectează jurnalele AK VOD Unify + AK Scrobbler și le încarcă într-un GitHub Gist privat
 * (sau le salvează local într-un fișier text).
 */
object GitHubLogUploader {
    private const val PREFS = "github_diag"
    private const val KEY_TOKEN = "gist_token"

    fun getToken(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TOKEN, "").orEmpty()

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    suspend fun collectFullReport(
        context: Context,
        scrobbleLog: List<ScrobbleLogEntry>,
        routeItems: List<RouteItem>,
        netflixStatus: List<String> = emptyList(),
        unresolvedCount: Int = 0,
        linkReport: List<String> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        AppDiagLog.init(context)
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
        val scrobblerReport = queryScrobblerReport(context)

        buildString {
            appendLine("=== AK VOD Unify (${BuildConfig.VERSION_NAME} · ${BuildConfig.APPLICATION_ID}) ===")
            appendLine("Generat: ${fmt.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("Unresolved count: $unresolvedCount")
            if (netflixStatus.isNotEmpty()) {
                appendLine("Netflix status: ${netflixStatus.joinToString(" | ")}")
            }
            appendLine()
            appendLine("--- AK VOD Unify: Jurnal aplicație ---")
            appendLine(AppDiagLog.asText().ifBlank { "(gol)" })
            appendLine()
            appendLine("--- AK VOD Unify: Ultimele evenimente primite de la Scrobbler (${scrobbleLog.size}) ---")
            if (scrobbleLog.isEmpty()) {
                appendLine("(niciuna)")
            } else {
                scrobbleLog.take(40).forEach { e ->
                    appendLine("${fmt.format(Date(e.at))} · ${e.app} · ${e.matched ?: e.raw} · ${e.positionMs / 1000}s · ${e.reason} -> ${e.result}")
                }
            }
            appendLine()
            if (linkReport.isNotEmpty()) {
                appendLine("--- AK VOD Unify: Ce deschide Unify în fiecare aplicație ---")
                linkReport.forEach { appendLine(it) }
                appendLine()
            }
            appendLine("--- AK VOD Unify: Rute & progres (${routeItems.size}) ---")
            routeItems.take(25).forEach { item ->
                val pos = item.positions.joinToString { "${it.label}:${it.progress ?: "-"}" }
                val dest = item.destinations.joinToString { "${it.label}:${it.status?.label ?: "-"}" }
                appendLine("${item.itemKey} (${item.ref}) · [$pos] · [$dest]")
            }
            appendLine()
            appendLine(scrobblerReport)
        }
    }

    private fun queryScrobblerReport(context: Context): String {
        val authorities = listOf("ro.ak.vodscrobbler.diag", "ro.ak.vodscrobbler.debug.diag")
        for (auth in authorities) {
            val uri = Uri.parse("content://$auth/report")
            val client = runCatching { context.contentResolver.acquireUnstableContentProviderClient(uri) }.getOrNull() ?: continue
            val text = try {
                client.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    runCatching { client.close() }
                } else {
                    @Suppress("DEPRECATION")
                    runCatching { client.release() }
                }
            }
            if (!text.isNullOrBlank()) return text
        }
        return "=== AK Scrobbler ===\n(nu este instalat sau nu a răspuns la interogarea de diagnostic)"
    }

    suspend fun uploadGist(okHttp: OkHttpClient, token: String, report: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotEmpty()) { "Lipsește token-ul GitHub (scope: gist)." }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
            val bodyJson = JSONObject().apply {
                put("description", "AK VOD Unify + Scrobbler diagnostic ($stamp)")
                put("public", false)
                put("files", JSONObject().apply {
                    put("ak-vod-diagnostic.txt", JSONObject().apply {
                        put("content", report)
                    })
                })
            }
            val req = Request.Builder()
                .url("https://api.github.com/gists")
                .header("Authorization", "Bearer $cleanToken")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            okHttp.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("GitHub HTTP ${resp.code}: ${raw.take(180)}")
                }
                JSONObject(raw).getString("html_url")
            }
        }
    }

    suspend fun saveLocal(context: Context, report: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val file = File(dir, "ak-vod-diagnostic-$stamp.txt")
            file.writeText(report)
            file.absolutePath
        }
    }
}
