package ro.ak.vodunify.core.scrobble

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.db.TrackingDao
import ro.ak.vodunify.core.publish.LOCAL_SOURCE
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.tracking.TrackingEntities
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Primește ce redă televizorul (de la AK Scrobbler), găsește titlul în TMDb și îl salvează ca
 * poziție locală — aceeași sursă ca „Setează minutul”, deci apare în „Continuă vizionarea”,
 * în „Alege unde vezi” (cu minutul exact) și, după profilul de publicare, ajunge în Trakt/SIMKL.
 *
 * Peste 90% la pauză/oprire îl trece ca văzut, doar local (în trackere „văzut” se marchează
 * numai explicit, ca la Etapa 6).
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matcher: ScrobbleMatcher,
    private val publisher: PublishRepository,
    private val trackingDao: TrackingDao,
    private val json: Json,
) {
    private val prefs = context.getSharedPreferences("scrobbler", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val logSerializer = ListSerializer(ScrobbleLogEntry.serializer())

    private val _log = MutableStateFlow(readLog())
    val log: StateFlow<List<ScrobbleLogEntry>> = _log.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Pachetul AK Scrobbler care se potrivește cu acest build (debug ↔ debug). */
    val scrobblerPackage: String = if (BuildConfig.DEBUG) "ro.ak.vodscrobbler.debug" else "ro.ak.vodscrobbler"

    fun isScrobblerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(scrobblerPackage, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
    }

    fun clearLog() {
        prefs.edit().remove(KEY_LOG).apply()
        _log.value = emptyList()
    }

    /**
     * Procesează un eveniment. Aruncă excepție doar pentru erori trecătoare (rețea), ca
     * scrobblerul să-l retrimită mai târziu.
     */
    suspend fun handle(event: ScrobbleEvent): ScrobbleResult = mutex.withLock {
        val raw = ScrobbleRules.rawLabel(event.fields)
        fun done(result: ScrobbleResult, media: MediaRef? = null, duration: Long? = event.duration): ScrobbleResult {
            if (!(event.isTick && result == ScrobbleResult.SAVED)) {
                // Punctele intermediare reușite nu umplu jurnalul; pauzele, opririle și problemele da.
                appendLog(event, raw, media, duration, result)
            } else {
                replaceLastTick(event, raw, media, duration, result)
            }
            return result
        }

        if (!_enabled.value) return@withLock done(ScrobbleResult.DISABLED)
        if (ScrobbleRules.isIgnoredPackage(event.pkg)) return@withLock done(ScrobbleResult.IGNORED)
        val reportedDuration = event.duration
        if (reportedDuration != null && reportedDuration < ScrobbleRules.MIN_DURATION_MS) {
            return@withLock done(ScrobbleResult.TOO_SHORT)
        }
        val guesses = TitleParser.guesses(event.fields, event.app)
        if (guesses.isEmpty() || !matcher.isConfigured) return@withLock done(ScrobbleResult.UNMATCHED)

        val match = matcher.match(guesses, reportedDuration) ?: return@withLock done(ScrobbleResult.UNMATCHED)
        val media = match.media
        val duration = reportedDuration ?: match.runtimeMs
        val progress = ScrobbleRules.progress(event.positionMs, duration)

        if (ScrobbleRules.isFinished(progress, event.isTick)) {
            markWatchedLocally(media, event.at)
            return@withLock done(ScrobbleResult.WATCHED, media, duration)
        }
        // Pauza / oprirea se publică (în modul automat); punctele intermediare doar se salvează.
        publisher.setManualPosition(media, event.positionMs, duration, publish = !event.isTick)
        done(ScrobbleResult.SAVED, media, duration)
    }

    private suspend fun markWatchedLocally(media: MediaRef, at: Long) {
        val key = "watched_${media.itemKey}"
        val last = prefs.getLong(key, 0L)
        trackingDao.deleteResumeById("$LOCAL_SOURCE|${media.itemKey}")
        if (at - last < TimeUnit.HOURS.toMillis(6)) return // aceeași vizionare, raportată din nou
        prefs.edit().putLong(key, at).apply()
        val event = WatchEvent(media, Instant.ofEpochMilli(at), LOCAL_SOURCE)
        trackingDao.insertHistory(listOf(TrackingEntities.history(event)))
        if (media.type != MediaType.EPISODE) trackingDao.upsertWatched(listOf(TrackingEntities.watched(event)))
    }

    private fun label(media: MediaRef?): String? = media?.let {
        if (it.type == MediaType.EPISODE) {
            "${it.title} S${it.season}E${it.episode}" + (it.episodeTitle?.let { t -> " · $t" } ?: "")
        } else {
            it.title + (it.year?.let { y -> " ($y)" } ?: "")
        }
    }

    private fun entry(event: ScrobbleEvent, raw: String, media: MediaRef?, duration: Long?, result: ScrobbleResult) = ScrobbleLogEntry(
        at = event.at,
        app = event.app,
        pkg = event.pkg,
        reason = event.reason,
        raw = raw,
        matched = label(media),
        positionMs = event.positionMs,
        durationMs = duration,
        result = result.wire,
    )

    private fun appendLog(event: ScrobbleEvent, raw: String, media: MediaRef?, duration: Long?, result: ScrobbleResult) {
        writeLog((listOf(entry(event, raw, media, duration, result)) + _log.value).take(MAX_LOG))
    }

    /** În timpul redării păstrăm o singură linie „în redare” pentru titlul curent, actualizată. */
    private fun replaceLastTick(event: ScrobbleEvent, raw: String, media: MediaRef?, duration: Long?, result: ScrobbleResult) {
        val current = _log.value
        val first = current.firstOrNull()
        val rest = if (first != null && first.reason == "tick" && first.pkg == event.pkg && first.raw == raw) current.drop(1) else current
        writeLog((listOf(entry(event, raw, media, duration, result)) + rest).take(MAX_LOG))
    }

    private fun writeLog(list: List<ScrobbleLogEntry>) {
        _log.value = list
        prefs.edit().putString(KEY_LOG, json.encodeToString(logSerializer, list)).apply()
    }

    private fun readLog(): List<ScrobbleLogEntry> = runCatching {
        prefs.getString(KEY_LOG, null)?.let { json.decodeFromString(logSerializer, it) }
    }.getOrNull().orEmpty()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LOG = "log"
        const val MAX_LOG = 50
    }
}
