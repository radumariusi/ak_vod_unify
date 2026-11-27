package ro.ak.vodunify.core.calendar.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.calendar.model.CalendarDateSerializer
import ro.ak.vodunify.core.db.TmdbCacheDao
import ro.ak.vodunify.core.db.TmdbCacheEntity
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@Serializable
internal data class CalendarPeriod(
    @Serializable(with = CalendarDateSerializer::class) val from: LocalDate,
    @Serializable(with = CalendarDateSerializer::class) val to: LocalDate,
) {
    init { require(from <= to) }

    fun days(): Sequence<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }
}

internal data class CachedCalendar<T>(val value: T?, val fresh: Boolean)

/** O intrare Room pe zi, inclusiv pentru zilele în care căutarea nu a găsit nimic. */
internal class CalendarReleaseCache<T>(
    private val dao: TmdbCacheDao,
    serializer: KSerializer<T>,
    private val slice: (T, LocalDate) -> T,
    private val merge: (List<T>, summary: T?) -> T,
    private val complete: (T) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class Day<T>(val query: CalendarPeriod, val value: T)

    private data class SavedDay<T>(val fetchedAt: Long, val day: Day<T>)

    private val json = Json { ignoreUnknownKeys = true }
    private val rowSerializer = Day.serializer(serializer)
    private val fetchLock = Mutex()

    suspend fun read(key: String, period: CalendarPeriod): CachedCalendar<T> {
        val days = readDays(key, period)
        if (days.isEmpty()) return CachedCalendar(null, false)
        val time = now()
        return CachedCalendar(result(period, days), period.days().all { days[it]?.isFresh(time) == true })
    }

    suspend fun load(
        key: String,
        period: CalendarPeriod,
        force: Boolean = false,
        onProgress: (suspend (T) -> Unit)? = null,
        fetch: suspend (CalendarPeriod, progress: (suspend (T) -> Unit)?) -> T,
    ): T = fetchLock.withLock {
        // O notificare sau preîncălzirea din Acasă poate fi terminat între timp aceeași cerere.
        val days = readDays(key, period).toMutableMap()
        val time = now()
        val missing = if (force) listOf(period) else missingCalendarPeriods(period) { days[it]?.isFresh(time) == true }
        missing.forEach { gap ->
            val progress: (suspend (T) -> Unit)? = if (onProgress == null) null else { partial ->
                val combined = withContext(Dispatchers.Default) {
                    merge(listOf(partial) + days.toSortedMap().values.map { it.day.value }, null)
                }
                onProgress(combined)
            }
            val loaded = fetch(gap, progress)
            val fetchedAt = now()
            val saved = withContext(Dispatchers.Default) {
                gap.days().associateWith { date ->
                    val part = slice(loaded, date)
                    val previous = days[date]?.day?.value
                    // La o eroare parțială, titlurile confirmate completează datele vechi.
                    val value = if (!complete(loaded) && previous != null) merge(listOf(part, previous), part) else part
                    SavedDay(fetchedAt, Day(gap, value))
                }
            }
            writeDays(key, saved)
            days.putAll(saved)
            if (onProgress != null && gap != missing.last()) onProgress(result(period, days))
        }
        result(period, days)
    }

    private fun SavedDay<T>.isFresh(time: Long): Boolean =
        time - fetchedAt < if (complete(day.value)) DAY_TTL else PARTIAL_TTL

    private suspend fun result(period: CalendarPeriod, days: Map<LocalDate, SavedDay<T>>): T = withContext(Dispatchers.Default) {
        val ordered = days.toSortedMap().values
        val first = ordered.first()
        // Numerele de titluri ascunse dintr-un interval mare nu descriu corect o subperioadă.
        val summary = first.day.value.takeIf {
            first.day.query == period && ordered.all { it.fetchedAt == first.fetchedAt && it.day.query == period }
        }
        merge(ordered.map { it.day.value }, summary)
    }

    private suspend fun readDays(key: String, period: CalendarPeriod): Map<LocalDate, SavedDay<T>> =
        withContext(Dispatchers.Default) {
            val prefix = "calendar:v1:$key:"
            val rows = try {
                dao.window("$prefix${period.from}", "$prefix${period.to}")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            rows.mapNotNull { row ->
                // O intrare incompatibilă se reface în același fel ca o zi încă necăutată.
                runCatching {
                    LocalDate.parse(row.key.removePrefix(prefix)) to
                        SavedDay(row.fetchedAt, json.decodeFromString(rowSerializer, row.body))
                }.getOrNull()
            }.toMap()
        }

    private suspend fun writeDays(key: String, days: Map<LocalDate, SavedDay<T>>) {
        try {
            withContext(Dispatchers.Default) {
                dao.putAll(days.map { (date, saved) ->
                    TmdbCacheEntity("calendar:v1:$key:$date", json.encodeToString(rowSerializer, saved.day), saved.fetchedAt)
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Rezultatul descărcat se poate afișa și dacă discul nu poate salva cache-ul.
        }
    }

    private companion object {
        val DAY_TTL = TimeUnit.DAYS.toMillis(1)
        val PARTIAL_TTL = TimeUnit.MINUTES.toMillis(15)
    }
}

/** Unește zilele lipsă consecutive pentru a cere numai intervalele care trebuie completate. */
internal fun missingCalendarPeriods(period: CalendarPeriod, fresh: (LocalDate) -> Boolean): List<CalendarPeriod> {
    val missing = mutableListOf<CalendarPeriod>()
    period.days().filterNot(fresh).forEach { day ->
        val end = missing.lastOrNull()
        if (end != null && end.to.plusDays(1) == day) missing[missing.lastIndex] = end.copy(to = day)
        else missing.add(CalendarPeriod(day, day))
    }
    return missing
}
