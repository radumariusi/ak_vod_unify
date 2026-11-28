package ro.ak.vodscrobbler

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Process
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import ro.ak.vodimport.NetflixRecent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Expune către AK VOD Unify (protejat prin permisiunea `signature` `${mainAppId}.permission.SCROBBLE`
 * și verificarea semnăturii apelantului):
 *  - `GET /queue?limit=N` -> lot de evenimente din coada locală (JSON)
 *  - `INSERT /ack` -> confirmă și șterge din coadă ID-urile deja salvate durabil de Unify
 *  - `GET /netflix_recent?trigger=1` -> activitatea recentă Netflix dacă sesiunea e în Scrobbler
 *  - `GET /report` -> jurnalul de diagnostic complet
 *  - `GET /arm_reader?pkg=com.apple.atve…` -> pornește modul cititor de ecran înainte să se deschidă Apple TV
 */
class ScrobbleDiagProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun enforceTrustedCaller() {
        val ctx = context ?: throw SecurityException("Missing context")
        val callingUid = Binder.getCallingUid()
        if (callingUid == Process.myUid()) return
        val pm = ctx.packageManager
        val sigMatch = pm.checkSignatures(Process.myUid(), callingUid) == PackageManager.SIGNATURE_MATCH
        if (!sigMatch) {
            throw SecurityException("Untrusted caller uid=$callingUid")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        enforceTrustedCaller()
        val ctx = context ?: return null

        return when (uri.lastPathSegment) {
            "queue" -> {
                val limit = uri.getQueryParameter("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 25
                val arr = Forwarder.get(ctx).peekBatch(limit)
                MatrixCursor(arrayOf("events_json")).apply {
                    addRow(arrayOf(arr.toString()))
                }
            }
            "netflix_recent" -> {
                val trigger = uri.getQueryParameter("trigger") == "1"
                val json = if (!NetflixRecent.hasSession()) {
                    NetflixRecent.result("not_logged_in", "Conectează-te la Netflix și alege profilul folosit.").toString()
                } else {
                    NetflixLocalCache.readIfFresh(ctx, maxAgeMs = 60_000L)
                        ?: if (trigger) {
                            runCatching {
                                runBlocking {
                                    withTimeoutOrNull(42_000L) { NetflixRecent.scan(ctx) }
                                }
                            }.getOrNull()?.also { NetflixLocalCache.save(ctx, it) }?.toString()
                                ?: NetflixRecent.result("error", "Timeout WebView Netflix (42s) în AK Scrobbler").toString()
                        } else {
                            null
                        }
                }
                MatrixCursor(arrayOf("netflix_json")).apply {
                    if (json != null) addRow(arrayOf(json))
                }
            }
            "arm_reader" -> {
                // Unify pornește Apple TV: modul cititor de ecran trebuie să fie deja activ.
                val pkg = uri.getQueryParameter("pkg")?.takeIf { it.startsWith("com.apple.atve.") }
                if (pkg != null) {
                    ScreenState.armReader(pkg, 15_000L)
                    ScreenState.onReaderArm?.invoke()
                }
                MatrixCursor(arrayOf("ok")).apply { addRow(arrayOf(if (pkg != null && ScreenState.serviceConnected) "1" else "0")) }
            }
            "readiness" -> {
                // Ce vede Unify în Setări → AK Scrobbler: dacă totul e pregătit, fără jurnale.
                val engines = ScreenState.ttsEngines
                MatrixCursor(arrayOf("listener", "accessibility", "connected", "tts_engines", "spoken_allowed", "reader_mode")).apply {
                    addRow(arrayOf(
                        if (Access.isListenerEnabled(ctx)) "1" else "0",
                        if (Access.isScreenReaderEnabled(ctx)) "1" else "0",
                        if (ScreenState.serviceConnected) "1" else "0",
                        engines?.joinToString(",") ?: "?",
                        if (ScreenState.spokenModeAllowed) "1" else "0",
                        ScreenState.readerModeLabel,
                    ))
                }
            }
            else -> buildReportCursor(ctx)
        }
    }

    private fun buildReportCursor(ctx: android.content.Context): Cursor {
        DiagLog.init(ctx)
        val fmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
        val pkgInfo = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val version = pkgInfo?.versionName ?: "?"

        val report = buildString {
            appendLine("=== AK Scrobbler ($version · ${ctx.packageName}) ===")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})")
            appendLine("NotificationListener: ${Access.isListenerEnabled(ctx)}")
            appendLine("AccessibilityService (ScreenReader): ${Access.isScreenReaderEnabled(ctx)} (connected=${ScreenState.serviceConnected})")
            appendLine("Overlay (bulină): ${Access.canDrawOverlays(ctx)}")
            appendLine("Battery whitelist: ${Access.isIgnoringBatteryOptimizations(ctx)}")
            appendLine("Foreground pkg: ${ScreenState.foregroundPackage ?: "-"}")
            appendLine("Mod cititor: ${ScreenState.readerModeLabel} · vorbitor permis=${ScreenState.spokenModeAllowed} · motoare voce=${ScreenState.ttsEngines?.joinToString()?.ifBlank { "niciunul" } ?: "?"}")
            appendLine("SkipScreen pkgs: ${ScreenState.skipScreenForPackages.joinToString().ifBlank { "-" }}")
            appendLine("Pending queue: ${Forwarder.get(ctx).pendingCount()}")
            appendLine()
            appendLine("--- Sesiuni active acum ---")
            val live = ScrobblerState.live
            if (live.isEmpty()) {
                appendLine("(niciuna)")
            } else {
                live.forEach { s ->
                    val title = s.media?.fields?.get("title") ?: s.media?.fields?.get("display_title") ?: "-"
                    val pos = s.positionMs?.let(StatusActivity::clock) ?: "-"
                    appendLine("${s.appLabel} (${s.packageName}) · ${s.state} · $title · pos=$pos")
                }
            }
            appendLine()
            appendLine("--- Trimise recent către AK VOD Unify ---")
            val sent = Forwarder.get(ctx).recent()
            if (sent.isEmpty()) {
                appendLine("(niciuna)")
            } else {
                sent.take(30).forEach { e ->
                    appendLine("${fmt.format(Date(e.at))} · ${e.appLabel} · ${e.title} · ${StatusActivity.clock(e.positionMs)} · ${e.reason} -> ${e.result}")
                }
            }
            appendLine()
            appendLine("--- DiagLog AK Scrobbler ---")
            appendLine(DiagLog.asText().ifBlank { "(gol)" })
        }

        return MatrixCursor(arrayOf("report")).apply {
            addRow(arrayOf(report))
        }
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceTrustedCaller()
        val ctx = context ?: return null
        if (uri.lastPathSegment == "ack") {
            val raw = values?.getAsString("ids") ?: return null
            val ids = runCatching {
                val arr = JSONArray(raw)
                buildSet {
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.getOrNull() ?: return null
            val removed = Forwarder.get(ctx).ackIds(ids)
            return uri.buildUpon().appendPath(removed.toString()).build()
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceTrustedCaller()
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceTrustedCaller()
        return 0
    }
}
