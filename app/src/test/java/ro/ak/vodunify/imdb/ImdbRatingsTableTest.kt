package ro.ak.vodunify.imdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.imdb.ImdbRatingsTable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ImdbRatingsTableTest {
    private val tsv = """
        tconst	averageRating	numVotes
        tt0000001	5.7	2100
        tt0000002	5.5	12
        tt0111161	9.3	3000000
        tt15398776	8.3	950000
    """.trimIndent()

    @Test fun parsesSkipsFewVotesAndRoundTrips() {
        val t = ImdbRatingsTable.parseTsv(tsv.reader().buffered())
        assertEquals(3, t.size)
        assertEquals(9.3, t.rating("tt0111161")!!, 0.001)
        assertEquals(8.3, t.rating("tt15398776")!!, 0.001)
        assertNull(t.rating("tt0000002")) // prea puține voturi
        assertNull(t.rating("tt9999999"))
        assertNull(t.rating(null))
        val out = ByteArrayOutputStream()
        t.write(out)
        val back = ImdbRatingsTable.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(5.7, back.rating("tt0000001")!!, 0.001)
    }
}
