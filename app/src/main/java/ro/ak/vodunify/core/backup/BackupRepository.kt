package ro.ak.vodunify.core.backup

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.db.VodDatabase
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup pe Google Drive: baza de date (tot ce ai văzut, în curs, Lista mea, calendarul) și setările,
 * într-o arhivă zip în folderul „AK VOD Unify backup”. Tokenurile (Trakt, SIMKL, Google) nu intră.
 * Restaurarea înlocuiește datele locale și repornește aplicația.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val drive: DriveClient,
    private val db: VodDatabase,
    val auth: GoogleAuth,
) {
    private val prefs = context.getSharedPreferences(GoogleAuth.PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(prefs.getString(KEY_STATUS, null))

    /** Ultimul rezultat („12.11 08:00 · backup salvat (1,2 MB)” sau eroarea). */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    fun setAuto(value: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, value).apply()
        _auto.value = value
        schedule(context, value && auth.connected.value)
    }

    suspend fun list(): List<DriveFile> = drive.backups(drive.folderId())

    /** Face backup acum. [automatic]: din WorkManager (o dată pe zi). */
    suspend fun backupNow(automatic: Boolean = false): DriveFile = mutex.withLock {
        try {
            val zip = withContext(Dispatchers.IO) { createZip() }
            try {
                val folder = drive.folderId()
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date())
                val uploaded = drive.upload(folder, "ak-vod-unify_$stamp.zip", zip)
                // Păstrăm ultimele KEEP copii.
                drive.backups(folder).drop(KEEP).forEach { runCatching { drive.delete(it.id) } }
                note((if (automatic) "backup automat" else "backup salvat") + " (${sizeLabel(zip.length())})")
                uploaded
            } finally {
                zip.delete()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("eroare backup: ${e.message ?: e.javaClass.simpleName}")
            throw e
        }
    }

    /** Descarcă backupul și înlocuiește datele locale; după asta aplicația trebuie repornită ([restart]). */
    suspend fun restore(file: DriveFile) = mutex.withLock {
        val tmp = File(context.cacheDir, "restore.zip")
        try {
            drive.download(file.id, tmp)
            withContext(Dispatchers.IO) { applyZip(tmp) }
            note("restaurat din ${file.name}")
        } finally {
            tmp.delete()
        }
    }

    /** Repornește aplicația (după restaurare baza și setările se citesc din nou de pe disc). */
    fun restart() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: context.packageManager.getLeanbackLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent?.let(context::startActivity)
        Runtime.getRuntime().exit(0)
    }

    fun disconnect() {
        auth.disconnect()
        schedule(context, false)
    }

    fun onConnected() {
        schedule(context, _auto.value)
    }

    // ---------- Arhiva ----------

    private fun dataDir(): File = context.filesDir.parentFile ?: throw IOException("Lipsește dosarul aplicației")

    /** Fișierele din backup, relativ la dosarul aplicației. */
    private fun backupFiles(): List<Pair<String, File>> {
        val base = dataDir()
        val out = mutableListOf<Pair<String, File>>()
        File(base, "databases").listFiles()?.filter { it.name.startsWith(DB_NAME) && !it.name.endsWith("-shm") }
            ?.forEach { out += "databases/${it.name}" to it }
        File(base, "shared_prefs").listFiles()?.filter { it.isFile && it.name !in EXCLUDED_PREFS }
            ?.forEach { out += "shared_prefs/${it.name}" to it }
        File(context.filesDir, "datastore").listFiles()?.filter { it.isFile }
            ?.forEach { out += "files/datastore/${it.name}" to it }
        return out
    }

    private fun createZip(): File {
        // Tot ce e în jurnalul WAL trece în fișierul principal, ca arhiva să fie completă.
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
        val zip = File(context.cacheDir, "backup.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry(MANIFEST))
            out.write("app=${BuildConfig.APPLICATION_ID}\nversion=${BuildConfig.VERSION_NAME}\ncreated=${System.currentTimeMillis()}\n".toByteArray())
            out.closeEntry()
            backupFiles().filterNot { it.first.endsWith("-wal") }.forEach { (name, file) ->
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    private fun applyZip(zip: File) {
        val base = dataDir().canonicalFile
        val staged = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var hasManifest = false
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name == MANIFEST) {
                    hasManifest = true
                    continue
                }
                if (entry.isDirectory || ALLOWED_DIRS.none { entry.name.startsWith(it) }) continue
                val target = File(staged, entry.name).canonicalFile
                if (!target.path.startsWith(staged.canonicalPath + File.separator)) continue // nume periculos
                target.parentFile?.mkdirs()
                target.outputStream().use { input.copyTo(it) }
            }
        }
        if (!hasManifest) throw IOException("Arhiva nu e un backup AK VOD Unify")
        db.close()
        File(base, "databases").listFiles()?.filter { it.name.startsWith(DB_NAME) }?.forEach { it.delete() }
        staged.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(staged).path
            val target = File(base, rel)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
        staged.deleteRecursively()
    }

    private fun note(text: String) {
        val line = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date()) + " · " + text
        prefs.edit().putString(KEY_STATUS, line).apply()
        _status.value = line
    }

    private fun sizeLabel(bytes: Long): String =
        if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0) else "${bytes / 1000} KB"

    companion object {
        const val DB_NAME = "vod.db"
        const val KEEP = 10
        private const val MANIFEST = "ak-vod-unify-backup.txt"
        private const val KEY_STATUS = "backup_status"
        private const val KEY_AUTO = "backup_auto"
        private const val WORK = "drive_backup_daily"
        private val ALLOWED_DIRS = listOf("databases/", "shared_prefs/", "files/datastore/")

        /** Tokenurile și starea backupului nu se suprascriu la restaurare. */
        val EXCLUDED_PREFS = setOf("${GoogleAuth.PREFS_NAME}.xml")

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK)
                return
            }
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackupEntryPoint {
    fun backups(): BackupRepository
}

/** Backup automat, o dată pe zi, când e rețea. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors.fromApplication(applicationContext, BackupEntryPoint::class.java).backups()
        if (!repo.auth.connected.value || !repo.auto.value) return Result.success()
        return try {
            repo.backupNow(automatic = true)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
