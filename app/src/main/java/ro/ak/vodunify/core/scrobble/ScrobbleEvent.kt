package ro.ak.vodunify.core.scrobble

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ce trimite AK Scrobbler (JSON în coloana „payload” a insert-ului). */
@Serializable
data class ScrobbleEvent(
    val id: String = "",
    val pkg: String,
    val app: String = pkg,
    /** pause | stop | tick */
    val reason: String,
    /** playing | paused | stopped | … */
    val state: String = "",
    @SerialName("position_ms") val positionMs: Long,
    /** -1 = aplicația nu dă durata. */
    @SerialName("duration_ms") val durationMs: Long = -1,
    val at: Long,
    val fields: Map<String, String> = emptyMap(),
    /** O vizionare (aceeași aplicație + același titlu), ca să legăm evenimentele între ele. */
    @SerialName("play_id") val playId: String = "",
    /** Minutul e numărat de scrobbler (aplicația nu-l dă): e un minim sigur, nu minutul exact. */
    val estimated: Boolean = false,
) {
    val duration: Long? get() = durationMs.takeIf { it > 0 }
    val isTick: Boolean get() = reason == "tick"
}

/** Rezultatul, trimis înapoi scrobblerului (ultimul segment din URI) și afișat în jurnal. */
enum class ScrobbleResult(val wire: String, val label: String) {
    SAVED("saved", "minut salvat"),
    WATCHED("watched", "marcat văzut"),
    UNMATCHED("unmatched", "negăsit în TMDb (păstrat, se reîncearcă)"),
    PENDING("pending", "fără titlu încă (păstrat, se completează automat)"),
    IGNORED("ignored", "ignorat (nu e film/serial)"),
    TOO_SHORT("too_short", "prea scurt (sub 10 min)"),
    DISABLED("disabled", "primirea e oprită"),
}

/** O intrare din jurnalul „AK Scrobbler” din Setări. */
@Serializable
data class ScrobbleLogEntry(
    val at: Long,
    val app: String,
    val pkg: String,
    val reason: String,
    val raw: String,
    val matched: String? = null,
    val positionMs: Long,
    val durationMs: Long? = null,
    val result: String,
    val itemKey: String? = null,
)

internal const val SCROBBLE_PREFS = "scrobbler"
internal const val SCROBBLE_LOG = "log"
internal const val SCROBBLE_APP_HISTORY = "app_by_key"
