package ro.ak.vodunify.core.imports

import java.time.Instant
import java.time.ZoneId

/** Ce facem cu un element potrivit: istoric (văzut) sau punct de reluare. Pur, testabil. */
object ImportPlanner {
    /** Peste 90% din durată = văzut. */
    const val WATCHED_AT = 0.90f

    /** Sub 1% nu e o vizionare începută. */
    const val MIN_PROGRESS = 0.01f

    sealed interface Kind {
        data object Watched : Kind
        data class Resume(val progress: Float, val positionMs: Long) : Kind
        data object Skip : Kind
    }

    /**
     * [bookmarkSec] = unde ai rămas; [durationSec] = durata dată de serviciu; [runtimeMs] = durata din
     * TMDb, folosită când serviciul n-o dă. Fără poziție: e o intrare de istoric (văzut).
     */
    fun classify(bookmarkSec: Long?, durationSec: Long?, runtimeMs: Long?): Kind {
        val position = bookmarkSec?.takeIf { it > 0 }?.times(1000) ?: return Kind.Watched
        val duration = durationSec?.takeIf { it > 0 }?.times(1000) ?: runtimeMs?.takeIf { it > 0 } ?: return Kind.Watched
        val p = (position.toFloat() / duration).coerceIn(0f, 1f)
        return when {
            p >= WATCHED_AT -> Kind.Watched
            p < MIN_PROGRESS -> Kind.Skip
            else -> Kind.Resume(p, position)
        }
    }

    /** Ziua (în fusul orar dat) în care cade [atMs]: [început, sfârșit] în ms. */
    fun dayBounds(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val day = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }
}
