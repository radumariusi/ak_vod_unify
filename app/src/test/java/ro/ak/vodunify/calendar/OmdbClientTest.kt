package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.omdb.OmdbClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OmdbClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = OmdbClient(OkHttpClient(), baseUrl = server.url("/").toString())

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `parses rating and votes and caches them`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Response":"True","imdbRating":"8.7","imdbVotes":"1,234,567"}"""))
        val first = client.rating("tt1", "key")!!
        assertEquals(8.7, first.rating, 0.0)
        assertEquals(1_234_567, first.votes)
        assertEquals(first, client.rating("tt1", "key"))
        assertEquals(1, server.requestCount)
        assertEquals("key", server.takeRequest().requestUrl!!.queryParameter("apikey"))
    }

    @Test
    fun `N A rating is null`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Response":"True","imdbRating":"N/A","imdbVotes":"N/A"}"""))
        assertNull(client.rating("tt2", "key"))
    }

    @Test
    fun `stops calling after the daily limit`() = runTest {
        server.enqueue(MockResponse().setBody("""{"Response":"False","Error":"Request limit reached!"}"""))
        assertNull(client.rating("tt3", "key"))
        assertNull(client.rating("tt4", "key"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `blank key makes no request`() = runTest {
        assertNull(client.rating("tt5", ""))
        assertEquals(0, server.requestCount)
    }
}
