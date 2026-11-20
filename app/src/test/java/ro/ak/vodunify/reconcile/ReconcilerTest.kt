package ro.ak.vodunify.reconcile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import ro.ak.vodunify.core.db.OwnWriteAction
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.reconcile.KeyResolver
import ro.ak.vodunify.core.reconcile.LibraryStatus
import ro.ak.vodunify.core.reconcile.Reconciler
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.reconcile.reconcile
import java.time.Instant

class ReconcilerTest {
    private val hour = 3_600_000L
    private val matrix = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603, imdb = "tt0133093"), "The Matrix", 1999)
    private val matrixImdbOnly = MediaRef(MediaType.MOVIE, ExternalIds(imdb = "tt0133093"), "The Matrix", 1999)
    private val got = MediaRef(MediaType.SHOW, ExternalIds(tmdb = 1399, tvdb = 121361), "GoT", 2011)
    private val gotTvdbOnly = MediaRef(MediaType.SHOW, ExternalIds(tvdb = 121361, simkl = 17465), "Game of Thrones", 2011)

    private fun ep(show: MediaRef, s: Int, e: Int) = show.copy(type = MediaType.EPISODE, season = s, episode = e)
    private fun at(h: Long) = Instant.ofEpochMilli(1_700_000_000_000L + h * hour)
    private fun resume(m: MediaRef, p: Float?, h: Long, src: String) = TrackingEntities.resume(ResumePoint(m, p, at(h), src))
    private fun watch(m: MediaRef, h: Long, src: String, remote: Long? = null) = TrackingEntities.history(WatchEvent(m, at(h), src, remote))
    private fun own(m: MediaRef, action: OwnWriteAction, h: Long, src: String, key: String = m.itemKey, p: Float? = null) =
        OwnWriteEntity("$src|$key|${action.name}", src, key, action.name, p, at(h).toEpochMilli())

    @Test fun sameTitleMatchesAcrossDifferentIds() {
        val refs = listOf(TrackingEntities.ref(matrix), TrackingEntities.ref(matrixImdbOnly), TrackingEntities.ref(gotTvdbOnly), TrackingEntities.ref(got))
        val keys = KeyResolver(refs)
        assertEquals("movie:tmdb:603", keys.titleKey(TrackingEntities.ref(matrixImdbOnly)))
        assertEquals("tv:tmdb:1399", keys.titleKey(TrackingEntities.ref(gotTvdbOnly)))
        assertEquals("tv:tmdb:1399:s1e2", keys.itemKey(TrackingEntities.ref(ep(gotTvdbOnly, 1, 2))))
    }

    @Test fun newestResumeWinsNotHighestPercent() {
        val v = reconcile(
            resume = listOf(resume(matrix, 0.7f, 1, "trakt"), resume(matrixImdbOnly, 0.2f, 3, "simkl")),
            latestWatches = emptyList(), watched = emptyList(), list = emptyList(), next = emptyList(), own = emptyList(),
        )
        assertEquals(1, v.resume.size)
        assertEquals(0.2f, v.resume[0].progress)
        assertEquals("simkl", v.resume[0].winner.source)
        assertEquals(setOf("trakt", "simkl"), v.resume[0].evidences.map { it.source }.toSet())
    }

    @Test fun unknownPositionIsNotZeroAndStillCounts() {
        val v = reconcile(
            listOf(resume(matrix, null, 5, "wetrakr"), resume(matrix, 0.4f, 2, "trakt")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertEquals(1, v.resume.size)
        assertNull(v.resume[0].progress) // cea mai nouă dovadă nu știe poziția: rămâne necunoscută, nu 0%
        assertEquals("wetrakr", v.resume[0].winner.source)
    }

    @Test fun tieOnTimePrefersKnownPosition() {
        val v = reconcile(
            listOf(resume(matrix, null, 2, "wetrakr"), resume(matrix, 0.4f, 2, "trakt")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertEquals(0.4f, v.resume[0].progress)
    }

    @Test fun finishedAfterPauseInAnotherSourceClosesResume() {
        val e = ep(got, 1, 3)
        val v = reconcile(
            listOf(resume(e, 0.5f, 1, "trakt")),
            listOf(watch(ep(gotTvdbOnly, 1, 3), 2, "simkl")),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertTrue(v.resume.isEmpty())
    }

    @Test fun lateOldWatchDoesNotCloseNewerResume() {
        // Ai revăzut filmul: vizionarea veche (importată târziu) nu închide pauza nouă.
        val v = reconcile(
            listOf(resume(matrix, 0.3f, 10, "trakt")),
            listOf(watch(matrix, 2, "simkl")),
            emptyList(), emptyList(), emptyList(), emptyList(),
        )
        assertEquals(1, v.resume.size)
    }

    @Test fun sameWatchFromSeveralSourcesIsOneLogicalEvent() {
        val keys = KeyResolver(listOf(TrackingEntities.ref(matrix), TrackingEntities.ref(matrixImdbOnly)))
        val events = listOf(
            watch(matrix, 1, "trakt", remote = 11),
            watch(matrixImdbOnly, 1, "simkl"),
            watch(matrix, 1, "wetrakr"),
            watch(matrix, 100, "trakt", remote = 12), // revăzut după 4 zile = altă vizionare
        )
        val watches = Reconciler.watches(events, Reconciler.OwnWrites(emptyList()), keys)
        assertEquals(2, watches.size)
        assertEquals(setOf("trakt"), watches[0].sources)
        assertEquals(setOf("trakt", "simkl", "wetrakr"), watches[1].sources)
    }

    @Test fun ownWriteEchoIsRecognisedAndNotDuplicated() {
        val keys = KeyResolver(listOf(TrackingEntities.ref(matrix)))
        val writes = Reconciler.OwnWrites(listOf(own(matrix, OwnWriteAction.WATCHED, 1, "trakt"), own(matrix, OwnWriteAction.WATCHED, 1, "simkl")))
        val events = listOf(
            watch(matrix, 1, "trakt"), // copia locală
            watch(matrix, 1, "trakt", remote = 99), // ecoul din Trakt
            watch(matrix, 1, "simkl"),
        )
        val watches = Reconciler.watches(events, writes, keys)
        assertEquals(1, watches.size)
        assertEquals(2, watches[0].evidences.size)
        assertTrue(watches[0].fromApp)
    }

    @Test fun unwatchInAppHidesOlderImports() {
        val v = reconcile(
            emptyList(),
            emptyList(),
            listOf(TrackingEntities.watched(WatchEvent(matrix, at(1), "wetrakr"))),
            emptyList(), emptyList(),
            listOf(own(matrix, OwnWriteAction.UNWATCHED, 5, "trakt")),
        )
        assertFalse(v.library.any { LibraryStatus.WATCHED in it.statuses })
    }

    @Test fun removedFromWatchlistInAppDoesNotComeBackFromOldImport() {
        val v = reconcile(
            emptyList(), emptyList(), emptyList(),
            listOf(TrackingEntities.watchlist(WatchlistEntry(matrix, at(1), "wetrakr"))),
            emptyList(),
            listOf(own(matrix, OwnWriteAction.WATCHLIST_REMOVE, 3, "trakt")),
        )
        assertTrue(v.watchlist.isEmpty())
    }

    @Test fun movieWatchedAfterListingLeavesWatchlist() {
        val v = reconcile(
            emptyList(),
            listOf(watch(matrix, 5, "trakt")),
            emptyList(),
            listOf(TrackingEntities.watchlist(WatchlistEntry(matrixImdbOnly, at(1), "simkl"))),
            emptyList(), emptyList(),
        )
        assertTrue(v.watchlist.isEmpty())
    }

    @Test fun libraryMergesSourcesPerTitle() {
        val v = reconcile(
            listOf(resume(ep(got, 2, 1), 0.5f, 4, "trakt")),
            emptyList(),
            listOf(
                TrackingEntities.watched(WatchEvent(got, at(3), "trakt")),
                TrackingEntities.watched(WatchEvent(gotTvdbOnly, at(2), "simkl")),
            ),
            listOf(TrackingEntities.watchlist(WatchlistEntry(matrix, at(1), "wetrakr"))),
            emptyList(), emptyList(),
        )
        assertEquals(2, v.library.size)
        val gotEntry = v.library.first { it.titleKey == "tv:tmdb:1399" }
        assertEquals(setOf(LibraryStatus.IN_PROGRESS, LibraryStatus.WATCHED), gotEntry.statuses)
        assertEquals(setOf("trakt", "simkl"), gotEntry.sources)
        assertEquals(setOf("trakt"), gotEntry.sourcesByStatus[LibraryStatus.IN_PROGRESS])
        assertEquals(17465L, gotEntry.ref.simklId) // id-urile se completează între surse
    }
}
