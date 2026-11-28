package ro.ak.vodunify.core.scrobble

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.diagnostics.AppDiagLog
import ro.ak.vodunify.core.playback.LinkPrecision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ce a pornit Unify ultima dată (aplicația + titlul). E DOAR un indiciu: fără link direct poți
 * alege altceva în aplicație. Îl folosim numai dacă playerul nu dă niciun titlu și durata
 * redării se potrivește (±3 min); la episoade doar dacă Unify a deschis chiar episodul.
 */
@Singleton
class LaunchHints @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences(SCROBBLE_PREFS, Context.MODE_PRIVATE)

    @Serializable
    data class Hint(
        val pkg: String,
        val at: Long,
        val type: String,
        val tmdb: Long? = null,
        val imdb: String? = null,
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeTitle: String? = null,
        val runtimeMs: Long? = null,
        /** Cum a fost deschis: EPISODE / TITLE / SERIES / SEARCH / APP. */
        val precision: String,
        /** Vizionarea (play_id) căreia i-am dat deja titlul; altă vizionare nu-l mai primește. */
        val usedBy: String? = null,
    ) {
        fun media() = MediaRef(
            type = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.MOVIE),
            ids = ExternalIds(tmdb = tmdb, imdb = imdb),
            title = title,
            year = year,
            season = season,
            episode = episode,
            episodeTitle = episodeTitle,
        )
    }

    fun record(pkg: String, media: MediaRef, runtimeMinutes: Int?, precision: LinkPrecision, now: Long = System.currentTimeMillis()) {
        val hint = Hint(
            pkg = pkg, at = now, type = media.type.name, tmdb = media.ids.tmdb, imdb = media.ids.imdb,
            title = media.title, year = media.year, season = media.season, episode = media.episode,
            episodeTitle = media.episodeTitle, runtimeMs = runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L },
            precision = precision.name,
        )
        write(hint)
    }

    /** Titlul de la Unify, dacă redarea îl confirmă; altfel null (și scriem în jurnal de ce). */
    fun confirm(event: ScrobbleEvent): ScrobbleMatch? {
        val hint = read() ?: return null
        val verdict = Rules.check(hint, event)
        if (verdict != null) {
            if (hint.pkg == event.pkg && event.at - hint.at in 0..Rules.MAX_AGE_MS && hint.usedBy == null) {
                AppDiagLog.add("Indiciu Unify „${hint.title}” NEfolosit: $verdict")
            }
            return null
        }
        if (hint.usedBy == null && event.playId.isNotEmpty()) write(hint.copy(usedBy = event.playId))
        AppDiagLog.add("Indiciu Unify „${hint.title}” confirmat de durata redării → folosit")
        return ScrobbleMatch(hint.media(), hint.runtimeMs)
    }

    private fun read(): Hint? = prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(Hint.serializer(), it) }.getOrNull() }

    private fun write(h: Hint) {
        prefs.edit().putString(KEY, json.encodeToString(Hint.serializer(), h)).apply()
    }

    /** Regulile (pure, testabile). Întoarce motivul respingerii sau null dacă indiciul e confirmat. */
    object Rules {
        const val MAX_AGE_MS = 20 * 60_000L
        const val DURATION_TOLERANCE_MS = 3 * 60_000L

        fun check(hint: Hint, event: ScrobbleEvent): String? {
            if (hint.pkg != event.pkg) return "altă aplicație"
            val age = event.at - hint.at
            if (age < 0 || age > MAX_AGE_MS) return "pornit acum ${age / 60_000} min"
            if (hint.usedBy != null && hint.usedBy != event.playId) return "folosit deja la altă redare"
            if (hint.type == MediaType.SHOW.name) return "Unify a deschis serialul, nu un episod"
            if (hint.type == MediaType.EPISODE.name && hint.precision != LinkPrecision.EPISODE.name) {
                return "episodul nu a fost deschis direct (puteai alege altul)"
            }
            val runtime = hint.runtimeMs ?: return "durata titlului nu e cunoscută"
            val duration = event.duration ?: return "playerul nu dă durata"
            if (kotlin.math.abs(runtime - duration) > DURATION_TOLERANCE_MS) {
                return "durata redării (${duration / 60_000} min) nu e a titlului (${runtime / 60_000} min)"
            }
            return null
        }
    }

    private companion object {
        const val KEY = "launch_hint"
    }
}
