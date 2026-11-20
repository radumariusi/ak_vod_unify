package ro.ak.vodscrobbler

/** Ce știm despre conținutul redat, exact cum îl dă aplicația (fără interpretare). */
data class MediaInfo(
    /** Câmpurile text din sesiunea media, după numele cheii (ex. "title" → "Stranger Things"). */
    val fields: Map<String, String>,
    val durationMs: Long?,
) {
    /** Identitatea conținutului: se schimbă când începe alt film / episod. */
    val identity: String
        get() = IDENTITY_KEYS.mapNotNull { fields[it] }.joinToString(" | ")

    val hasTitle: Boolean get() = identity.isNotBlank()

    companion object {
        val IDENTITY_KEYS = listOf("title", "display_title", "display_subtitle", "artist", "album", "album_artist")
    }
}

enum class PlayState { PLAYING, PAUSED, BUFFERING, STOPPED, OTHER }

enum class SendReason(val wire: String) {
    /** Ai pus pauză. */
    PAUSE("pause"),

    /** Redarea s-a oprit, aplicația s-a închis sau a început alt titlu. */
    STOP("stop"),

    /** Punct intermediar în timpul redării (dacă TV-ul se stinge brusc, avem minutul recent). */
    TICK("tick"),
}

/** Un eveniment de trimis către AK VOD Unify. */
data class Outgoing(
    val packageName: String,
    val appLabel: String,
    val reason: SendReason,
    val state: PlayState,
    val media: MediaInfo,
    val positionMs: Long,
)

/**
 * Urmărește o singură sesiune media (o aplicație) și decide când trimitem poziția.
 * Pur (fără Android), ca să poată fi testat: timpul vine din afară, în ms de la pornirea
 * sistemului (aceeași bază ca `PlaybackState.getLastPositionUpdateTime()`).
 */
class SessionTracker(val packageName: String, val appLabel: String) {
    var media: MediaInfo? = null
        private set
    var state: PlayState = PlayState.OTHER
        private set

    private var basePositionMs: Long? = null
    private var baseUpdateTime: Long = 0L
    private var speed: Float = 1f

    private var lastSentAt: Long = Long.MIN_VALUE
    private var lastSentPositionMs: Long? = null
    private var lastSentReason: SendReason? = null

    /** Poziția estimată acum (la redare, avansează cu timpul scurs × viteza). */
    fun position(now: Long): Long? {
        val base = basePositionMs ?: return null
        val pos = if (state == PlayState.PLAYING) {
            base + ((now - baseUpdateTime).coerceAtLeast(0) * speed).toLong()
        } else {
            base
        }
        val duration = media?.durationMs?.takeIf { it > 0 }
        return if (duration != null) pos.coerceIn(0, duration) else pos.coerceAtLeast(0)
    }

    fun onMetadata(info: MediaInfo?, now: Long): List<Outgoing> {
        val previous = media
        if (previous != null && info != null && previous.identity == info.identity) {
            // Același titlu: doar actualizăm durata / câmpurile noi.
            media = info
            return emptyList()
        }
        val out = mutableListOf<Outgoing>()
        if (previous != null && previous.hasTitle && state != PlayState.STOPPED) {
            emit(SendReason.STOP, position(now), now, previous)?.let(out::add)
        }
        media = info
        basePositionMs = null
        lastSentAt = Long.MIN_VALUE
        lastSentPositionMs = null
        lastSentReason = null
        return out
    }

    fun onPlaybackState(newState: PlayState, positionMs: Long?, updateTime: Long, newSpeed: Float, now: Long): List<Outgoing> {
        val before = state
        val positionBefore = position(now)
        state = newState
        if (positionMs != null && positionMs >= 0) {
            basePositionMs = positionMs
            baseUpdateTime = if (updateTime > 0) updateTime else now
        }
        speed = if (newSpeed > 0f) newSpeed else 1f

        return when {
            newState == PlayState.PAUSED && before != PlayState.PAUSED ->
                listOfNotNull(emit(SendReason.PAUSE, position(now), now))
            newState == PlayState.STOPPED && before != PlayState.STOPPED -> {
                // Unele aplicații resetează poziția la 0 la oprire: păstrăm ultima poziție reală.
                val reported = position(now)
                val p = reported?.takeIf { it > 0 } ?: positionBefore
                if (p != null && p != reported) basePositionMs = p
                listOfNotNull(emit(SendReason.STOP, p, now))
            }
            else -> emptyList()
        }
    }

    fun onTick(now: Long): List<Outgoing> {
        if (state != PlayState.PLAYING) return emptyList()
        if (lastSentAt != Long.MIN_VALUE && now - lastSentAt < TICK_INTERVAL_MS) return emptyList()
        if (lastSentAt == Long.MIN_VALUE && (position(now) ?: 0) < MIN_POSITION_MS) return emptyList()
        return listOfNotNull(emit(SendReason.TICK, position(now), now))
    }

    /** Sesiunea a dispărut (aplicația s-a închis). */
    fun onGone(now: Long): List<Outgoing> {
        if (state == PlayState.STOPPED) return emptyList()
        val out = listOfNotNull(emit(SendReason.STOP, position(now), now))
        state = PlayState.STOPPED
        return out
    }

    private fun emit(reason: SendReason, positionMs: Long?, now: Long, info: MediaInfo? = media): Outgoing? {
        val m = info ?: return null
        if (!m.hasTitle) return null
        val pos = positionMs ?: return null
        if (pos < MIN_POSITION_MS) return null
        val last = lastSentPositionMs
        if (last != null && lastSentReason != SendReason.TICK && reason != SendReason.TICK &&
            kotlin.math.abs(last - pos) < SAME_POSITION_MS
        ) {
            return null
        }
        lastSentAt = now
        lastSentPositionMs = pos
        lastSentReason = reason
        return Outgoing(packageName, appLabel, reason, state, m, pos)
    }

    companion object {
        /** În timpul redării trimitem poziția cel mult o dată pe minut. */
        const val TICK_INTERVAL_MS = 60_000L

        /** Sub 30 de secunde nu e o vizionare începută (meniuri, trailere scurte). */
        const val MIN_POSITION_MS = 30_000L

        /** Două pauze la mai puțin de 3 s distanță sunt aceeași poziție. */
        const val SAME_POSITION_MS = 3_000L
    }
}
