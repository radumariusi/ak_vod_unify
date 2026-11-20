package ro.ak.vodunify.core.wetrakr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import java.time.Instant

/**
 * Conversii WeTrakr ↔ modelele comune, după forma rândurilor din add-on-ul Kodi:
 * `{type, ids:{tmdb,imdb,tvdb}, series_ids, season, episode, watched:{is_watched,
 * play_count, last_watched_at, unknown}, rating, planning}`; progresul vine separat
 * (`in_progress`: `{type, ids, series_ids, season, episode, percent, position_ms, updated_at}`).
 */
object WeTrakrMappers {
    const val SOURCE = "wetrakr"

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    fun JsonElement?.long(): Long? = str()?.toLongOrNull() ?: str()?.toDoubleOrNull()?.toLong()
    fun JsonElement?.int(): Int? = long()?.toInt()
    fun JsonElement?.float(): Float? = str()?.toFloatOrNull()
    fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

    fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun ids(o: JsonObject?): ExternalIds = ExternalIds(
        tmdb = o?.get("tmdb").long(),
        imdb = o?.get("imdb").str()?.takeIf { it.startsWith("tt") },
        tvdb = o?.get("tvdb").long(),
    )

    private fun hasIds(ids: ExternalIds) = ids.tmdb != null || ids.imdb != null || ids.tvdb != null

    /** Cheia stabilă a unui rând, ca în add-on: primul id (tmdb > imdb > tvdb); episoadele atârnă de serial. */
    fun rowKey(row: JsonObject): String? {
        fun first(ids: ExternalIds) = ids.tmdb?.let { "tmdb:$it" } ?: ids.imdb?.let { "imdb:$it" } ?: ids.tvdb?.let { "tvdb:$it" }
        val type = row["type"].str() ?: return null
        if (type == "episode") {
            val series = first(ids(row["series_ids"].obj()))
            val season = row["season"].int()
            val episode = row["episode"].int()
            if (series != null && season != null && episode != null) return "episode:$series:$season:$episode"
            return first(ids(row["ids"].obj()))?.let { "episode:$it" }
        }
        return first(ids(row["ids"].obj()))?.let { "$type:$it" }
    }

    /** Referința la element (film, serial sau episod) dintr-un rând. */
    fun media(row: JsonObject): MediaRef? {
        val title = row["title"].str() ?: row["name"].str()
        return when (row["type"].str()) {
            "movie" -> {
                val ids = ids(row["ids"].obj())
                if (!hasIds(ids)) null else MediaRef(MediaType.MOVIE, ids, title.orEmpty(), row["year"].int())
            }
            "show", "tvshow", "series" -> {
                val ids = ids(row["ids"].obj())
                if (!hasIds(ids)) null else MediaRef(MediaType.SHOW, ids, title.orEmpty(), row["year"].int())
            }
            "episode" -> {
                val series = ids(row["series_ids"].obj())
                val season = row["season"].int()
                val episode = row["episode"].int()
                if (!hasIds(series) || season == null || episode == null) {
                    null
                } else {
                    MediaRef(
                        MediaType.EPISODE,
                        series,
                        row["series_title"].str() ?: row["show_title"].str().orEmpty(),
                        null,
                        season,
                        episode,
                        title,
                    )
                }
            }
            else -> null
        }
    }

    private fun watched(row: JsonObject): JsonObject? = row["watched"].obj()?.takeIf { it["is_watched"].bool() }

    /** Momentul ultimei vizionări; null dacă WeTrakr nu-l știe („unknown”). */
    private fun watchedAt(w: JsonObject): Instant? =
        if (w["unknown"].bool()) null else instant(w["last_watched_at"].str())

    /** Vizionare completă (istoric), doar dacă știm când a fost. */
    fun history(row: JsonObject): WatchEvent? {
        val w = watched(row) ?: return null
        val media = media(row)?.takeIf { it.type != MediaType.SHOW } ?: return null
        val at = watchedAt(w) ?: return null
        return WatchEvent(media, at, SOURCE)
    }

    /** Titlu văzut: film văzut sau serial cu un episod văzut (la nivel de serial). */
    fun watchedTitle(row: JsonObject): WatchEvent? {
        val w = watched(row) ?: return null
        val media = media(row) ?: return null
        val at = watchedAt(w) ?: Instant.EPOCH
        return when (media.type) {
            MediaType.EPISODE -> WatchEvent(media.asShow(), at, SOURCE)
            else -> WatchEvent(media, at, SOURCE)
        }
    }

    fun watchlist(row: JsonObject, listedAt: Instant): WatchlistEntry? {
        if (!row["planning"].bool()) return null
        val media = media(row)?.takeIf { it.type != MediaType.EPISODE } ?: return null
        return WatchlistEntry(media, listedAt, SOURCE)
    }

    /** Un rând din `in_progress`. Procentul lipsă rămâne null (poziție necunoscută, nu 0%). */
    fun resume(row: JsonObject): ResumePoint? {
        val media = media(row)?.takeIf { it.type != MediaType.SHOW } ?: return null
        val percent = row["percent"].float()
        val runtime = row["runtime_s"].float()
        val position = row["position_ms"].float()
        val progress = when {
            percent != null -> percent / 100f
            position != null && runtime != null && runtime > 0 -> position / 1000f / runtime
            else -> null
        }?.coerceIn(0f, 1f)
        val at = instant(row["updated_at"].str()) ?: Instant.EPOCH
        return ResumePoint(media, progress, at, SOURCE)
    }

    /** Un element din `/next-episodes`: `{show:{title, ids}, episode:{season, number, title}, last_watched_at}`. */
    fun upNext(item: JsonObject): ShowProgress? {
        val show = item["show"].obj() ?: return null
        val ids = ids(show["ids"].obj())
        if (!hasIds(ids)) return null
        val ref = MediaRef(MediaType.SHOW, ids, show["title"].str().orEmpty(), show["year"].int())
        val ep = item["episode"].obj() ?: return null
        val season = ep["season"].int() ?: return null
        val number = ep["number"].int() ?: ep["episode"].int() ?: return null
        val next = ref.copy(type = MediaType.EPISODE, season = season, episode = number, episodeTitle = ep["title"].str())
        return ShowProgress(
            show = ref,
            aired = 0,
            completed = 0,
            watched = emptySet(),
            next = next,
            lastWatchedAt = instant(item["last_watched_at"].str()),
            source = SOURCE,
        )
    }

    // ---------- Scriere (evenimentele add-on-ului) ----------

    private fun idsJson(ids: ExternalIds): JsonObject = buildJsonObject {
        ids.tmdb?.let { put("tmdb", it) }
        ids.imdb?.let { put("imdb", it) }
        ids.tvdb?.let { put("tvdb", it) }
    }

    /** `event` = playing / paused / scrobble; [progress] 0–1. */
    fun event(event: String, media: MediaRef, progress: Float): JsonObject = buildJsonObject {
        put("event", event)
        put("progress", Math.round(progress.coerceIn(0f, 1f) * 1000f) / 10f)
        if (media.type == MediaType.MOVIE) {
            put("media_type", "movie")
            put("title", media.title)
            media.year?.let { put("year", it) }
            put("ids", idsJson(media.ids))
        } else {
            put("media_type", "episode")
            put("title", media.episodeTitle.orEmpty())
            put("show_title", media.title)
            put("show_ids", idsJson(media.ids))
            put("ids", buildJsonObject { })
            put("season", media.season ?: 1)
            put("episode", media.episode ?: 1)
        }
    }
}
