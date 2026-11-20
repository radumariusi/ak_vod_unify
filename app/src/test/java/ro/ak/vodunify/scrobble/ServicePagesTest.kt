package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.scrobble.ServicePages
import ro.ak.vodunify.core.scrobble.TitleGuess
import ro.ak.vodunify.core.scrobble.TitleParser

class ServicePagesTest {
    private val gti = "amzn1.dv.gti.a686d37c-bb0b-437e-baff-bcd2a984ab42"

    @Test
    fun primeEpisodePage() {
        val html = """<html><head><meta property="og:title" content="Watch The Boys - Season 4 | Prime Video"></head>
            <script>{"titleID":"$gti","episodeNumber":3,"title":"We'll Keep the Red Flag Flying Here"}</script></html>"""
        val t = ServicePages.prime(html, gti)!!
        assertEquals("The Boys", t.show)
        assertEquals(4, t.season)
        assertEquals(3, t.episode)
        val guesses = TitleParser.guesses(ServicePages.asFields(t), "Prime Video")
        assertTrue(guesses.contains(TitleGuess.NumberedEpisode("The Boys", 4, 3, null)))
    }

    @Test
    fun primeMoviePageTitleTag() {
        val html = "<html><head><title>Prime Video: Road House</title></head></html>"
        val t = ServicePages.prime(html, gti)!!
        assertEquals("Road House", t.show)
        assertNull(t.season)
        assertNull(t.episode)
    }

    @Test
    fun netflixTitle() {
        val html = """<meta content="Stranger Things | Netflix Official Site" property="og:title">"""
        assertEquals("Stranger Things", ServicePages.netflix(html)!!.show)
        assertNull(ServicePages.netflix("<title>Netflix</title>"))
    }

    @Test
    fun vendorKeysAndAppNameAreNotTitles() {
        val fields = mapOf(
            "author" to "PrimeVideo",
            "media_id" to gti,
            "com.amazon.alexa.externalmediaplayer.metadata.content_type" to "VIDEO",
        )
        assertTrue(TitleParser.guesses(fields, "Prime Video").isEmpty())
    }
}
