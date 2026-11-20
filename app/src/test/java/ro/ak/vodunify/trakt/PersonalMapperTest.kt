package ro.ak.vodunify.trakt

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.ui.home.PersonalMapper
import java.time.Instant

class PersonalMapperTest {
    @Test fun unknownProgressIsLabelledNotZero() {
        assertEquals("Poziție necunoscută", PersonalMapper.progressLabel(null))
        assertEquals("0% văzut", PersonalMapper.progressLabel(0f))
        assertEquals("42% văzut", PersonalMapper.progressLabel(0.42f))
    }

    @Test fun episodeLabel() {
        assertEquals("S01E02 · Pilot", PersonalMapper.episodeLabel(1, 2, "Pilot"))
        assertEquals("S10E11", PersonalMapper.episodeLabel(10, 11, null))
    }

    @Test fun resumeEntityRoundTrip() {
        val media = MediaRef(MediaType.EPISODE, ExternalIds(tmdb = 1399, trakt = 1), "GoT", 2011, 1, 3, "Lord Snow")
        val entity = TrackingEntities.resume(ResumePoint(media, null, Instant.ofEpochMilli(1000), "trakt", 9))
        assertEquals("trakt|tv:tmdb:1399:s1e3", entity.id)
        assertEquals("tv:tmdb:1399", entity.ref.mediaKey)
        assertEquals(null, entity.progress)
        assertEquals(media, TrackingEntities.media(entity.ref))
        val card = PersonalMapper.resume(entity, null)
        assertEquals("S01E03 · Lord Snow · Poziție necunoscută", card.subtitle)
        assertEquals(null, card.progress)
    }

    @Test fun dedupeKeepsNewest() {
        val list = listOf("a" to 1L, "a" to 5L, "b" to 3L)
        assertEquals(listOf("a" to 5L, "b" to 3L), TrackingEntities.dedupe(list, { it.first }, { it.second }))
    }
}
