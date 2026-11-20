package ro.ak.vodunify.calendar

import ro.ak.vodunify.core.calendar.model.ImdbRating
import ro.ak.vodunify.core.calendar.model.QualityThresholds
import ro.ak.vodunify.core.calendar.quality.QualityGate
import ro.ak.vodunify.core.calendar.quality.QualityInput
import ro.ak.vodunify.core.calendar.quality.QualityVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityGateTest {
    private val t = QualityThresholds(minImdbRating = 7.0, minImdbVotes = 5_000, minTmdbRating = 7.0, minTmdbVotes = 300)

    private fun input(
        new: Boolean = false,
        anticipated: Boolean = false,
        followed: Boolean = false,
        tmdb: Pair<Double, Int> = 0.0 to 0,
        imdb: ImdbRating? = null,
    ) = QualityInput(new, anticipated, followed, tmdb.first, tmdb.second, imdb)

    @Test
    fun `returning show uses IMDb when available`() {
        assertEquals(QualityVerdict.PASS_RATING, QualityGate.evaluate(input(tmdb = 6.0 to 50, imdb = ImdbRating(7.5, 20_000)), t))
        assertEquals(QualityVerdict.REJECT_LOW_RATING, QualityGate.evaluate(input(tmdb = 8.0 to 5_000, imdb = ImdbRating(6.9, 20_000)), t))
        assertEquals(QualityVerdict.REJECT_LOW_RATING, QualityGate.evaluate(input(imdb = ImdbRating(8.0, 4_999)), t))
    }

    @Test
    fun `returning show falls back to TMDB`() {
        assertEquals(QualityVerdict.PASS_RATING, QualityGate.evaluate(input(tmdb = 7.0 to 300), t))
        assertEquals(QualityVerdict.REJECT_LOW_RATING, QualityGate.evaluate(input(tmdb = 7.0 to 299), t))
        assertEquals(QualityVerdict.REJECT_LOW_RATING, QualityGate.evaluate(input(tmdb = 6.9 to 1_000), t))
    }

    @Test
    fun `new show needs the anticipated list or a strong start`() {
        assertEquals(QualityVerdict.PASS_ANTICIPATED, QualityGate.evaluate(input(new = true, anticipated = true), t))
        assertEquals(QualityVerdict.PASS_RATING, QualityGate.evaluate(input(new = true, imdb = ImdbRating(8.2, 30_000)), t))
        assertEquals(QualityVerdict.REJECT_NOT_ANTICIPATED, QualityGate.evaluate(input(new = true, tmdb = 9.0 to 12), t))
        assertEquals(
            QualityVerdict.PASS_NEW_ALLOWED,
            QualityGate.evaluate(input(new = true), t.copy(newShowsOnlyIfAnticipated = false)),
        )
    }

    @Test
    fun `followed show always passes`() {
        assertEquals(QualityVerdict.PASS_FOLLOWED, QualityGate.evaluate(input(followed = true, tmdb = 2.0 to 3), t))
    }
}
