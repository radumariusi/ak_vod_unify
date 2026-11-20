package ro.ak.vodunify.publish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.db.OwnWriteAction
import ro.ak.vodunify.core.db.OwnWriteEntity
import ro.ak.vodunify.core.db.PublishRecordEntity
import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import ro.ak.vodunify.core.publish.PublishCandidate
import ro.ak.vodunify.core.publish.PublishDecision
import ro.ak.vodunify.core.publish.PublishMode
import ro.ak.vodunify.core.publish.PublishPlanner
import ro.ak.vodunify.core.publish.PublishProfile
import ro.ak.vodunify.core.publish.PublishStatus
import ro.ak.vodunify.core.publish.RouteDiagnostics
import ro.ak.vodunify.core.reconcile.Evidence
import ro.ak.vodunify.core.reconcile.reconcile
import ro.ak.vodunify.core.tracking.TrackingEntities
import java.time.Instant

class PublishPlannerTest {
    private val hour = 3_600_000L
    private val base = 1_700_000_000_000L
    private val matrix = MediaRef(MediaType.MOVIE, ExternalIds(tmdb = 603, imdb = "tt0133093"), "The Matrix", 1999)
    private val matrixImdbOnly = MediaRef(MediaType.MOVIE, ExternalIds(imdb = "tt0133093"), "The Matrix", 1999)
    private val ref = TrackingEntities.ref(matrix)

    private fun at(h: Long) = base + h * hour
    private fun resume(m: MediaRef, p: Float?, h: Long, src: String, pos: Long? = null) =
        TrackingEntities.resume(ResumePoint(m, p, Instant.ofEpochMilli(at(h)), src)).copy(positionMs = pos)

    private fun cand(p: Float?, origin: String = LOCAL_SOURCE, h: Long = 10) =
        PublishCandidate(matrix.itemKey, ref, p, null, origin, at(h))

    private fun record(p: Float, status: PublishStatus, originH: Long = 10, attemptH: Long = 10) = PublishRecordEntity(
        "trakt|${matrix.itemKey}", "trakt", matrix.itemKey, ref, p, null, LOCAL_SOURCE, at(originH), status.name, at(attemptH), null, null, null,
    )

    private fun skip(d: PublishDecision) = (d as? PublishDecision.Skip)?.reason

    @Test fun unknownPositionIsNeverPublishedAsZero() {
        assertNotNull(skip(PublishPlanner.decide(cand(null), "trakt", null, null, at(11), manual = true)))
    }

