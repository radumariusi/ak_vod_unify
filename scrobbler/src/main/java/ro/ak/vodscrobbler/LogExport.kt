package ro.ak.vodscrobbler

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Salvează / trimite jurnalul de diagnostic, ca să poată fi citit după un test. */
object LogExport {
    const val FILE_NAME = "ak-scrobbler-jurnal.txt"

    fun fullText(context: Context): String = buildString {
        append("AK Scrobbler ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
        append("Salvat: ${SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(Date())}\n")
        append("Acces sesiuni media: ${Access.isListenerEnabled(context)} · citire ecran: ${Access.isScreenReaderEnabled(context)}")
        append(" (conectat: ${ScreenState.serviceConnected}) · în coadă: ${Forwarder.get(context).pendingCount()}\n\n")
        append(DiagLog.asText())
    }

    /** Întoarce unde a fost salvat fișierul (text pentru utilizator). */
    fun saveToDownloads(context: Context): String {
        val bytes = fullText(context).toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            // Ștergem jurnalele salvate anterior de noi (cele ale altor instalări nu le putem șterge),
            // apoi salvăm cu un nume nou, fără spații sau paranteze, ușor de citit din ADB.
            runCatching {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                    arrayOf("ak-scrobbler-jurnal%"),
                )
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "ak-scrobbler-jurnal-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android nu a creat fișierul")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Nu pot scrie fișierul")
            val name = runCatching {
                resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: FILE_NAME
            return "/sdcard/Download/$name"
        }
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, FILE_NAME)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    fun shareIntent(context: Context): Intent {
        val text = fullText(context).let { if (it.length > MAX_SHARE) it.takeLast(MAX_SHARE) else it }
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Jurnal AK Scrobbler")
                .putExtra(Intent.EXTRA_TEXT, text),
            "Trimite jurnalul",
        )
    }

    private const val MAX_SHARE = 90_000
}
