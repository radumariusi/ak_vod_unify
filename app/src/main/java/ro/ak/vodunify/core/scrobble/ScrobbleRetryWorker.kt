package ro.ak.vodunify.core.scrobble

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.android.EntryPointAccessors
import java.io.IOException

class ScrobbleRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entry = EntryPointAccessors.fromApplication(applicationContext, ScrobbleProvider.ScrobbleEntryPoint::class.java)
        return try {
            entry.repository().retryUnresolved()
            Result.success()
        } catch (_: IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "scrobble_retry_tmdb", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ScrobbleRetryWorker>().build(),
            )
        }
    }
}
