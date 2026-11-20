package ro.ak.vodunify.core.simkl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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

/**
 * Conversii SIMKL ↔ modelele comune. Lucrează direct pe JSON (fără DTO-uri rigide),
 * pentru că SIMKL întoarce id-urile când ca text, când ca număr și omite câmpurile goale.
 */
object SimklMappers {
    const val SOURCE = "simkl"

    /** Tipurile din /sync/all-items și cheile lor din /sync/activities. */
    enum class Kind(val path: String, val activitiesKey: String, val itemKey: String) {
        SHOWS("shows", "tv_shows", "show"),
        ANIME("anime", "anime", "show"),
        MOVIES("movies", "movies", "movie"),
    }

    // ---------- JSON ----------

    fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    fun JsonElement?.arr(): JsonArray? = this as? JsonArray
    fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    fun JsonElement?.long(): Long? = str()?.toLongOrNull() ?: str()?.toDoubleOrNull()?.toLong()
    fun JsonElement?.int(): Int? = long()?.toInt()
    fun JsonElement?.float(): Float? = str()?.toFloatOrNull()

    fun instant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

    // ---------- Id-uri ----------

    fun ids(o: JsonObject?): ExternalIds {
        if (o == null) return ExternalIds()
        return ExternalIds(
            tmdb = o["tmdb"].long(),
            imdb = o["imdb"].str()?.takeIf { it.startsWith("tt") },
            simkl = o["simkl"].long() ?: o["simkl_id"].long(),
            tvdb = o["tvdb"].long(),
            mal = o["mal"].long(),
            anidb = o["anidb"].long(),
        )
    }

    /** Id-urile pentru scriere: SIMKL alege primul care se potrivește, deci punem întâi id-ul SIMKL. */
    fun idsJson(ids: ExternalIds): JsonObject = buildJsonObject {
        ids.simkl?.let { put("simkl", it) }
        ids.imdb?.let { put("imdb", it) }
        ids.tmdb?.let { put("tmdb", it.toString()) }
        ids.tvdb?.let { put("tvdb", it.toString()) }
        ids.mal?.let { put("mal", it.toString()) }
        ids.anidb?.let { put("anidb", it.toString()) }
    }

    /** Filmul / serialul unei intrări din all-items sau playback. */
    fun title(o: JsonObject?, type: MediaType): MediaRef? {
        if (o == null) return null
        val ids = ids(o["ids"].obj())
        val title = o["title"].str() ?: return null
        return MediaRef(type, ids, title, o["year"].int())
    }

    fun simklId(item: JsonObject, kind: Kind): Long? = ids(item[kind.itemKey].obj()?.get("ids").obj()).simkl

    // ---------- Episoade ----------

    /** „S01E05” / „E12” (anime) → numere. */
    fun parseEpisodeCode(code: String?): EpisodeNumber? {
        if (code.isNullOrBlank()) return null
        val m = Regex("""^\s*(?:S(\d+))?\s*E(\d+)\s*$""", RegexOption.IGNORE_CASE).find(code) ?: return null
        val season = m.groupValues[1].toIntOrNull() ?: 1
        val episode = m.groupValues[2].toIntOrNull() ?: return null
        return EpisodeNumber(season, episode)
    }

    /**
     * Episoadele văzute dintr-o intrare de serial / anime, cu data vizionării.
     * Pentru anime SIMKL numerotează adesea absolut (sezonul 1); dacă episodul are
     * maparea TVDB (`tvdb: {season, episode}`), folosim numerotarea TVDB, ca să se
     * potrivească cu Trakt / TMDb.
     */
    fun watchedEpisodes(item: JsonObject, anime: Boolean): List<Pair<EpisodeNumber, Instant?>> {
        val out = mutableListOf<Pair<EpisodeNumber, Instant?>>()
        item["seasons"].arr()?.forEach { s ->
            val season = s.obj() ?: return@forEach
            val seasonNumber = season["number"].int() ?: 1
            season["episodes"].arr()?.forEach { e ->
                val ep = e.obj() ?: return@forEach
                val number = ep["number"].int() ?: ep["episode"].int() ?: return@forEach
                val mapped = if (anime) ep["tvdb"].obj() else null
                val n = if (mapped != null && mapped["season"].int() != null && mapped["episode"].int() != null) {
                    EpisodeNumber(mapped["season"].int()!!, mapped["episode"].int()!!)
                } else {
                    EpisodeNumber(seasonNumber, number)
                }
                out += n to instant(ep["watched_at"].str())
            }
        }
        return out
    }

