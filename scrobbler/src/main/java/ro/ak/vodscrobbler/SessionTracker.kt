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
        private val random = java.util.Random()

        fun randomKey(): String = java.lang.Long.toHexString(random.nextLong() and 0xffffffffffL)

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
    /** Identifică o vizionare (aceeași aplicație + același titlu), ca AK VOD Unify să lege evenimentele. */
    val playId: String = "",
    /** Poziția nu vine de la aplicație, ci din timpul de redare numărat de noi (minim sigur). */
    val estimated: Boolean = false,
)

/**
 * Urmărește o singură sesiune media (o aplicație) și decide când trimitem poziția.
 * Pur (fără Android), ca să poată fi testat: timpul vine din afară, în ms de la pornirea
 * sistemului (aceeași bază ca `PlaybackState.getLastPositionUpdateTime()`).
 */
class SessionTracker(val packageName: String, val appLabel: String, private val sessionKey: String = randomKey()) {
    /** Pentru diagnostic: de ce NU am trimis (motiv, explicație). */
    var onSkip: ((SendReason, String) -> Unit)? = null

    var media: MediaInfo? = null
        private set
    var state: PlayState = PlayState.OTHER
        private set

    private var basePositionMs: Long? = null
    private var baseUpdateTime: Long = 0L
    private var speed: Float = 1f

    /** Aplicația ne-a dat măcar o dată poziția (altfel o luăm de pe ecran sau o numărăm noi). */
    private var positionFromSession = false

    /** Timpul de redare numărat de noi, pentru aplicațiile care nu dau deloc poziția. */
    private var playingSince: Long? = null
    private var playedMs: Long = 0L

    private var playCounter = 0

    /** Vizionarea curentă; se schimbă la alt titlu. */
    var playId: String = "$sessionKey-0"
        private set

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

    /** Timpul de redare numărat de noi (ms). */
    fun playedTime(now: Long): Long = playedMs + (playingSince?.let { (now - it).coerceAtLeast(0) } ?: 0L)

    /** Poziția reală dacă o știm, altfel timpul de redare numărat (marcat „estimat”). */
    private fun bestPosition(now: Long): Long? = position(now) ?: playedTime(now).takeIf { it > 0 }

    private val isEstimated: Boolean get() = basePositionMs == null

    /**
     * [correction] = titlul vine de pe ecran și doar l-am citit mai bine (aceeași redare continuă):
     * îl înlocuim fără să închidem vizionarea.
     */
    fun onMetadata(info: MediaInfo?, now: Long, correction: Boolean = false): List<Outgoing> {
        val previous = media
        if (previous != null && info != null && previous.identity == info.identity) {
            // Același titlu: doar actualizăm durata / câmpurile noi.
            media = info.copy(durationMs = info.durationMs ?: previous.durationMs)
            return emptyList()
        }
        if (previous != null && info != null && ((!previous.hasTitle && info.hasTitle) || correction)) {
            // Am aflat (sau corectat) titlul pentru redarea în curs: păstrăm poziția.
            media = info.copy(durationMs = info.durationMs ?: previous.durationMs)
            return emptyList()
        }
        val out = mutableListOf<Outgoing>()
        if (previous != null && previous.hasTitle && state != PlayState.STOPPED) {
            emit(SendReason.STOP, bestPosition(now), now, previous)?.let(out::add)
        }
        media = info
        basePositionMs = null
        positionFromSession = false
        playedMs = 0L
        playingSince = if (state == PlayState.PLAYING) now else null
        playId = "$sessionKey-${++playCounter}"
        lastSentAt = Long.MIN_VALUE
        lastSentPositionMs = null
        lastSentReason = null
        return out
    }

