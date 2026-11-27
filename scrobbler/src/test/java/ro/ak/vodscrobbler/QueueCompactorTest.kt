package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueCompactorTest {
    private fun ev(
        id: String,
        playId: String,
        reason: String,
        pos: Long,
        at: Long,
        fields: Map<String, String> = emptyMap(),
    ) = QueuedEvent(
        id = id,
        pkg = "com.amazon.amazonvideo.livingroom",
        app = "Prime Video",
        reason = reason,
        state = if (reason == "pause") "paused" else "playing",
        positionMs = pos,
        durationMs = 6_000_000L,
        at = at,
        playId = playId,
        estimated = false,
        fields = fields,
    )

    @Test
    fun compactsConsecutiveTicksSamePlayIdWhileKeepingFieldsAndPauses() {
        var queue = emptyList<QueuedEvent>()
        queue = QueueCompactor.append(queue, ev("1", "p1", "tick", 60_000, 100, mapOf("prime_gti" to "g1")))
        queue = QueueCompactor.append(queue, ev("2", "p1", "tick", 120_000, 200, mapOf("title" to "Film")))
        assertEquals(1, queue.size)
        assertEquals("2", queue.single().id)
        assertEquals(120_000L, queue.single().positionMs)
        assertEquals("g1", queue.single().fields["prime_gti"])
        assertEquals("Film", queue.single().fields["title"])

        // Pauza nu este suprascrisă de tick-uri ulterioare și nu dispare.
        queue = QueueCompactor.append(queue, ev("3", "p1", "pause", 180_000, 300))
        queue = QueueCompactor.append(queue, ev("4", "p1", "tick", 240_000, 400))
        assertEquals(listOf("2", "3", "4"), queue.map { it.id })
    }

    @Test
    fun preservesStopAndSeparatePlayIds() {
        var queue = emptyList<QueuedEvent>()
        queue = QueueCompactor.append(queue, ev("1", "p1", "pause", 100_000, 100))
        queue = QueueCompactor.append(queue, ev("2", "p1", "stop", 200_000, 200))
        queue = QueueCompactor.append(queue, ev("3", "p2", "tick", 60_000, 300))
        assertEquals(listOf("1", "2", "3"), queue.map { it.id })
    }
}