    @Test fun neverPublishesNearTheEndSoItCannotBecomeWatched() {
        assertNotNull(skip(PublishPlanner.decide(cand(0.80f), "trakt", null, null, at(11), manual = true)))
        assertNotNull(skip(PublishPlanner.decide(cand(0.95f), "trakt", null, null, at(11), manual = true)))
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(cand(0.5f), "trakt", null, null, at(11), manual = true))
    }

    @Test fun showsAndTinyProgressAreSkipped() {
        val show = cand(0.3f).copy(ref = ref.copy(type = "SHOW"))
        assertNotNull(skip(PublishPlanner.decide(show, "trakt", null, null, at(11), manual = true)))
        assertNotNull(skip(PublishPlanner.decide(cand(0.005f), "trakt", null, null, at(11), manual = true)))
    }

    @Test fun doesNotWriteBackToOriginOrOverNewerOrSamePosition() {
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f, origin = "trakt"), "trakt", null, null, at(11), manual = true)))
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", Evidence("trakt", at(12), 0.2f), null, at(13), manual = true)))
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", Evidence("trakt", at(5), 0.41f), null, at(13), manual = true)))
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(cand(0.4f), "trakt", Evidence("trakt", at(5), 0.1f), null, at(13), manual = true))
    }

    @Test fun idempotentForTheSamePosition() {
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", null, record(0.4f, PublishStatus.PUBLISHED), at(11), manual = true)))
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", null, record(0.4f, PublishStatus.SENT), at(11), manual = false)))
        // Neconfirmat: automatul așteaptă reverificarea, tu poți retrimite.
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", null, record(0.4f, PublishStatus.NOT_CONFIRMED), at(11), manual = false)))
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(cand(0.4f), "trakt", null, record(0.4f, PublishStatus.NOT_CONFIRMED), at(11), manual = true))
        // O poziție nouă se trimite chiar dacă cea veche a fost publicată.
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(cand(0.6f, h = 12), "trakt", null, record(0.4f, PublishStatus.PUBLISHED), at(13), manual = false))
    }

    @Test fun failedRetriesAtMostHourlyInAutomaticMode() {
        val failed = record(0.4f, PublishStatus.FAILED, attemptH = 10)
        assertNotNull(skip(PublishPlanner.decide(cand(0.4f), "trakt", null, failed, at(10) + 10 * 60_000, manual = false)))
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(cand(0.4f), "trakt", null, failed, at(12), manual = false))
    }

    @Test fun echoOfOwnPublishIsTracedBackToTheOriginalSource() {
        // Am publicat 40% din „local” în Trakt la ora 10; Trakt îl trimite înapoi cu paused_at puțin mai nou.
        val own = OwnWriteEntity("trakt|${matrix.itemKey}|RESUME", "trakt", matrix.itemKey, OwnWriteAction.RESUME.name, 0.4f, at(10))
        val v = reconcile(
            resume = listOf(resume(matrix, 0.4f, 10, LOCAL_SOURCE, pos = 2_000_000), resume(matrixImdbOnly, 0.4f, 10, "trakt").copy(updatedAt = at(10) + 1000)),
            latestWatches = emptyList(), watched = emptyList(), list = emptyList(), next = emptyList(), own = listOf(own),
        )
        val r = v.resume.single()
        assertEquals("trakt", r.winner.source)
        val c = PublishPlanner.candidate(r, 2_000_000)!!
        assertEquals(LOCAL_SOURCE, c.origin)
        assertEquals(2_000_000L, c.positionMs)
        // SIMKL nu are poziția: se poate publica; Trakt o are deja: nu.
        assertEquals(PublishDecision.Publish, PublishPlanner.decide(c, "simkl", null, null, at(11), manual = false))
        assertNotNull(skip(PublishPlanner.decide(c, "trakt", r.evidences.first { it.source == "trakt" }, null, at(11), manual = false)))
    }

    @Test fun echoWithoutOriginalIsNotRepublished() {
        val own = OwnWriteEntity("trakt|${matrix.itemKey}|RESUME", "trakt", matrix.itemKey, OwnWriteAction.RESUME.name, 0.4f, at(10))
        val v = reconcile(listOf(resume(matrix, 0.4f, 10, "trakt")), emptyList(), emptyList(), emptyList(), emptyList(), listOf(own))
        assertNull(PublishPlanner.candidate(v.resume.single()))
    }

    @Test fun readBackMatchesAcrossIdsAndVerifiesPosition() {
        val back = listOf(resume(matrixImdbOnly, 0.405f, 10, "trakt"))
        val m = PublishPlanner.matchReadBack(ref, back)
        assertNotNull(m)
        assertEquals(PublishStatus.PUBLISHED, PublishPlanner.verify(0.4f, m))
        assertEquals(PublishStatus.NOT_CONFIRMED, PublishPlanner.verify(0.6f, m))
        assertEquals(PublishStatus.NOT_CONFIRMED, PublishPlanner.verify(0.4f, null))
    }

    @Test fun autoOnlyRecentPositions() {
        assertTrue(PublishPlanner.autoEligible(cand(0.4f, h = 0), at(24)))
        assertFalse(PublishPlanner.autoEligible(cand(0.4f, h = 0), at(24 * 15)))
    }

    @Test fun diagnosticsShowsEverySourceWinnerDestinationsAndNuvio() {
        val v = reconcile(
            listOf(resume(matrix, 0.3f, 12, LOCAL_SOURCE, pos = 2_000_000), resume(matrixImdbOnly, 0.1f, 5, "trakt")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        )
        val all = listOf(resume(matrix, 0.3f, 12, LOCAL_SOURCE, pos = 2_000_000), resume(matrixImdbOnly, 0.1f, 5, "trakt"))
        val items = RouteDiagnostics.build(
            resume = v.resume,
            allResume = all,
            records = emptyList(),
            profile = PublishProfile(0, "Principal", PublishMode.ON_DEMAND, setOf("trakt", "simkl")),
            trackers = mapOf("trakt" to "Trakt", "simkl" to "SIMKL"),
            connected = setOf("trakt"),
            now = at(13),
        )
        val item = items.single()
        assertEquals(LOCAL_SOURCE, item.winner)
        assertEquals(listOf(LOCAL_SOURCE, "trakt", "simkl"), item.positions.map { it.source })
        assertEquals(2_000_000L, item.positions.first().positionMs)
        assertFalse(item.positions.last().present)
        assertEquals("gata de publicat", item.destinations.first { it.destination == "trakt" }.note)
        assertEquals("neconectat", item.destinations.first { it.destination == "simkl" }.note)
        assertTrue(item.canPublish)
        val nuvio = item.players.first { it.player == "Nuvio" }
        assertTrue(nuvio.getsPosition)
        assertTrue(nuvio.note.contains("mai vechi"))
    }

    @Test fun diagnosticsViewOnlyCannotPublish() {
        val all = listOf(resume(matrix, 0.3f, 12, LOCAL_SOURCE, pos = 2_000_000))
        val v = reconcile(all, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val item = RouteDiagnostics.build(
            v.resume, all, emptyList(),
            PublishProfile(0, "Principal", PublishMode.VIEW_ONLY, setOf("trakt")),
            mapOf("trakt" to "Trakt"), setOf("trakt"), at(13),
        ).single()
        assertFalse(item.canPublish)
        assertFalse(item.players.first { it.player == "Nuvio" }.getsPosition)
    }
}
