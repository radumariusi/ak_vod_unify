package ro.ak.vodunify.core.calendar.quality

import kotlinx.serialization.Serializable
import ro.ak.vodunify.core.calendar.model.ImdbRating
import ro.ak.vodunify.core.calendar.model.QualityThresholds

/** Why a show is (or isn't) in the calendar. */
@Serializable
enum class QualityVerdict(val passed: Boolean, val label: String, val movieLabel: String) {
    PASS_FOLLOWED(true, "Urmărești serialul", "Urmărești filmul"),
    PASS_RATING(true, "Trece pragurile de notă", "Trece pragurile de notă"),
    PASS_ANTICIPATED(true, "Serial nou, pe lista celor mult așteptate", "Film nou, printre cele mai așteptate"),
    PASS_NEW_ALLOWED(true, "Serial nou (filtrul pentru seriale noi e oprit)", "Film nou (filtrul pentru filme noi e oprit)"),
    REJECT_LOW_RATING(false, "Notă sau voturi sub prag", "Notă sau voturi sub prag"),
    REJECT_NOT_ANTICIPATED(false, "Serial nou, nu e pe listele celor mult așteptate", "Film nou, nu e printre cele mai așteptate"),
}

data class QualityInput(
    val isNewShow: Boolean,
    val isAnticipated: Boolean,
    val isFollowed: Boolean,
    val tmdbRating: Double,
    val tmdbVotes: Int,
    val imdb: ImdbRating?,
)

/**
 * The series quality rules:
 *
 * - followed shows always pass;
 * - returning shows must reach the IMDb thresholds when IMDb data is available,
 *   otherwise the TMDB thresholds;
 * - new shows (season 1) pass when they are on an anticipated list, or when they
 *   already reach the thresholds (a strong debut). With the "only anticipated"
 *   switch off, every new show passes.
 */
object QualityGate {

    fun evaluate(input: QualityInput, t: QualityThresholds): QualityVerdict {
        if (input.isFollowed) return QualityVerdict.PASS_FOLLOWED
        val meetsBar = meetsRatingBar(input, t)
        if (!input.isNewShow) {
            return if (meetsBar) QualityVerdict.PASS_RATING else QualityVerdict.REJECT_LOW_RATING
        }
        return when {
            input.isAnticipated -> QualityVerdict.PASS_ANTICIPATED
            meetsBar -> QualityVerdict.PASS_RATING
            !t.newShowsOnlyIfAnticipated -> QualityVerdict.PASS_NEW_ALLOWED
            else -> QualityVerdict.REJECT_NOT_ANTICIPATED
        }
    }

    /**
     * Movies: one with too few votes to judge (not out yet, or just out) is "new"
     * and needs the anticipated list; one with enough votes must reach the bar.
     */
    fun evaluateMovie(
        tmdbRating: Double,
        tmdbVotes: Int,
        imdb: ImdbRating?,
        isAnticipated: Boolean,
        isFollowed: Boolean,
        t: QualityThresholds,
    ): QualityVerdict {
        val enoughVotes = (imdb?.votes ?: 0) >= t.minImdbVotes || tmdbVotes >= t.minTmdbVotes
        return evaluate(
            QualityInput(
                isNewShow = !enoughVotes,
                isAnticipated = isAnticipated,
                isFollowed = isFollowed,
                tmdbRating = tmdbRating,
                tmdbVotes = tmdbVotes,
                imdb = imdb,
            ),
            t,
        )
    }

    fun meetsRatingBar(input: QualityInput, t: QualityThresholds): Boolean {
        val imdb = input.imdb
        return if (imdb != null && imdb.votes > 0) {
            imdb.rating >= t.minImdbRating && imdb.votes >= t.minImdbVotes
        } else {
            input.tmdbRating >= t.minTmdbRating && input.tmdbVotes >= t.minTmdbVotes
        }
    }
}