    fun onPlaybackState(newState: PlayState, positionMs: Long?, updateTime: Long, newSpeed: Float, now: Long): List<Outgoing> {
        val before = state
        val positionBefore = bestPosition(now)
        val realBefore = position(now)
        if (before == PlayState.PLAYING && newState != PlayState.PLAYING) {
            playedMs = playedTime(now)
            playingSince = null
        } else if (newState == PlayState.PLAYING && playingSince == null) {
            playingSince = now
        }
        state = newState
        if (positionMs != null && positionMs >= 0) {
            basePositionMs = positionMs
            baseUpdateTime = if (updateTime > 0) updateTime else now
            positionFromSession = true
        } else if (before == PlayState.PLAYING && newState != PlayState.PLAYING && realBefore != null) {
            // Aplicația nu spune minutul la pauză: îl înghețăm unde ajunsese redarea.
            basePositionMs = realBefore
            baseUpdateTime = now
        }
        speed = if (newSpeed > 0f) newSpeed else 1f

        return when {
            newState == PlayState.PAUSED && before != PlayState.PAUSED ->
                listOfNotNull(emit(SendReason.PAUSE, bestPosition(now), now))
            newState == PlayState.STOPPED && before != PlayState.STOPPED -> {
                // Unele aplicații resetează poziția la 0 la oprire: păstrăm ultima poziție reală.
                val reported = bestPosition(now)
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
        if (lastSentAt == Long.MIN_VALUE) {
            val p = bestPosition(now)
            if (p == null) {
                onSkip?.invoke(SendReason.TICK, "poziție necunoscută (aplicația nu o trimite)")
                return emptyList()
            }
            if (p < MIN_POSITION_MS) return emptyList()
        }
        return listOfNotNull(emit(SendReason.TICK, bestPosition(now), now))
    }

    /** Sesiunea a dispărut (aplicația s-a închis). */
    fun onGone(now: Long): List<Outgoing> {
        if (state == PlayState.STOPPED) return emptyList()
        val out = listOfNotNull(emit(SendReason.STOP, bestPosition(now), now))
        if (state == PlayState.PLAYING) {
            playedMs = playedTime(now)
            playingSince = null
        }
        state = PlayState.STOPPED
        return out
    }

    private fun emit(reason: SendReason, positionMs: Long?, now: Long, info: MediaInfo? = media): Outgoing? {
        // Fără titlu trimitem oricum: AK VOD Unify îl păstrează ca „neidentificat” (aplicație + durată + minut).
        val m = info ?: MediaInfo(emptyMap(), null)
        val pos = positionMs ?: return skip(reason, "poziție necunoscută (aplicația nu o trimite)")
        if (pos < MIN_POSITION_MS) return skip(reason, "poziție ${pos / 1000}s, sub ${MIN_POSITION_MS / 1000}s")
        val last = lastSentPositionMs
        if (last != null && lastSentReason != SendReason.TICK && reason != SendReason.TICK &&
            kotlin.math.abs(last - pos) < SAME_POSITION_MS
        ) {
            return skip(reason, "același minut ca trimiterea precedentă")
        }
        lastSentAt = now
        lastSentPositionMs = pos
        lastSentReason = reason
        return Outgoing(packageName, appLabel, reason, state, m, pos, playId, estimated = isEstimated)
    }

    /**
     * Minutul / durata citite de pe ecranul playerului. Poziția de pe ecran o folosim doar dacă
     * aplicația nu ne-o dă prin sesiunea media (aceea e mai precisă).
     */
    fun onScreenTimes(positionMs: Long?, durationMs: Long?, now: Long) {
        if (durationMs != null && durationMs > 0 && media?.durationMs == null) {
            media = (media ?: MediaInfo(emptyMap(), null)).copy(durationMs = durationMs)
        }
        if (positionMs != null && positionMs >= 0 && !positionFromSession) {
            basePositionMs = positionMs
            baseUpdateTime = now
            speed = 1f
        }
    }

    private fun skip(reason: SendReason, why: String): Outgoing? {
        onSkip?.invoke(reason, why)
        return null
    }

    companion object {
        private val random = java.util.Random()

        fun randomKey(): String = java.lang.Long.toHexString(random.nextLong() and 0xffffffffffL)

        /** În timpul redării trimitem poziția cel mult o dată pe minut. */
        const val TICK_INTERVAL_MS = 60_000L

        /** Sub 30 de secunde nu e o vizionare începută (meniuri, trailere scurte). */
        const val MIN_POSITION_MS = 30_000L

        /** Două pauze la mai puțin de 3 s distanță sunt aceeași poziție. */
        const val SAME_POSITION_MS = 3_000L
    }
}
