package ro.ak.vodscrobbler

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Expune jurnalul și starea AK Scrobbler către AK VOD Unify (protejat prin permisiunea „signature”
 * `${mainAppId}.permission.SCROBBLE`), ca Unify să poată salva/încărca ambele jurnale împreună.
 */
class ScrobbleDiagProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
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
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
