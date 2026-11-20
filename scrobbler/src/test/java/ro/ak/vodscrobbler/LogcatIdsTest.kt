package ro.ak.vodscrobbler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatIdsTest {
    @Test
    fun netflixLatestMovieId() {
        val lines = listOf(
            "I/nf_player( 1234): open movieId=80057281 trackId=1",
            "D/nf_ui( 1234): something else",
            "I/nf_player( 1234): {\"movieId\":81091393,\"type\":\"episode\"}",
        )
        assertEquals("81091393", LogcatIds.extract("com.netflix.ninja", lines)["netflix_id"])
    }

    @Test
    fun netflixWatchUrl() {
        assertEquals("70143836", LogcatIds.extract("com.netflix.ninja", listOf("D/x: https://www.netflix.com/watch/70143836?trackId=1"))["netflix_id"])
    }

    @Test
    fun primeGti() {
        val ids = LogcatIds.extract("com.amazon.amazonvideo.livingroom", listOf("I/AIV: play amzn1.dv.gti.a686d37c-bb0b-437e-baff-bcd2a984ab42 start"))
        assertEquals("amzn1.dv.gti.a686d37c-bb0b-437e-baff-bcd2a984ab42", ids["prime_gti"])
    }

    @Test
    fun otherAppsNothing() {
        assertTrue(LogcatIds.extract("com.example", listOf("movieId=80057281")).isEmpty())
    }
}
