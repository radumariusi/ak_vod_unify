package ro.ak.vodunify.core.scrobble

import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.db.ResumePointEntity
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * „Titlu greșit” (apăsare lungă pe un titlu recunoscut automat): îți arătăm celelalte titluri cu
 * același nume (episoade + filme cu anul), iar alegerea ta mută minutul / „văzut” pe titlul corect
 * (deci se schimbă peste tot: Continuă vizionarea, În curs, Lista mea, Istoric) și rămâne regulă
 * pentru vizionările următoare cu același text din aceeași aplicație.
 */
@Singleton
class TitleCorrection @Inject constructor(
    private val matcher: ScrobbleMatcher,
    private val scrobbles: ScrobbleRepository,
    private val publisher: PublishRepository,
    private val tracking: TrackingRepository,
) {
    data class Option(val media: MediaRef, val label: String)

    /** Numele după care căutăm: ce a citit AK Scrobbler (fără „S1, E1 …”), altfel titlul afișat. */
    fun searchName(titleKey: String, fallback: String, resume: ResumePointEntity?): String {
        val raw = scrobbles.lastRawFor(titleKey)?.second ?: return resume?.ref?.episodeTitle ?: fallback
        // Peacock: doar numele episodului; Apple TV: „Serial · S1, E1 · Episod” → episodul e cel mai specific.
        val parts = raw.split(" · ").map { it.trim() }.filter { it.isNotEmpty() && !EPISODE_CODE.containsMatchIn(it) }
        return parts.lastOrNull() ?: fallback
    }

    suspend fun options(name: String, current: String?): List<Option> =
        matcher.alternatives(name)
            .filter { it.media.itemKey != current }
            .map { m ->
                val r = m.media
                val label = if (r.type == MediaType.EPISODE) {
                    "Serial · ${r.title}${r.year?.let { " ($it)" } ?: ""} · S${r.season}E${r.episode}${r.episodeTitle?.let { " · $it" } ?: ""}"
                } else {
                    "Film · ${r.title}${r.year?.let { " ($it)" } ?: ""}"
                }
                Option(r, label)
            }

    /**
     * Mută vizionarea de pe titlul greșit pe [chosen] și ține minte alegerea. [wrongTitle] e filmul sau
     * serialul de pe card; pentru serial, episodul greșit vine din punctul de reluare.
     */
    suspend fun apply(wrongTitle: MediaRef, resume: ResumePointEntity?, wasWatched: Boolean, chosen: MediaRef) {
        val titleKey = wrongTitle.titleKey
        val raw = scrobbles.lastRawFor(titleKey)
        val wrongItem = resume?.ref?.let(TrackingEntities::media) ?: wrongTitle.takeIf { it.type == MediaType.MOVIE }
        val position = resume?.positionMs ?: 0L
        // Întâi scoatem titlul greșit (poate fi același serial, alt episod), apoi punem minutul pe cel corect.
        tracking.removeFromContinueWatching(titleKey)
        if (position > 0L) {
            publisher.setManualPosition(chosen, position, null, publish = false, appPackage = resume?.appPackage, recordedAt = resume?.updatedAt ?: System.currentTimeMillis())
        }
        if (wasWatched && resume == null) {
            // Trecut deja ca văzut (peste prag): „văzut” trece pe titlul corect.
            if (wrongItem != null && wrongItem.type != MediaType.SHOW) tracking.setWatched(wrongItem, false)
            tracking.setWatched(chosen, true)
        }
        raw?.let { (pkg, text) -> scrobbles.rememberCorrection(pkg, text, chosen) }
    }

    private companion object {
        val EPISODE_CODE = Regex("""(?i)(?<![\p{L}\d])[ST]\d{1,2}\s*[:.,\-]?\s*(?:E|Ep\.?\s?)\d{1,3}(?!\d)|\bSeason\s+\d+""")
    }
}
