package ro.ak.vodunify.core.calendar.time

import ro.ak.vodunify.core.calendar.model.Region
import ro.ak.vodunify.core.calendar.model.StreamingApp
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
    /** True when the app gets the episode later than the original airing (licensed, not original). */
    val delayed: Boolean = false,
)

/**
 * Turns a TMDB episode air date (a local date in the show's home market) into a
 * moment in Romania time, for the app and region the user watches it on.
 *
 * Original airing (estimates until a source with exact hours is added):
 * - streaming originals (Netflix, Max, Disney+, …) drop at 00:00 Pacific time,
 *   which is 09:00–10:00 in Romania on the same date;
 * - broadcast and cable shows (HBO included) air at 21:00 in their home time
 *   zone, so a US evening episode lands at 03:00–04:00 the next day in Romania.
 *
 * Then the user's app and its region (RO or US, as set in Settings):
 * - the app is the show's own network (Netflix original on Netflix, HBO on Max),
 *   or a Romanian show on an app set to RO: same moment as the original airing,
 *   both in RO and US (these services release simultaneously);
 * - the app only licenses the show (an NBC show on Peacock, a CBS show on
 *   SkyShowtime): in the US it arrives the next morning (6 hours after the
 *   original airing, e.g. 21:00 ET → 03:00 ET); in Romania it arrives at 12:00
 *   Romania time, the same day if the original airing is before noon, otherwise
 *   the next day.
 */
class ReleaseTimeResolver {

    fun original(airDate: LocalDate, originCountries: List<String>, networkIds: Set<Int>): ResolvedRelease {
        val local: ZonedDateTime = when {
            networkIds.any { it in streamingNetworkIds } ->
                ZonedDateTime.of(airDate, LocalTime.MIDNIGHT, PACIFIC)
            else -> {
                val zone = originCountries.firstNotNullOfOrNull { originZones[it.uppercase()] }
                    ?: return ResolvedRelease(ZonedDateTime.of(airDate, LocalTime.MIDNIGHT, RomaniaTime.ZONE), true)
                ZonedDateTime.of(airDate, PRIMETIME, zone)
            }
        }
        return ResolvedRelease(local.withZoneSameInstant(RomaniaTime.ZONE), approximate = true)
    }

    fun resolve(
        airDate: LocalDate,
        originCountries: List<String>,
        networkIds: Set<Int>,
        app: StreamingApp?,
        region: Region?,
    ): ResolvedRelease {
        val original = original(airDate, originCountries, networkIds)
        if (app == null || region == null) return original
        val isOwnNetwork = app.tmdbNetworkIds.any { it in networkIds }
        // Romanian shows on Romanian apps (Voyo, …) go up together with the TV airing.
        val isHomeMarket = region == Region.RO && originCountries.any { it.equals("RO", ignoreCase = true) }
        if (isOwnNetwork || isHomeMarket) return original

        val licensed = when (region) {
            Region.US -> original.at.plusHours(US_LICENSED_DELAY_HOURS)
            Region.RO -> {
                val noon = original.at.toLocalDate().atTime(RO_LICENSED_TIME).atZone(RomaniaTime.ZONE)
                if (noon.isAfter(original.at)) noon else noon.plusDays(1)
            }
        }
        return ResolvedRelease(licensed.withZoneSameInstant(RomaniaTime.ZONE), approximate = true, delayed = true)
    }

    companion object {
        private val PACIFIC: ZoneId = ZoneId.of("America/Los_Angeles")
        private val PRIMETIME: LocalTime = LocalTime.of(21, 0)
        private const val US_LICENSED_DELAY_HOURS = 6L
        private val RO_LICENSED_TIME: LocalTime = LocalTime.NOON

        private val streamingNetworkIds: Set<Int> =
            StreamingCatalog.all.flatMap { it.tmdbNetworkIds }.toSet() - setOf(49) // HBO is a cable network

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
