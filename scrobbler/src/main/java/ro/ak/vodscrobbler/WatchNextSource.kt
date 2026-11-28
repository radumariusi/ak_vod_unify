package ro.ak.vodscrobbler

import android.content.Context
import android.media.tv.TvContract

/**
 * Rândul „Continuă vizionarea” al Google TV (TvProvider, Watch Next). Cu READ_TV_LISTINGS (dat prin
 * ADB: pm grant <pachet> android.permission.READ_TV_LISTINGS), Android 11 ne arată și rândurile altor
 * aplicații marcate „searchable”. Apple TV își actualizează rândul când oprești / ieși: dacă momentul
 * ultimei vizionări e după începutul redării noastre, rândul e chiar titlul redat acum.
 */
object WatchNextSource {
    data class Row(
        val packageName: String,
        val title: String,
        val episodeTitle: String?,
        val season: String?,
        val episode: String?,
        val lastEngagementMs: Long,
    ) {
        fun guess(): ScreenGuess {
            val code = if (season != null && episode != null) "S$season, E$episode" else null
            val sub = listOfNotNull(code, episodeTitle?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null }
            return ScreenGuess(title, sub)
        }
    }

    sealed class Result {
        data class Rows(val rows: List<Row>) : Result()
        data class Error(val message: String) : Result()
    }

    fun query(context: Context, pkg: String): Result = runCatching {
        val projection = arrayOf(
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
        )
        val rows = mutableListOf<Row>()
        context.contentResolver.query(TvContract.WatchNextPrograms.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = c.getString(0) ?: continue
                if (p != pkg) continue
                val title = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                rows += Row(p, title, c.getString(2), c.getString(3), c.getString(4), if (c.isNull(5)) 0L else c.getLong(5))
            }
        }
        Result.Rows(rows.sortedByDescending { it.lastEngagementMs }) as Result
    }.getOrElse { Result.Error("${it.javaClass.simpleName}: ${it.message}") }
}
