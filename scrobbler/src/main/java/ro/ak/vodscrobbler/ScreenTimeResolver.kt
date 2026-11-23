package ro.ak.vodscrobbler

import kotlin.math.abs

/** Interpretarea timpilor fără etichete dintr-un singur player. */
internal class ScreenTimeResolver {
    private enum class Format { TOTAL, REMAINING }

    private var anchor: Pair<Long, Long>? = null
    private var format: Format? = null

    fun reset() {
        anchor = null
        format = null
    }

    fun resolve(times: ScreenTimes, knownDuration: Long? = null): ScreenTimes {
        val pair = times.rawPair ?: return times
        val (position, second) = pair
        val previous = anchor
        val delta = previous?.let { position - it.first } ?: 0L
        val observed = if (previous != null && abs(delta) >= MIN_MOVEMENT_MS) {
            val change = second - previous.second
            when {
                abs(change + delta) <= ROUNDING_MS && change != 0L -> Format.REMAINING
                abs(change) <= ROUNDING_MS && second >= position -> Format.TOTAL
                else -> null
            }
        } else {
            null
        }
        if (previous == null || abs(delta) >= MIN_MOVEMENT_MS) anchor = pair
        if (observed != null) format = observed

        val asTotal = knownDuration != null && second >= position && abs(second - knownDuration) <= ROUNDING_MS
        val asRemaining = knownDuration != null && abs(position + second - knownDuration) <= ROUNDING_MS
        val interpretation = when {
            asTotal && !asRemaining -> Format.TOTAL
            asRemaining && !asTotal -> Format.REMAINING
            else -> format
        }
        return when (interpretation) {
            Format.REMAINING -> times.copy(durationMs = position + second, remainingMs = second)
            Format.TOTAL -> times.copy(durationMs = second.takeIf { it >= position }, remainingMs = null)
            null -> times.copy(durationMs = null, remainingMs = null)
        }
    }

    private companion object {
        const val MIN_MOVEMENT_MS = 3_000L
        const val ROUNDING_MS = 1_000L
    }
}
