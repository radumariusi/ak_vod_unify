package ro.ak.vodunify.tmdb

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import ro.ak.vodunify.core.tmdb.TmdbClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class TmdbClientTest {
    private val server = MockWebServer().apply { start() }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private class FakeTmdbCacheDao : TmdbCacheDao {
        private val store = ConcurrentHashMap<String, TmdbCacheEntity>()
        override suspend fun get(key: String): TmdbCacheEntity? = store[key]
        override suspend fun put(entry: TmdbCacheEntity) { store[entry.key] = entry }
        override suspend fun window(first: String, last: String): List<TmdbCacheEntity> = emptyList()
        override suspend fun putAll(entries: List<TmdbCacheEntity>) { entries.forEach { store[it.key] = it } }
        override suspend fun prune(olderThan: Long) = Unit
        override suspend fun clear() = store.clear()
    }

    private fun newClient() = TmdbClient(OkHttpClient(), json, FakeTmdbCacheDao()).apply {
        overrideForTest(server.url("/").toString(), "test_key")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `deduplicates concurrent requests for the same key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":603}""").setBodyDelay(80, TimeUnit.MILLISECONDS))
        val client = newClient()

        val results = (1..8).map {
            async { client.getRaw("movie/603", mapOf("language" to "ro-RO")) }
        }.awaitAll()

        assertTrue(results.all { it == """{"id":603}""" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `retries on HTTP 429 and returns success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("""{"id":1399}"""))
        val client = newClient()

        val body = client.getRaw("tv/1399", emptyMap())

        assertEquals("""{"id":1399}""", body)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cancelling one waiter preserves in-flight request for sibling`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":42}""").setBodyDelay(150, TimeUnit.MILLISECONDS))
        val client = newClient()

        val first = async { client.getRaw("movie/42", emptyMap()) }
        val second = async { client.getRaw("movie/42", emptyMap()) }
        delay(25)
        first.cancelAndJoin()

        assertEquals("""{"id":42}""", second.await())
        assertEquals(1, server.requestCount)
    }
}
