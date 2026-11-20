package ro.ak.vodunify.core.scrobble

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.update.CompanionApp
import java.util.concurrent.TimeUnit

/**
 * Cere aplicațiilor AK Import (RO și US) „ce ai văzut recent pe Netflix”. Răspunsul vine prin
 * [ScrobbleProvider] (`/netflix_recent`). Rulează la un minut după o pauză / oprire Netflix
 * (Netflix își salvează minutul în cont în câteva secunde) și încă o dată după 10 minute.
 */
class NetflixRecentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val suffix = if (BuildConfig.DEBUG) ".debug" else ""
        for (app in listOf(CompanionApp.IMPORT_RO, CompanionApp.IMPORT_US)) {
            runCatching {
                applicationContext.sendBroadcast(Intent(ACTION).setPackage(app.packageName + suffix))
            }
        }
        return Result.success()
    }

    companion object {
        const val ACTION = "ro.ak.vodimport.action.NETFLIX_RECENT"

        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniqueWork(
                "netflix_recent",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NetflixRecentWorker>().setInitialDelay(1, TimeUnit.MINUTES).build(),
            )
            wm.enqueueUniqueWork(
                "netflix_recent_late",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NetflixRecentWorker>().setInitialDelay(10, TimeUnit.MINUTES).build(),
            )
        }
    }
}
