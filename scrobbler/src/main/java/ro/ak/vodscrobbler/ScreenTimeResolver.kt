package ro.ak.vodscrobbler

import kotlin.math.abs

/** Interpretarea timpilor fără etichete dintr-un singur player. */
internal class ScreenTimeResolver {
    private enum class Format { TOTAL, TOTAL_FIRST, REMAINING }

    private var anchor: Pair<Long, Long>? = null
    private var format: Format? = null

    fun reset() {
        anchor = null
        format = null
    }

    fun resolve(times: ScreenTimes, knownDuration: Long? = null): ScreenTimes {
        val pair = times.rawPair ?: return times
        val (first, second) = pair
        val previous = anchor
        val deltaFirst = previous?.let { first - it.first } ?: 0L
        val deltaSecond = previous?.let { second - it.second } ?: 0L
        val observed = when {
            previous != null && abs(deltaFirst) >= MIN_MOVEMENT_MS -> when {
                abs(deltaSecond + deltaFirst) <= ROUNDING_MS && deltaSecond != 0L -> Format.REMAINING
                abs(deltaSecond) <= ROUNDING_MS && second >= first -> Format.TOTAL
                else -> null
            }
            // Voyo afișează durata totală prima și minutul curent al doilea (ex. „45:54 | 00:29”).
            previous != null && abs(deltaFirst) <= ROUNDING_MS && deltaSecond >= MIN_MOVEMENT_MS && first >= second -> Format.TOTAL_FIRST
            first >= 60_000L && second == 0L -> Format.TOTAL_FIRST
            else -> null
        }
        if (previous == null || abs(deltaFirst) >= MIN_MOVEMENT_MS || abs(deltaSecond) >= MIN_MOVEMENT_MS) anchor = pair
        if (observed != null) format = observed

        val asTotal = knownDuration != null && second >= first && abs(second - knownDuration) <= ROUNDING_MS
        val asTotalFirst = knownDuration != null && first >= second && abs(first - knownDuration) <= ROUNDING_MS
        val asRemaining = knownDuration != null && abs(first + second - knownDuration) <= ROUNDING_MS
        val interpretation = when {
            asTotal && !asRemaining && !asTotalFirst -> Format.TOTAL
            asTotalFirst && !asTotal && !asRemaining -> Format.TOTAL_FIRST
            asRemaining && !asTotal && !asTotalFirst -> Format.REMAINING
            else -> format
        }
        return when (interpretation) {
            Format.REMAINING -> times.copy(positionMs = first, durationMs = first + second, remainingMs = second)
            Format.TOTAL -> times.copy(positionMs = first, durationMs = second.takeIf { it >= first }, remainingMs = null)
            Format.TOTAL_FIRST -> times.copy(positionMs = second, durationMs = first.takeIf { it >= second }, remainingMs = null)
            null -> if (second > first) {
                times.copy(positionMs = first, durationMs = null, remainingMs = null)
            } else {
                // Nu am confirmat încă dacă „45:54 | 00:29” e durată|poziție: așteptăm a doua citire înainte să trimitem fals 45:54.
                times.copy(positionMs = null, durationMs = null, remainingMs = null)
            }
        }
    }

    private companion object {
        const val MIN_MOVEMENT_MS = 3_000L
        const val ROUNDING_MS = 1_000L
    }
}
