package ro.ak.vodunify.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.db.WatchEventEntity
import ro.ak.vodunify.core.playback.PosterActivity
import ro.ak.vodunify.core.playback.PosterBadgeRequest
import ro.ak.vodunify.core.playback.PosterStage
import ro.ak.vodunify.core.reconcile.reconcile
import ro.ak.vodunify.ui.library.LibraryFilter
import ro.ak.vodunify.ui.library.LibraryLogic
import ro.ak.vodunify.ui.library.LibraryTab

class ContinueWatchingTest {
    private fun point(key: String, at: Long, episode: Int? = null) = ResumePointEntity(
        id = "local|$key|$episode",
        source = "local",
        ref = TrackedRef(key, if (episode == null) "MOVIE" else "EPISODE", key, null, episode?.let { 1 }, episode, null, null, null, null, null),
        progress = 0.3f,
        updatedAt = at,
        remoteId = null,
    )

    @Test
    fun keepsOnlyMostRecentPerTitle() {
        val list = listOf(
            point("tv:tmdb:1", at = 100, episode = 1),
            point("movie:tmdb:2", at = 300),
            point("tv:tmdb:1", at = 200, episode = 2),
        )
        val out = reconcile(list, emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).localContinueWatching
        assertEquals(listOf(300L, 200L), out.map { it.updatedAt })
        assertEquals(2, out[1].ref.episode)
    }

    @Test
    fun replayMovesMovieOutOfWatchedUntilTheNewPlaybackFinishes() {
        val play = point("movie:tmdb:2", at = 300).copy(progress = 0.96f, appPackage = "com.tubitv")
        val first = WatchEventEntity("old", "local", play.ref, 100)
        val replay = reconcile(listOf(play), listOf(first), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(listOf(play), replay.localContinueWatching)
        assertEquals(listOf(play.ref.mediaKey), LibraryLogic.filter(replay.library, LibraryFilter(LibraryTab.IN_PROGRESS)).map { it.titleKey })
        assertTrue(LibraryLogic.filter(replay.library, LibraryFilter(LibraryTab.WATCHED)).isEmpty())

        val done = reconcile(listOf(play), listOf(first, first.copy(id = "new", watchedAt = 400)), emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(done.localContinueWatching.isEmpty())
        val movie = LibraryLogic.filter(done.library, LibraryFilter(LibraryTab.WATCHED)).single()
        assertEquals(PosterStage.WATCHED, LibraryLogic.posterStage(movie))
        assertEquals("", LibraryLogic.subtitle(movie, LibraryTab.WATCHED))
    }

    @Test
    fun completedSeriesReopensOnReplayOrWhenANewEpisodeAirs() {
        val play = point("tv:tmdb:1", at = 300, episode = 2)
        val old = point("tv:tmdb:1", at = 150, episode = 1)
        val history = listOf(WatchEventEntity("first", "local", old.ref, 100), WatchEventEntity("second", "local", play.ref, 200))
        val key = play.ref.mediaKey

        val replay = reconcile(listOf(old, play), history, emptyList(), emptyList(), emptyList(), emptyList())
        val entry = LibraryLogic.mergeSeries(replay.library, replay.series, mapOf(key to 2)).single()
        assertEquals(listOf(play), replay.localContinueWatching)
        assertEquals(PosterStage.IN_PROGRESS, LibraryLogic.posterStage(entry))
        assertEquals(PosterStage.IN_PROGRESS, PosterBadgeRequest(
            TitleKind.TV, 1, null, PosterActivity(PosterStage.IN_PROGRESS, null, null, 2, hasActiveResume = true),
        ).resolvedStage(2))

        val stopped = reconcile(listOf(old), history, emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(stopped.localContinueWatching.isEmpty())
        assertEquals(PosterStage.WATCHED, LibraryLogic.posterStage(LibraryLogic.mergeSeries(stopped.library, stopped.series, mapOf(key to 2)).single()))
        assertEquals(PosterStage.IN_PROGRESS, LibraryLogic.posterStage(LibraryLogic.mergeSeries(stopped.library, stopped.series, mapOf(key to 3)).single()))

        val unwatch = OwnWriteEntity("manual", "local", "$key:s1", "UNWATCHED", null, 400)
        val reset = reconcile(listOf(old, play), history, emptyList(), emptyList(), emptyList(), listOf(unwatch))
        assertTrue(reset.series.isEmpty())
        assertTrue(reset.localContinueWatching.isEmpty())
    }
}
