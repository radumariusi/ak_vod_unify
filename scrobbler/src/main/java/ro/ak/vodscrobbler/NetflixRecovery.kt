package ro.ak.vodscrobbler

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ro.ak.vodimport.NetflixRecent
import ro.ak.vodimport.WebLoginActivity

class NetflixLoginActivity : WebLoginActivity() {
    override val recentOnly = true

    override fun onStart() {
        super.onStart()
        sizeScrobblerWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeScrobblerWindow()
    }

    override fun saveHistory(service: String, payload: JSONObject): String {
        val items = payload.getJSONArray("items")
        val recent = JSONArray()
        for (i in 0 until minOf(items.length(), 200)) recent.put(items.get(i))
        return if (sendActivity(applicationContext, NetflixRecent.result("ok", items = recent))) {
            "Sesiunea este activă. Activitatea recentă a fost trimisă către AK VOD Unify."
        } else {
            "AK VOD Unify nu a acceptat activitatea. Verifică dacă ai instalat ambele APK-uri din același release."
        }
    }
}

class NetflixRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NetflixRecent.ACTION) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "netflix_recovery", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<NetflixRecoveryWorker>().build(),
        )
    }
}

class NetflixRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val activity = NetflixRecent.scan(applicationContext)
        val sent = withContext(Dispatchers.IO) { sendActivity(applicationContext, activity) }
        return if (sent) Result.success() else if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

private fun sendActivity(context: Context, result: JSONObject): Boolean {
    val uri = Uri.parse("content://${BuildConfig.MAIN_APP_ID}.scrobble/netflix_recent")
    return runCatching {
        context.contentResolver.insert(uri, ContentValues().apply { put("payload", result.toString()) }) != null
    }.getOrDefault(false)
}
