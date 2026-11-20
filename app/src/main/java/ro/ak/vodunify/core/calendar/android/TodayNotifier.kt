package ro.ak.vodunify.core.calendar.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ro.ak.vodunify.MainActivity
import ro.ak.vodunify.R
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.model.NotificationMode
import ro.ak.vodunify.core.calendar.today.TodayDigest
import ro.ak.vodunify.core.calendar.format.movieKindLabel
import ro.ak.vodunify.core.calendar.format.movieTimeLabel
import ro.ak.vodunify.core.calendar.format.movieWhere
import ro.ak.vodunify.core.calendar.format.timeLabel
import ro.ak.vodunify.core.calendar.format.whereToWatch

/** The morning "Azi apare" notifications. */
object TodayNotifier {
    private const val CHANNEL_ID = "today"
    private const val GROUP = "ro.ak.vodunify.CALENDAR_TODAY"
    private const val SUMMARY_ID = 1
    /** One notification per followed title; more go in the summary. */
    private const val MAX_SINGLE = 8

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Azi apare", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Episoadele și filmele care apar azi"
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    @SuppressLint("MissingPermission")
    fun post(context: Context, digest: TodayDigest, mode: NotificationMode) {
        if (mode == NotificationMode.OFF || !canNotify(context)) return
        createChannel(context)
        val manager = NotificationManagerCompat.from(context)
        val open = openApp(context)

        val singles = buildList {
            digest.followedEpisodes.forEach { add(episodeKey(it) to episodeNotification(context, it, open)) }
            digest.followedMovies.forEach { add("movie-${it.movieId}" to movieNotification(context, it, open)) }
        }
        singles.take(MAX_SINGLE).forEach { (key, notification) -> manager.notify(key.hashCode(), notification) }

        val wantSummary = mode == NotificationMode.ALL && !digest.isEmpty || singles.size > 1
        if (!wantSummary) return
        val lines = digest.episodes.map { "${it.showName} ${it.code} · ${timeLabel(it)}" } +
            digest.movies.map { "${it.title} · ${movieKindLabel(it)}" }
        val title = summaryTitle(digest)
        val summary = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.take(3).joinToString(", "))
            .setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .also { style -> lines.take(7).forEach { style.addLine(it) } }
                    .setSummaryText(if (lines.size > 7) "și încă ${lines.size - 7}" else null),
            )
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_ID, summary)
    }

    private fun summaryTitle(digest: TodayDigest): String {
        val parts = buildList {
            if (digest.episodes.isNotEmpty()) add(plural(digest.episodes.size, "episod", "episoade"))
            if (digest.movies.isNotEmpty()) add(plural(digest.movies.size, "film", "filme"))
        }
        return "Azi apar " + parts.joinToString(" și ")
    }

    private fun plural(n: Int, one: String, many: String): String = when {
        n == 1 -> "1 $one"
        n % 100 in 1..19 -> "$n $many"
        else -> "$n de $many"
    }

    private fun episodeKey(e: EpisodeEntry) = "episode-${e.key}"

    private fun episodeNotification(context: Context, e: EpisodeEntry, open: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${e.showName} ${e.code}")
            .setContentText("Azi la ${timeLabel(e)} · ${whereToWatch(e)}")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    listOfNotNull(
                        e.episodeTitle?.takeIf { it.isNotBlank() },
                        "Azi la ${timeLabel(e)} (ora României) · ${whereToWatch(e)}",
                    ).joinToString("\n"),
                ),
            )
            .setGroup(GROUP)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

    private fun movieNotification(context: Context, m: MovieEntry, open: PendingIntent) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(m.title)
            .setContentText(
                listOfNotNull("Azi: " + movieKindLabel(m), movieTimeLabel(m).takeIf { m.hasTime }, movieWhere(m)).joinToString(" · "),
            )
            .setGroup(GROUP)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CalendarWork.EXTRA_OPEN_CALENDAR, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