    // ---------- Citire ----------

    fun status(item: JsonObject): String? = item["status"].str()

    fun lastWatched(item: JsonObject): Instant? = instant(item["last_watched_at"].str())

    /** Vizionările complete (istoric): episoadele cu dată + filmele terminate. */
    fun history(item: JsonObject, kind: Kind): List<WatchEvent> {
        if (kind == Kind.MOVIES) {
            if (status(item) != "completed") return emptyList()
            val movie = title(item["movie"].obj(), MediaType.MOVIE) ?: return emptyList()
            val at = lastWatched(item) ?: return emptyList() // fără dată nu inventăm o vizionare
            return listOf(WatchEvent(movie, at, SOURCE))
        }
        val show = title(item["show"].obj(), MediaType.SHOW) ?: return emptyList()
        return watchedEpisodes(item, kind == Kind.ANIME).mapNotNull { (n, at) ->
            at ?: return@mapNotNull null
            WatchEvent(show.copy(type = MediaType.EPISODE, season = n.season, episode = n.episode), at, SOURCE)
        }
    }

    /** Titlurile văzute: film terminat sau serial cu cel puțin un episod văzut. */
    fun watchedTitle(item: JsonObject, kind: Kind): WatchEvent? {
        if (kind == Kind.MOVIES) {
            if (status(item) != "completed") return null
            val movie = title(item["movie"].obj(), MediaType.MOVIE) ?: return null
            return WatchEvent(movie, lastWatched(item) ?: Instant.EPOCH, SOURCE)
        }
        val watched = item["watched_episodes_count"].int() ?: 0
        if (watched <= 0 && status(item) != "completed") return null
        val show = title(item["show"].obj(), MediaType.SHOW) ?: return null
        return WatchEvent(show, lastWatched(item) ?: Instant.EPOCH, SOURCE)
    }

    fun watchlist(item: JsonObject, kind: Kind): WatchlistEntry? {
        if (status(item) != "plantowatch") return null
        val type = if (kind == Kind.MOVIES) MediaType.MOVIE else MediaType.SHOW
        val ref = title(item[kind.itemKey].obj(), type) ?: return null
        val at = instant(item["added_to_watchlist_at"].str()) ?: lastWatched(item) ?: Instant.EPOCH
        return WatchlistEntry(ref, at, SOURCE)
    }

    /** „Următorul episod” pentru serialele / anime-urile în curs. */
    fun progress(item: JsonObject, kind: Kind): ShowProgress? {
        if (kind == Kind.MOVIES) return null
        val status = status(item)
        if (status == "plantowatch" || status == "dropped") return null
        val show = title(item["show"].obj(), MediaType.SHOW) ?: return null
        val watchedList = watchedEpisodes(item, kind == Kind.ANIME)
        val total = item["total_episodes_count"].int() ?: 0
        val notAired = item["not_aired_episodes_count"].int() ?: 0
        val completed = item["watched_episodes_count"].int() ?: watchedList.size
        val aired = (total - notAired).coerceAtLeast(completed)
        val nextNumber = parseEpisodeCode(item["next_to_watch"].str())
        val next = nextNumber?.takeIf { completed < aired }?.let {
            show.copy(type = MediaType.EPISODE, season = it.season, episode = it.episode)
        }
        return ShowProgress(
            show = show,
            aired = aired,
            completed = completed,
            watched = watchedList.map { it.first }.toSet(),
            next = next,
            lastWatchedAt = lastWatched(item),
            source = SOURCE,
        )
    }

