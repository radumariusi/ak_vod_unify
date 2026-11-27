package ro.ak.vodunify.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Base64
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
 * Colectează jurnalele AK VOD Unify + AK Scrobbler și le încarcă pe GitHub
 * (sau le salvează local într-un fișier text).
 *
 * Destinația principală e un repo PRIVAT fix ([LOG_REPO]): `latest.txt` se suprascrie la fiecare
 * încărcare, iar o copie datată rămâne în `history/`. Așa Claude poate citi mereu ultimul jurnal
 * direct (repos/{owner}/{repo}/contents/latest.txt), fără să mai fie trimis vreun link.
 * Dacă repo-ul nu e accesibil cu token-ul dat, cade înapoi pe Gist secret (comportamentul vechi).
 */
object GitHubLogUploader {
    private const val PREFS = "github_diag"
    private const val KEY_TOKEN = "gist_token"
    const val LOG_REPO = "radumariusi/ak_vod_logs"
    private const val API = "https://api.github.com"
    private val JSON = "application/json; charset=utf-8".toMediaType()

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

    /** Rezultatul încărcării: link-ul de deschis și unde a ajuns jurnalul. */
    data class UploadResult(val url: String, val toRepo: Boolean, val note: String? = null)

    /** Încarcă în repo-ul fix de jurnale; dacă nu se poate, încearcă Gist. */
    suspend fun upload(okHttp: OkHttpClient, token: String, report: String): Result<UploadResult> = withContext(Dispatchers.IO) {
        val repo = uploadToRepo(okHttp, token, report)
        if (repo.isSuccess) return@withContext Result.success(UploadResult(repo.getOrThrow(), toRepo = true))
        val repoError = repo.exceptionOrNull()?.message ?: "eroare necunoscută"
        val gist = uploadGist(okHttp, token, report)
        if (gist.isSuccess) {
            Result.success(
                UploadResult(
                    gist.getOrThrow(),
                    toRepo = false,
                    note = "Repo-ul $LOG_REPO nu e accesibil ($repoError). L-am pus pe Gist; trimite link-ul sau setează un token cu acces la repo.",
                ),
            )
        } else {
            Result.failure(IllegalStateException("Repo $LOG_REPO: $repoError · Gist: ${gist.exceptionOrNull()?.message}"))
        }
    }

    /** Scrie `latest.txt` (suprascris) + `history/<stamp>.txt` în [LOG_REPO]. Întoarce link-ul către latest.txt. */
    suspend fun uploadToRepo(okHttp: OkHttpClient, token: String, report: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanToken = token.trim()
            require(cleanToken.isNotEmpty()) { "Lipsește token-ul GitHub." }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.ROOT).format(Date())
            val content = Base64.encodeToString(report.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val message = "Jurnal ${BuildConfig.VERSION_NAME} · $stamp"

            // 1. latest.txt: are nevoie de sha-ul curent ca să-l suprascriem.
            val sha: String? = when (val found = fileSha(okHttp, cleanToken, "latest.txt")) {
                REPO_MISSING -> {
                    createRepo(okHttp, cleanToken)
                    fileSha(okHttp, cleanToken, "latest.txt") as? String
                }
                else -> found as? String
            }
            putFile(okHttp, cleanToken, "latest.txt", content, message, sha)
            // 2. copia datată (fișier nou, fără sha). Un eșec aici nu strică latest.txt.
            runCatching { putFile(okHttp, cleanToken, "history/$stamp.txt", content, message, null) }
            "https://github.com/$LOG_REPO/blob/main/latest.txt"
        }
    }

    private val REPO_MISSING = Any()

    /** sha-ul fișierului, null dacă fișierul nu există, [REPO_MISSING] dacă repo-ul nu există / nu e vizibil. */
    private fun fileSha(okHttp: OkHttpClient, token: String, path: String): Any? {
        val req = authed(token, "$API/repos/$LOG_REPO/contents/$path").get().build()
        okHttp.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            return when {
                resp.isSuccessful -> JSONObject(raw).optString("sha").ifBlank { null }
                resp.code == 404 && raw.contains("This repository is empty", ignoreCase = true) -> null
                resp.code == 404 -> if (repoExists(okHttp, token)) null else REPO_MISSING
                else -> error("GitHub HTTP ${resp.code}: ${raw.take(160)}")
            }
        }
    }

    private fun repoExists(okHttp: OkHttpClient, token: String): Boolean {
        val req = authed(token, "$API/repos/$LOG_REPO").get().build()
        return okHttp.newCall(req).execute().use { it.isSuccessful }
    }

    /** Încearcă să creeze repo-ul privat (merge doar cu token-uri care pot crea repo-uri). */
    private fun createRepo(okHttp: OkHttpClient, token: String) {
        val body = JSONObject().apply {
            put("name", LOG_REPO.substringAfter('/'))
            put("private", true)
            put("description", "Jurnale de diagnostic AK VOD Unify (privat)")
            put("auto_init", true)
        }
        val req = authed(token, "$API/user/repos").post(body.toString().toRequestBody(JSON)).build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("repo-ul nu există și nu l-am putut crea (HTTP ${resp.code}). Creează-l privat pe github.com/new")
            }
        }
    }

    private fun putFile(okHttp: OkHttpClient, token: String, path: String, b64: String, message: String, sha: String?) {
        val body = JSONObject().apply {
            put("message", message)
            put("content", b64)
            if (sha != null) put("sha", sha)
        }
        val req = authed(token, "$API/repos/$LOG_REPO/contents/$path").put(body.toString().toRequestBody(JSON)).build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(160)}")
        }
    }

    private fun authed(token: String, url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")

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
