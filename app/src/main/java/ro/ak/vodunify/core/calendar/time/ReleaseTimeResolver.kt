package ro.ak.vodunify.core.calendar.time

import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

object RomaniaTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Bucharest")

    fun today(clock: Clock = Clock.systemUTC()): LocalDate = LocalDate.now(clock.withZone(ZONE))

    fun now(clock: Clock = Clock.systemUTC()): ZonedDateTime = ZonedDateTime.now(clock.withZone(ZONE))
}

data class ResolvedRelease(
    /** Release moment, always in Europe/Bucharest. */
    val at: ZonedDateTime,
    /** TMDB only gives a date, so the hour is an estimate. */
    val approximate: Boolean,
    /** False when the original market's time zone is unknown and only the date is usable. */
    val hasTime: Boolean = true,
)

/**
 * Estimates the first airing from the TMDB date. Global streaming originals use
 * midnight Pacific, and broadcasts use 21:00 in the original network's market.
 * The result is converted to Bucharest time, including any date rollover.
 */
class ReleaseTimeResolver {

    fun original(airDate: LocalDate, originCountries: List<String>, networkIds: Set<Int>): ResolvedRelease {
        val local: ZonedDateTime = when {
            networkIds.any { it in streamingNetworkIds } ->
                ZonedDateTime.of(airDate, LocalTime.MIDNIGHT, PACIFIC)
            else -> {
                val zone = originCountries.firstNotNullOfOrNull { originZones[it.uppercase()] }
                    ?: return ResolvedRelease(airDate.atStartOfDay(RomaniaTime.ZONE), approximate = true, hasTime = false)
                ZonedDateTime.of(airDate, PRIMETIME, zone)
            }
        }
        return ResolvedRelease(local.withZoneSameInstant(RomaniaTime.ZONE), approximate = true)
    }

    companion object {
        private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        private val PRIMETIME: LocalTime = LocalTime.of(21, 0)
        private val streamingNetworkIds: Set<Int> =
            StreamingCatalog.all.filter { it.globalDrop }.flatMap { it.tmdbNetworkIds }.toSet() - setOf(49)

        private val originZones: Map<String, ZoneId> = mapOf(
            "US" to "America/New_York",
            "CA" to "America/Toronto",
            "GB" to "Europe/London",
            "IE" to "Europe/Dublin",
            "RO" to "Europe/Bucharest",
            "DE" to "Europe/Berlin",
            "FR" to "Europe/Paris",
            "ES" to "Europe/Madrid",
            "IT" to "Europe/Rome",
            "NL" to "Europe/Amsterdam",
            "BE" to "Europe/Brussels",
            "SE" to "Europe/Stockholm",
            "NO" to "Europe/Oslo",
            "DK" to "Europe/Copenhagen",
            "PL" to "Europe/Warsaw",
            "TR" to "Europe/Istanbul",
            "KR" to "Asia/Seoul",
            "JP" to "Asia/Tokyo",
            "CN" to "Asia/Shanghai",
            "TW" to "Asia/Taipei",
            "IN" to "Asia/Kolkata",
            "AU" to "Australia/Sydney",
            "NZ" to "Pacific/Auckland",
            "BR" to "America/Sao_Paulo",
            "MX" to "America/Mexico_City",
            "AR" to "America/Argentina/Buenos_Aires",
            "CO" to "America/Bogota",
        ).mapValues { ZoneId.of(it.value) }
    }
}