    /** Un element din GET /sync/playback (progres 0–100). */
    fun resume(o: JsonObject): ResumePoint? {
        val at = instant(o["paused_at"].str()) ?: return null
        val progress = o["progress"].float()?.let { (it / 100f).coerceIn(0f, 1f) }
        val media = when (o["type"].str()) {
            "movie" -> title(o["movie"].obj(), MediaType.MOVIE)
            else -> {
                val show = title((o["show"] ?: o["anime"]).obj(), MediaType.SHOW) ?: return null
                val ep = o["episode"].obj() ?: return null
                // Documentația dă numărul episodului când ca „number”, când ca „episode”.
                val number = ep["number"].int() ?: ep["episode"].int() ?: return null
                val season = ep["tvdb_season"].int()?.takeIf { o["anime"] != null } ?: ep["season"].int() ?: 1
                val episode = ep["tvdb_number"].int()?.takeIf { o["anime"] != null } ?: number
                show.copy(type = MediaType.EPISODE, season = season, episode = episode, episodeTitle = ep["title"].str())
            }
        } ?: return null
        return ResumePoint(media, progress, at, SOURCE, o["id"].long())
    }

    // ---------- Scriere ----------

    /** Corpul pentru /sync/history și /sync/history/remove. */
    fun historyBody(media: MediaRef, watchedAt: Instant?): JsonObject = buildJsonObject {
        when (media.type) {
            MediaType.MOVIE -> put("movies", buildJsonArray {
                add(buildJsonObject {
                    put("ids", idsJson(media.ids))
                    put("title", media.title)
                    media.year?.let { put("year", it) }
                    watchedAt?.let { put("watched_at", it.toString()) }
                })
            })
            MediaType.SHOW -> put("shows", buildJsonArray {
                add(buildJsonObject {
                    put("ids", idsJson(media.ids))
                    put("title", media.title)
                    media.year?.let { put("year", it) }
                    watchedAt?.let { put("watched_at", it.toString()) }
                })
            })
            MediaType.EPISODE -> put("shows", buildJsonArray {
                add(buildJsonObject {
                    put("ids", idsJson(media.ids))
                    put("title", media.title)
                    media.year?.let { put("year", it) }
                    put("seasons", buildJsonArray {
                        add(buildJsonObject {
                            put("number", media.season ?: 1)
                            put("episodes", buildJsonArray {
                                add(buildJsonObject {
                                    put("number", media.episode ?: 1)
                                    watchedAt?.let { put("watched_at", it.toString()) }
                                })
                            })
                        })
                    })
                })
            })
        }
    }

    /** Corpul pentru /sync/add-to-list: „to” se pune pe fiecare element. */
    fun addToListBody(media: MediaRef, to: String): JsonObject = buildJsonObject {
        val key = if (media.type == MediaType.MOVIE) "movies" else "shows"
        put(key, buildJsonArray {
            add(buildJsonObject {
                put("to", to)
                put("ids", idsJson(media.ids))
                put("title", media.title)
                media.year?.let { put("year", it) }
            })
        })
    }

    /** Corpul pentru /scrobble/pause (progres 0–100). */
    fun pauseBody(media: MediaRef, progress: Float): JsonObject = buildJsonObject {
        put("progress", (progress * 100f).coerceIn(0f, 100f).let { Math.round(it * 100f) / 100f })
        if (media.type == MediaType.MOVIE) {
            put("movie", buildJsonObject {
                put("title", media.title)
                media.year?.let { put("year", it) }
                put("ids", idsJson(media.ids))
            })
        } else {
            put("show", buildJsonObject {
                put("title", media.title)
                put("ids", idsJson(media.ids))
            })
            put("episode", buildJsonObject {
                put("season", media.season ?: 1)
                put("number", media.episode ?: 1)
            })
        }
    }
}
