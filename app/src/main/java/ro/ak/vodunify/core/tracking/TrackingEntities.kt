package ro.ak.vodunify.core.tracking

import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.UpNextEntity
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.db.WatchedTitleEntity
import ro.ak.vodunify.core.db.WatchlistEntity

/** Conversii pure model conector ↔ entități Room (testabile fără Android). */
object TrackingEntities {

    fun ref(m: MediaRef) = TrackedRef(
        mediaKey = m.titleKey,
        type = m.type.name,
        title = m.title,
        year = m.year,
        season = m.season,
        episode = m.episode,
        episodeTitle = m.episodeTitle,
        tmdbId = m.ids.tmdb,
        imdbId = m.ids.imdb,
        traktId = m.ids.trakt,
        tvdbId = m.ids.tvdb,
        simklId = m.ids.simkl,
    )

    fun media(r: TrackedRef) = MediaRef(
        type = MediaType.entries.firstOrNull { it.name == r.type } ?: MediaType.MOVIE,
        ids = ExternalIds(tmdb = r.tmdbId, imdb = r.imdbId, trakt = r.traktId, tvdb = r.tvdbId, simkl = r.simklId),
        title = r.title,
        year = r.year,
        season = r.season,
        episode = r.episode,
        episodeTitle = r.episodeTitle,
    )

    fun resume(p: ResumePoint) = ResumePointEntity(
        id = "${p.source}|${p.media.itemKey}",
        source = p.source,
        ref = ref(p.media),
        progress = p.progress,
        updatedAt = p.updatedAt.toEpochMilli(),
        remoteId = p.remoteId,
    )

    fun history(e: WatchEvent) = WatchEventEntity(
        id = "${e.source}|${e.remoteId ?: "${e.media.itemKey}@${e.watchedAt.toEpochMilli()}"}",
        source = e.source,
        ref = ref(e.media),
        watchedAt = e.watchedAt.toEpochMilli(),
    )

    fun watched(e: WatchEvent) = WatchedTitleEntity(
        id = "${e.source}|${e.media.titleKey}",
        source = e.source,
        ref = ref(if (e.media.type == MediaType.EPISODE) e.media.asShow() else e.media),
        lastWatchedAt = e.watchedAt.toEpochMilli(),
    )

    fun watchlist(w: WatchlistEntry) = WatchlistEntity(
        id = "${w.source}|${w.media.titleKey}",
        source = w.source,
        ref = ref(w.media),
        listedAt = w.listedAt.toEpochMilli(),
    )

    fun upNext(p: ShowProgress): UpNextEntity? {
        val next = p.next ?: return null
        return UpNextEntity(
            id = "${p.source}|${p.show.titleKey}",
            source = p.source,
            ref = ref(next),
            aired = p.aired,
            completed = p.completed,
            lastWatchedAt = p.lastWatchedAt?.toEpochMilli() ?: 0L,
        )
    }

    /** Elimină dublurile când mai multe surse raportează același element. Păstrează cel mai recent. */
    fun <T> dedupe(items: List<T>, key: (T) -> String, time: (T) -> Long): List<T> =
        items.groupBy(key).values.map { group -> group.maxBy(time) }.sortedByDescending(time)
}
