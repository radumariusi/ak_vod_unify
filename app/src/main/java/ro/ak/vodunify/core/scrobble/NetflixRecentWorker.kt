package ro.ak.vodunify.core.scrobble

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ro.ak.vodimport.NetflixRecent
import ro.ak.vodunify.BuildConfig
import java.util.concurrent.TimeUnit

/** Cere activitatea Netflix după ce serviciul a înregistrat vizionarea. */
class NetflixRecentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Nu mai trimitem broadcast automat către AK Scrobbler (decuplare totală); scanăm doar local dacă există sesiune.
        val result = NetflixRecent.scan(applicationContext)
        NetflixMatchWorker.enqueue(applicationContext, result.toString())
        return Result.success()
    }

    companion object {
        val SCROBBLER_PACKAGE = "ro.ak.vodscrobbler" + if (BuildConfig.DEBUG) ".debug" else ""
        private const val LOCAL_ONLY = "local_only"

        fun scanLocal(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "netflix_recent_unify", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NetflixRecentWorker>().setInputData(workDataOf(LOCAL_ONLY to true)).build(),
            )
        }

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            for (minutes in listOf(1L, 10L)) {
                wm.enqueueUniqueWork(
                    "netflix_recent_$minutes",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<NetflixRecentWorker>()
                        .setInitialDelay(minutes, TimeUnit.MINUTES).build(),
                )
            }
        }

        fun retryNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "netflix_recent_manual", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<NetflixRecentWorker>().build(),
            )
        }
    }
}
