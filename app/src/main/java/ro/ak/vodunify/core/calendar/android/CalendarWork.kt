package ro.ak.vodunify.core.calendar.android

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.NotificationMode
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.time.Duration
import java.util.concurrent.TimeUnit

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CalendarEntryPoint {
    fun calendar(): CalendarRepository
    fun tracking(): TrackingRepository
}

private fun Context.calendarEntry(): CalendarEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, CalendarEntryPoint::class.java)

/**
 * Lucrul în fundal al Calendarului:
 * - la 3 ore: sincronizarea trackerelor (dacă e veche) și canalul „Azi apare” de pe TV;
 * - zilnic, la ora din Setări (ora României): notificarea cu ce apare azi.
 */
class CalendarWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entry = applicationContext.calendarEntry()
        val notify = inputData.getBoolean(KEY_NOTIFY, false)
        return try {
            runCatching { entry.tracking().syncIfStale(TimeUnit.HOURS.toMillis(2)) }
            val calendar = entry.calendar()
            val settings = calendar.settings.first()
            val digest = calendar.today()
            TodayChannel.update(applicationContext, digest, settings.tvChannel)
            if (notify && digest != null && settings.notifications.mode != NotificationMode.OFF) {
                val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val today = digest.date.toString()
                // O dată pe zi, chiar dacă lucrul rulează de două ori (reîncercare, oră schimbată).
                if (prefs.getString(KEY_LAST_NOTIFIED, null) != today) {
                    TodayNotifier.post(applicationContext, digest, settings.notifications.mode)
                    prefs.edit().putString(KEY_LAST_NOTIFIED, today).apply()
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("CalendarWorker", "failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_NOTIFY = "notify"
        private const val PREFS = "calendar_work"
        private const val KEY_LAST_NOTIFIED = "last_notified"
        private const val KEY_SCHEDULED_HOUR = "scheduled_hour"
        private const val WORK_REFRESH = "calendar-refresh"
        private const val WORK_REFRESH_NOW = "calendar-refresh-now"
        private const val WORK_DAILY = "calendar-daily"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context, settings: CalendarSettings) {
            val work = WorkManager.getInstance(context)
            work.enqueueUniquePeriodicWork(
                WORK_REFRESH,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CalendarWorker>(3, TimeUnit.HOURS).setConstraints(network).build(),
            )
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (settings.notifications.mode == NotificationMode.OFF) {
                work.cancelUniqueWork(WORK_DAILY)
                prefs.edit().remove(KEY_SCHEDULED_HOUR).apply()
                return
            }
            val hour = settings.notifications.hour
            val changed = prefs.getInt(KEY_SCHEDULED_HOUR, -1) != hour
            val request = PeriodicWorkRequestBuilder<CalendarWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayUntil(hour).toMillis(), TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_NOTIFY to true))
                .setConstraints(network)
                .build()
            work.enqueueUniquePeriodicWork(
                WORK_DAILY,
                if (changed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            prefs.edit().putInt(KEY_SCHEDULED_HOUR, hour).apply()
        }

        /** Reface acum canalul TV; cu [notify], trimite și notificarea (butonul de test din Setări). */
        fun refreshNow(context: Context, notify: Boolean = false) {
            if (notify) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_LAST_NOTIFIED).apply()
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_REFRESH_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CalendarWorker>()
                    .setInputData(workDataOf(KEY_NOTIFY to notify))
                    .setConstraints(network)
                    .build(),
            )
        }

        internal fun delayUntil(hour: Int): Duration {
            val now = RomaniaTime.now()
            var next = now.toLocalDate().atTime(hour, 0).atZone(RomaniaTime.ZONE)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}

/** Pornit din Application: programează lucrul în fundal după setările Calendarului. */
object CalendarWork {
    /** Extra pe intentul de deschidere (notificare, canal TV): deschide direct tabul Calendar. */
    const val EXTRA_OPEN_CALENDAR = "ro.ak.vodunify.OPEN_CALENDAR"

    fun start(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { TodayNotifier.createChannel(app) }
            var first = true
            val calendar = app.calendarEntry().calendar()
            calendar.settings
                .map { Triple(it.notifications, it.tvChannel, it.trackedShows.size + it.trackedMovies.size) }
                .distinctUntilChanged()
                .collect {
                    val settings = calendar.settings.first()
                    runCatching { CalendarWorker.schedule(app, settings) }
                    // Prima valoare doar programează; o schimbare reface imediat canalul.
                    if (!first) runCatching { CalendarWorker.refreshNow(app) }
                    first = false
                }
        }
    }
}
