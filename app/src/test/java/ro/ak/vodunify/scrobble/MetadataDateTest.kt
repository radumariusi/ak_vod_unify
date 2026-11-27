package ro.ak.vodunify.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.ak.vodunify.core.scrobble.MatchRules
import java.time.LocalDate

class MetadataDateTest {
    @Test
    fun peacockIsoDateBecomesDay() {
        // The Good Daughter S1E1 „What Happened to Charlie” (TMDb: 12.11.2026).
        assertEquals(LocalDate.of(2026, 11, 12), MatchRules.metadataDate("2026-11-12T10:00:00.000Z"))
        assertEquals(LocalDate.of(2026, 4, 17), MatchRules.metadataDate("2026-04-17"))
    }

    @Test
    fun missingOrBrokenDateIsNull() {
        assertNull(MatchRules.metadataDate(null))
        assertNull(MatchRules.metadataDate(""))
        assertNull(MatchRules.metadataDate("ieri"))
        assertNull(MatchRules.metadataDate("2026-13-40T00:00:00Z"))
    }
}
