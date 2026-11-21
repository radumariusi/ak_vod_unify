package ro.ak.vodunify.tracking

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.db.TrackedRef
import ro.ak.vodunify.core.tracking.latestPerTitle

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
        val out = latestPerTitle(list)
        assertEquals(listOf(300L, 200L), out.map { it.updatedAt })
        assertEquals(2, out[1].ref.episode)
    }
}
