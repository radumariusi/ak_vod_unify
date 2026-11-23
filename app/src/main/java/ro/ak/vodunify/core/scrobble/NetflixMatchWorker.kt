package ro.ak.vodunify.core.scrobble

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** Decuplează citirea activității de potrivirea TMDb, inclusiv la importul istoricului complet. */
class NetflixMatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val name = inputData.getString(FILE)?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
            ?: return@withContext Result.failure()
        val file = File(directory(applicationContext), name)
        if (!file.isFile) return@withContext Result.success()
        val entry = EntryPointAccessors.fromApplication(applicationContext, ScrobbleProvider.ScrobbleEntryPoint::class.java)
        try {
            val activity = entry.json().decodeFromString(NetflixRecentResult.serializer(), file.readText())
            entry.repository().applyNetflixRecent(activity)
            file.delete()
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < 4) Result.retry() else {
                file.delete()
                Result.failure()
            }
        } catch (_: IllegalArgumentException) {
            file.delete()
            Result.failure()
        }
    }

    companion object {
        private const val FILE = "file"

        private fun directory(context: Context) = File(context.filesDir, "netflix_activity")

        fun enqueue(context: Context, payload: String) {
            val dir = directory(context).apply { mkdirs() }
            val name = UUID.randomUUID().toString()
            File(dir, name).writeText(payload)
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<NetflixMatchWorker>().setInputData(workDataOf(FILE to name)).build(),
            )
        }
    }
}
