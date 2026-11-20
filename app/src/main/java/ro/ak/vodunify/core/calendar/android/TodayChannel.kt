package ro.ak.vodunify.core.calendar.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import ro.ak.vodunify.MainActivity
import ro.ak.vodunify.R
import ro.ak.vodunify.core.calendar.model.EpisodeEntry
import ro.ak.vodunify.core.calendar.model.MovieEntry
import ro.ak.vodunify.core.calendar.today.TodayDigest
import ro.ak.vodunify.core.calendar.format.movieKindLabel
import ro.ak.vodunify.core.calendar.format.movieWhere
import ro.ak.vodunify.core.calendar.format.timeLabel
import ro.ak.vodunify.core.calendar.format.whereToWatch

/**
 * The "Azi apare" channel on the Android TV home screen: today's episodes and movies,
 * followed ones first. Rebuilt on every refresh (programs are replaced, not merged).
 */
object TodayChannel {
    private const val TAG = "TodayChannel"
    private const val PREFS = "tv_channel"
    private const val KEY_CHANNEL = "channel_id"
    private const val KEY_PROGRAMS = "program_ids"
    private const val MAX_PROGRAMS = 25

    // TvContractCompat.PreviewPrograms constants, spelled out to stay clear of the
    // Java-interface constant lookups that Kotlin can't do through the class.
    private const val TYPE_MOVIE = 0
    private const val TYPE_TV_EPISODE = 3
    private const val ASPECT_RATIO_2_3 = 4
    private const val ASPECT_RATIO_16_9 = 0

    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Publishes [digest] (null = no data yet, keep what's there), or removes the channel when [enabled] is false. */
    fun update(context: Context, digest: TodayDigest?, enabled: Boolean) {
        if (!isSupported(context)) return
        try {
            val helper = PreviewChannelHelper(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var channelId = prefs.getLong(KEY_CHANNEL, -1L)
            // The user (or the launcher) may have removed the channel.
            if (channelId != -1L && runCatching { helper.getPreviewChannel(channelId) }.getOrNull() == null) {
                channelId = -1L
                prefs.edit().remove(KEY_CHANNEL).remove(KEY_PROGRAMS).apply()
            }
            if (!enabled) {
                if (channelId != -1L) runCatching { helper.deletePreviewChannel(channelId) }
                prefs.edit().clear().apply()
                return
            }
            if (channelId == -1L) {
                val channel = PreviewChannel.Builder()
                    .setDisplayName("Azi apare")
                    .setDescription("Episoadele și filmele care apar azi")
                    .setAppLinkIntentUri(openAppUri(context, null))
                    .setInternalProviderId("today")
                    .setLogo(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(256, 256))
                    .build()
                // The app's first channel is shown on the home screen without asking.
                channelId = helper.publishDefaultChannel(channel)
                prefs.edit().putLong(KEY_CHANNEL, channelId).apply()
            }
            if (digest == null) return

            prefs.getStringSet(KEY_PROGRAMS, emptySet()).orEmpty().forEach { id ->
                id.toLongOrNull()?.let { runCatching { helper.deletePreviewProgram(it) } }
            }
            val programs = digest.episodes.map { episodeProgram(context, channelId, it) } +
                digest.movies.map { movieProgram(context, channelId, it) }
            val ids = programs.take(MAX_PROGRAMS).mapIndexedNotNull { index, builder ->
                runCatching { helper.publishPreviewProgram(builder.setWeight(MAX_PROGRAMS - index).build()) }
                    .onFailure { Log.w(TAG, "program", it) }
                    .getOrNull()
            }
            prefs.edit().putStringSet(KEY_PROGRAMS, ids.map(Long::toString).toSet()).apply()
        } catch (e: Exception) {
            // No TV provider (some boxes), or the launcher refused: the app works without the channel.
            Log.w(TAG, "channel update failed", e)
        }
    }

    private fun episodeProgram(context: Context, channelId: Long, e: EpisodeEntry): PreviewProgram.Builder =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TYPE_TV_EPISODE)
            .setTitle(e.showName)
            .setSeasonNumber(e.seasonNumber)
            .setEpisodeNumber(e.episodeNumber)
            .setEpisodeTitle(e.episodeTitle?.takeIf { it.isNotBlank() } ?: e.code)
            .setDescription(
                listOfNotNull(
                    if (e.isFollowed) "Urmărești" else null,
                    "${e.code} · azi la ${timeLabel(e)}",
                    whereToWatch(e),
                ).joinToString(" · "),
            )
            .apply {
                val poster = e.posterUrl
                val still = e.stillUrl ?: e.backdropUrl
                if (poster != null) {
                    setPosterArtUri(Uri.parse(poster)).setPosterArtAspectRatio(ASPECT_RATIO_2_3)
                } else if (still != null) {
                    setPosterArtUri(Uri.parse(still)).setPosterArtAspectRatio(ASPECT_RATIO_16_9)
                }
            }
            .setInternalProviderId("episode-${e.key}")
            .setIntentUri(openAppUri(context, "episode-${e.key}"))

    private fun movieProgram(context: Context, channelId: Long, m: MovieEntry): PreviewProgram.Builder =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TYPE_MOVIE)
            .setTitle(m.title)
            .setDescription(
                listOfNotNull(if (m.isFollowed) "Urmărești" else null, "Azi: " + movieKindLabel(m), movieWhere(m)).joinToString(" · "),
            )
            .apply {
                val poster = m.posterUrl
                if (poster != null) setPosterArtUri(Uri.parse(poster)).setPosterArtAspectRatio(ASPECT_RATIO_2_3)
            }
            .setInternalProviderId("movie-${m.movieId}")
            .setIntentUri(openAppUri(context, "movie-${m.movieId}"))

    /** Opens the app. [item] names the picked program, so a later version can open its details directly. */
    private fun openAppUri(context: Context, item: String?): Uri {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(CalendarWork.EXTRA_OPEN_CALENDAR, true)
        if (item != null) intent.putExtra(EXTRA_ITEM, item)
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    const val EXTRA_ITEM = "ro.ak.vodunify.CALENDAR_TODAY_ITEM"
}
