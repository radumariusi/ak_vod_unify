package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.scrobble.ScrobbleLogEntry
import ro.ak.vodunify.core.scrobble.recordedPackage
import ro.ak.vodunify.core.tracking.TrackingEntities

class PlaybackSourceRecoveryTest {
    private val media = MediaRef(MediaType.EPISODE, ExternalIds(tmdb = 42), "A Show", 2026, season = 1, episode = 1)
    private val point = ResumePointEntity(
        id = "local|${media.itemKey}", source = "local", ref = TrackingEntities.ref(media),
        progress = .2f, updatedAt = 1_000L, remoteId = null, positionMs = 45_000L,
    )
    private val entry = ScrobbleLogEntry(
        at = 1_000L, app = "Tubi", pkg = "com.tubitv", reason = "pause", raw = "A Show",
        matched = "A Show S1E1 · Pilot", positionMs = 45_000L, result = "saved",
    )

    @Test fun recoversLegacyEntryOnlyForTheSameEpisodeAndPlayback() {
        assertEquals("com.tubitv", recordedPackage(point, listOf(entry)))
        assertNull(recordedPackage(point, listOf(entry.copy(matched = "A Show S1E10 · Finale"))))
        assertNull(recordedPackage(point, listOf(entry.copy(at = entry.at - 1))))
        assertNull(recordedPackage(point, listOf(entry.copy(positionMs = 46_000L))))
        assertNull(recordedPackage(point, listOf(entry.copy(result = "watched"))))
    }

    @Test fun prefersAnExactIdAndKeepsAlreadyKnownSources() {
        assertEquals("com.tubitv", recordedPackage(point, listOf(entry.copy(itemKey = media.itemKey, matched = "Alt titlu"))))
        assertNull(recordedPackage(point, listOf(entry.copy(itemKey = "tv:tmdb:43:s1e1"))))
        assertNull(recordedPackage(point.copy(appPackage = "com.netflix.ninja"), listOf(entry)))
    }

    @Test fun rejectsConflictingSources() {
        assertNull(recordedPackage(point, listOf(entry, entry.copy(pkg = "com.netflix.ninja"))))
    }
}
