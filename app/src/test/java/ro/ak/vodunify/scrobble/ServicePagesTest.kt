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
    fun primeInlineAndQuotedEpisodeNumbers() {
        val htmlInline = """<html><head><meta property="og:title" content="Prime Video: The Lord of the Rings: The Rings of Power - Season 3, Ep. 7"></head></html>"""
        val t1 = ServicePages.prime(htmlInline, gti)!!
        assertEquals("The Lord of the Rings: The Rings of Power", t1.show)
        assertEquals(3, t1.season)
        assertEquals(7, t1.episode)

        val htmlQuoted = """<html><head><meta property="og:title" content="Watch The Rings of Power - Season 3 | Prime Video"></head>
            <script>{"episodeNumber":"7","titleID":"$gti"}</script></html>"""
        val t2 = ServicePages.prime(htmlQuoted, gti)!!
        assertEquals("The Rings of Power", t2.show)
        assertEquals(3, t2.season)
        assertEquals(7, t2.episode)

        val seasonOnly = ServicePages.asFields(ro.ak.vodunify.core.scrobble.ServiceTitle("The Rings of Power", 3, null))
        assertTrue(TitleParser.guesses(seasonOnly, "Prime Video").none { it is TitleGuess.Movie })
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

    @Test
    fun primeSeasonPageHydrationPicksExactEpisodeOverSeasonOneSwitcher() {
        val epGti = "amzn1.dv.gti.af1a7586-2d90-4ece-8c98-24f1577a7852"
        val s3Gti = "amzn1.dv.gti.8c43a805-f19b-4642-b474-7494b7759e80"
        val html = """
            <html><head><title>Prime Video: The Lord of the Rings: The Rings of Power - Season 3</title></head>
            <body><script type="application/json">{
              "signupLink":"/signup?cGTI=$epGti",
              "seasons":[{"sequenceNumber":1,"titleType":"season"},{"sequenceNumber":2,"titleType":"season"},{"sequenceNumber":3,"titleType":"season"}],
              "self":{
                "$s3Gti":{"gti":"$s3Gti","sequenceNumber":3,"titleType":"season"},
                "$epGti":{"gti":"$epGti","sequenceNumber":7,"titleType":"episode"}
              },
              "episodes":{
                "amzn1.dv.gti.00000000-0000-0000-0000-000000000001":{"title":"Marnûkh","episodeNumber":1},
                "$epGti":{"title":"Orodruin"}
              }
            }</script></body></html>
        """.trimIndent()
        val t = ServicePages.prime(html, epGti)!!
        assertEquals("The Lord of the Rings: The Rings of Power", t.show)
        assertEquals(3, t.season)
        assertEquals(7, t.episode)
        assertEquals("Orodruin", t.episodeTitle)
    }

    @Test
    fun arteConfigExtractsTitleAndSubtitle() {
        val json = """{"data":{"id":"103438-001-A_en","attributes":{"metadata":{"title":"Love Has its Networks","subtitle":"Dino Dinner","duration":{"seconds":283}}}}}"""
        val t = ServicePages.arteConfig(json)!!
        assertEquals("Love Has its Networks", t.show)
        assertEquals("Dino Dinner", t.episodeTitle)
    }
}
