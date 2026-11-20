package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.filter.ContentClassifier
import ro.ak.vodunify.core.calendar.model.ContentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentClassifierTest {
    @Test
    fun anime() {
        assertEquals(setOf(ContentFilter.ANIME), ContentClassifier.classify("ja", listOf("JP"), listOf(16, 10759)))
    }

    @Test
    fun `western animation is not anime`() {
        assertEquals(setOf(ContentFilter.ANIMATION), ContentClassifier.classify("en", listOf("US"), listOf(16, 35)))
    }

    @Test
    fun turkishIndianKorean() {
        assertTrue(ContentFilter.TURKISH in ContentClassifier.classify("tr", listOf("TR"), listOf(18)))
        assertTrue(ContentFilter.INDIAN in ContentClassifier.classify("hi", listOf("IN"), listOf(18)))
        assertTrue(ContentFilter.KOREAN in ContentClassifier.classify("ko", listOf("KR"), listOf(18)))
    }

    @Test
    fun telenovela() {
        val c = ContentClassifier.classify("es", listOf("MX"), listOf(10766))
        assertTrue(ContentFilter.TELENOVELA in c)
        assertTrue(ContentFilter.SOAP in c)
    }

    @Test
    fun `talk show from type`() {
        assertTrue(ContentFilter.TALK_SHOW in ContentClassifier.classify("en", listOf("US"), listOf(35), "Talk Show"))
    }

    @Test
    fun `regular US drama has no category`() {
        assertEquals(emptySet<ContentFilter>(), ContentClassifier.classify("en", listOf("US"), listOf(18, 9648)))
    }
}
