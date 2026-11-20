package ro.ak.vodunify.core.trakt

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ro.ak.vodunify.core.connector.EpisodeNumber
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import java.time.Instant
import java.time.format.DateTimeParseException

/** Conversii pure Trakt DTO ↔ modelele comune ale conectorilor (testabile fără Android). */
object TraktMappers {
    const val SOURCE = "trakt"

    fun instant(value: String?): Instant? = try {
        value?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    fun ids(i: TraktIds) = ExternalIds(tmdb = i.tmdb, imdb = i.imdb?.takeIf { it.isNotBlank() }, trakt = i.trakt, tvdb = i.tvdb)

    fun movie(m: TraktMovie) = MediaRef(MediaType.MOVIE, ids(m.ids), m.title ?: "Fără titlu", m.year)

    fun show(s: TraktShow) = MediaRef(MediaType.SHOW, ids(s.ids), s.title ?: "Fără titlu", s.year)

    fun episode(show: TraktShow, e: TraktEpisode) = MediaRef(
        type = MediaType.EPISODE,
        ids = ids(show.ids),
        title = show.title ?: "Fără titlu",
        year = show.year,
        season = e.season,
        episode = e.number,
        episodeTitle = e.title,
    )

    /** Movie sau episode+show; null pentru tipuri pe care nu le folosim (season, list). */
    private fun media(type: String, movie: TraktMovie?, show: TraktShow?, episode: TraktEpisode?): MediaRef? = when (type) {
        "movie" -> movie?.let(::movie)
        "episode" -> if (show != null && episode != null) episode(show, episode) else null
        "show" -> show?.let(::show)
        else -> null
    }

    /**
     * Playback = vizionări începute. Progresul vine în procente (0–100); dacă lipsește,
     * păstrăm null (poziție necunoscută), nu 0%.
     */
    fun resume(p: TraktPlayback): ResumePoint? {
        val media = media(p.type, p.movie, p.show, p.episode) ?: return null
        val progress = p.progress?.takeIf { it.isFinite() && it >= 0f }?.let { (it / 100f).coerceIn(0f, 1f) }
        return ResumePoint(media, progress, instant(p.pausedAt) ?: Instant.EPOCH, SOURCE, p.id)
    }

    fun history(h: TraktHistoryItem): WatchEvent? {
        val media = media(h.type, h.movie, h.show, h.episode) ?: return null
        val at = instant(h.watchedAt) ?: return null
        return WatchEvent(media, at, SOURCE, h.id)
    }

    fun watched(w: TraktWatchedItem): WatchEvent? {
        val media = w.movie?.let(::movie) ?: w.show?.let(::show) ?: return null
        return WatchEvent(media, instant(w.lastWatchedAt) ?: Instant.EPOCH, SOURCE)
    }

    fun watchlist(item: TraktListItem): WatchlistEntry? {
        val media = media(item.type, item.movie, item.show, null) ?: return null
        return WatchlistEntry(media, instant(item.listedAt) ?: Instant.EPOCH, SOURCE)
    }

    fun progress(show: MediaRef, p: TraktShowProgress): ShowProgress {
        val watched = p.seasons.flatMap { s -> s.episodes.filter { it.completed }.map { EpisodeNumber(s.number, it.number) } }.toSet()
        val next = p.nextEpisode?.let { e ->
            show.copy(type = MediaType.EPISODE, season = e.season, episode = e.number, episodeTitle = e.title)
        }
        return ShowProgress(show.asShow(), p.aired, p.completed, watched, next, instant(p.lastWatchedAt), SOURCE)
    }

    // ---------- Corpuri pentru /sync/history, /sync/watchlist (adăugare / ștergere) ----------

    fun idsJson(ids: ExternalIds): JsonObject = buildJsonObject {
        ids.trakt?.let { put("trakt", it) }
        ids.tmdb?.let { put("tmdb", it) }
        ids.imdb?.let { put("imdb", it) }
        ids.tvdb?.let { put("tvdb", it) }
    }

    /**
     * Corpul standard Trakt pentru sincronizare: `{"movies":[…]}`, `{"shows":[…]}` sau,
     * pentru un episod, `{"shows":[{"ids":…,"seasons":[{"number":S,"episodes":[{"number":E}]}]}]}`.
     */
    fun syncBody(media: MediaRef, watchedAt: Instant? = null): JsonObject {
        require(media.ids.trakt != null || media.ids.tmdb != null || media.ids.imdb != null || media.ids.tvdb != null) {
            "Titlul nu are un id recunoscut de Trakt"
        }
        val at = watchedAt?.toString()
        return when (media.type) {
            MediaType.MOVIE -> buildJsonObject {
                put("movies", buildJsonArray { add(item(media, at)) })
            }
            MediaType.SHOW -> buildJsonObject {
                put("shows", buildJsonArray { add(item(media, at)) })
            }
            MediaType.EPISODE -> {
                val season = requireNotNull(media.season) { "Lipsește sezonul" }
                val episode = requireNotNull(media.episode) { "Lipsește episodul" }
                val ep = buildJsonObject {
                    put("number", episode)
                    at?.let { put("watched_at", it) }
                }
                val show = buildJsonObject {
                    put("ids", idsJson(media.ids))
                    put("seasons", JsonArray(listOf(buildJsonObject { put("number", season); put("episodes", JsonArray(listOf(ep))) })))
                }
                buildJsonObject { put("shows", JsonArray(listOf(show))) }
            }
        }
    }

    private fun item(media: MediaRef, watchedAt: String?) = buildJsonObject {
        put("ids", idsJson(media.ids))
        watchedAt?.let { put("watched_at", JsonPrimitive(it)) }
    }

    /** Pentru /scrobble/pause: corp cu movie sau show+episode și progres în procente. */
    fun scrobbleBody(media: MediaRef, progress: Float): JsonObject = buildJsonObject {
        when (media.type) {
            MediaType.MOVIE -> put("movie", buildJsonObject { put("ids", idsJson(media.ids)) })
            MediaType.EPISODE -> {
                put("show", buildJsonObject { put("ids", idsJson(media.ids)) })
                put(
                    "episode",
                    buildJsonObject {
                        put("season", requireNotNull(media.season))
                        put("number", requireNotNull(media.episode))
                    },
                )
            }
            MediaType.SHOW -> error("Progresul se publică pe film sau episod, nu pe serial")
        }
        put("progress", (progress.coerceIn(0f, 1f) * 100f).toDouble())
    }
}
