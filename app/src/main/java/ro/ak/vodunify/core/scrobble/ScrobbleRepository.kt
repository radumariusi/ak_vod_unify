package ro.ak.vodunify.core.scrobble

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * Peste pragul „văzut” (implicit 90%, se schimbă din Setări) îl trece ca văzut și în istoric,
 * local. Vizionările pe care nu le putem recunoaște încă nu se pierd: le păstrăm și le completăm
 * automat (alt eveniment al aceleiași vizionări sau o reîncercare).
 */
@Singleton
class ScrobbleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val matcher: ScrobbleMatcher,
    private val publisher: PublishRepository,
    private val trackingDao: TrackingDao,
    private val serviceTitles: ServiceTitleResolver,
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

    private val eventsSerializer = ListSerializer(ScrobbleEvent.serializer())

    private val _watchedPercent = MutableStateFlow(prefs.getInt(KEY_WATCHED_PCT, (ScrobbleRules.WATCHED_AT * 100).toInt()))

    /** De la ce procent un film / episod e trecut automat ca văzut. */
    val watchedPercent: StateFlow<Int> = _watchedPercent.asStateFlow()

    fun setWatchedPercent(value: Int) {
        val v = value.coerceIn(50, 99)
        prefs.edit().putInt(KEY_WATCHED_PCT, v).apply()
        _watchedPercent.value = v
    }

    /** Vizionările deja recunoscute (după play_id): evenimentele lor fără titlu merg tot acolo. */
    private val byPlay = object : LinkedHashMap<String, ScrobbleMatch>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrobbleMatch>?) = size > 200
    }

    /** Câte vizionări neidentificate încă păstrăm (le reîncercăm automat). */
    fun unresolvedCount(): Int = readUnresolved().size

    /**
     * Procesează un eveniment. Aruncă excepție doar pentru erori trecătoare (rețea), ca
     * scrobblerul să-l retrimită mai târziu.
     */
    suspend fun handle(event: ScrobbleEvent): ScrobbleResult = mutex.withLock {
        val result = process(event, fromQueue = false)
        runCatching { retryUnresolvedIfDue(event.at) }
        result
    }

    private suspend fun process(original: ScrobbleEvent, fromQueue: Boolean): ScrobbleResult {
        // Fără titlu, dar cu codul titlului (Prime Video GTI, Netflix id): îl aflăm de pe pagina serviciului.
        // Codul e mai sigur decât textele aplicației, deci are prioritate.
        val extra = serviceTitles.fieldsFor(original)
        val event = if (extra.isEmpty()) original else original.copy(fields = original.fields + extra)
        val raw = ScrobbleRules.rawLabel(event.fields) + (serviceTitles.noteFor(original)?.let { " [$it]" } ?: "")
        fun done(result: ScrobbleResult, media: MediaRef? = null, duration: Long? = event.duration, position: Long = event.positionMs): ScrobbleResult {
            val e = entry(event, raw, media, duration, result, position, fromQueue)
            // În timpul redării păstrăm o singură linie pe titlu; pauzele, opririle și problemele se adaugă.
            if (event.isTick && !fromQueue) replaceLastTick(e) else appendLog(e)
            return result
        }

        if (!_enabled.value) return done(ScrobbleResult.DISABLED)
        if (ScrobbleRules.isIgnoredPackage(event.pkg)) return done(ScrobbleResult.IGNORED)
        val reportedDuration = event.duration
        if (reportedDuration != null && reportedDuration < ScrobbleRules.MIN_DURATION_MS) {
            return done(ScrobbleResult.TOO_SHORT)
        }
        val guesses = TitleParser.guesses(event.fields, event.app)
        var match = if (guesses.isNotEmpty() && matcher.isConfigured) matcher.match(guesses, reportedDuration) else null
        if (match == null && event.playId.isNotEmpty()) match = byPlay[event.playId]
        if (match == null) {
            // Nu aruncăm nimic: păstrăm vizionarea și o completăm când aflăm titlul (alt eveniment
            // al aceleiași vizionări) sau la o reîncercare.
            if (!fromQueue) keepUnresolved(event)
            // Netflix nu spune titlul: îl aflăm din „Activitate de vizionare” (AK Import), după minut.
            if (!fromQueue && !event.isTick && guesses.isEmpty() && NetflixActivity.isNetflix(event.pkg)) {
                NetflixRecentWorker.schedule(context)
            }
            return done(if (guesses.isEmpty()) ScrobbleResult.PENDING else ScrobbleResult.UNMATCHED)
        }
        if (event.playId.isNotEmpty()) {
            byPlay[event.playId] = match
            resolveSiblings(event.playId)
        }
        val media = match.media
        val duration = reportedDuration ?: match.runtimeMs
        val position = if (event.estimated) ScrobbleRules.mergeEstimated(event.positionMs, knownPosition(media)) else event.positionMs
        val progress = ScrobbleRules.progress(position, duration)

        if (ScrobbleRules.isFinished(progress, _watchedPercent.value / 100f)) {
            markWatchedLocally(media, event.at)
            return done(ScrobbleResult.WATCHED, media, duration, position)
        }
        // Pauza / oprirea se publică (în modul automat); punctele intermediare doar se salvează.
        publisher.setManualPosition(media, position, duration, publish = !event.isTick)
        return done(ScrobbleResult.SAVED, media, duration, position)
    }

    // ---------- Netflix: titlul din „Activitate de vizionare” ----------

    /**
     * Răspunsul AK Import RO / US: potrivim vizionările Netflix fără titlu după zi + minut și le
     * procesăm ca pe orice vizionare (minut salvat / văzut).
     */
    suspend fun applyNetflixRecent(result: NetflixRecentResult): Unit = mutex.withLock {
        var matched = 0
        if (result.status == "ok") {
            val solved = mutableSetOf<String>()
            for (e in readUnresolved()) {
                if (!NetflixActivity.isNetflix(e.pkg) || TitleParser.guesses(e.fields, e.app).isNotEmpty()) continue
                val item = NetflixActivity.match(e, result.items) ?: continue
                matched++
                val r = process(e.copy(fields = e.fields + NetflixActivity.fields(item)), fromQueue = true)
                if (r == ScrobbleResult.SAVED || r == ScrobbleResult.WATCHED || r == ScrobbleResult.IGNORED || r == ScrobbleResult.TOO_SHORT) {
                    solved += unresolvedKey(e)
                }
            }
            if (solved.isNotEmpty()) writeUnresolved(readUnresolved().filterNot { unresolvedKey(it) in solved })
        }
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        val line = when (result.status) {
            "ok" -> "$time · ${result.items.size} titluri citite, $matched vizionări recunoscute"
            "not_logged_in" -> "$time · ${result.message ?: "neconectat"}"
            else -> "$time · eroare: ${result.message ?: "-"}"
        }
        prefs.edit().putString(KEY_NETFLIX_STATUS + result.route.ifBlank { "?" }, line).apply()
    }

    /** Starea ultimei citiri din Netflix, pe fiecare AK Import (RO / US). */
    fun netflixStatus(): List<String> = listOf("RO", "US").mapNotNull { r ->
        prefs.getString(KEY_NETFLIX_STATUS + r, null)?.let { "AK Import $r: $it" }
    }

    private suspend fun knownPosition(media: MediaRef): Long? =
        trackingDao.allResume().first().firstOrNull { it.id == "$LOCAL_SOURCE|${media.itemKey}" }?.positionMs

    // ---------- Vizionări neidentificate încă ----------

    private fun readUnresolved(): List<ScrobbleEvent> = runCatching {
        prefs.getString(KEY_UNRESOLVED, null)?.let { json.decodeFromString(eventsSerializer, it) }
    }.getOrNull().orEmpty()

    private fun writeUnresolved(list: List<ScrobbleEvent>) {
        prefs.edit().putString(KEY_UNRESOLVED, json.encodeToString(eventsSerializer, list)).apply()
    }

    private fun unresolvedKey(e: ScrobbleEvent) = e.playId.ifEmpty { "${e.pkg}|${ScrobbleRules.rawLabel(e.fields)}" }

    /** Păstrăm ultimul eveniment al fiecărei vizionări neidentificate (cu minutul cel mai nou). */
    private fun keepUnresolved(event: ScrobbleEvent) {
        val key = unresolvedKey(event)
        val list = readUnresolved().filterNot { unresolvedKey(it) == key } + event
        val cutoff = event.at - TimeUnit.DAYS.toMillis(UNRESOLVED_DAYS)
        writeUnresolved(list.filter { it.at >= cutoff }.takeLast(MAX_UNRESOLVED))
    }

    /** Vizionarea a fost recunoscută: evenimentul ei păstrat nu mai e nevoie. */
    private fun resolveSiblings(playId: String) {
        val list = readUnresolved()
        val left = list.filterNot { it.playId == playId }
        if (left.size != list.size) writeUnresolved(left)
    }

    /**
     * Reîncercăm, cel mult o dată la 30 de minute, vizionările păstrate care au un text de titlu
     * (TMDb poate fi fost indisponibil). Cele fără niciun titlu așteaptă alt eveniment al vizionării.
     */
    private suspend fun retryUnresolvedIfDue(now: Long) {
        val last = prefs.getLong(KEY_LAST_RETRY, 0L)
        if (now - last < TimeUnit.MINUTES.toMillis(30)) return
        prefs.edit().putLong(KEY_LAST_RETRY, now).apply()
        if (!matcher.isConfigured) return
        val list = readUnresolved()
        if (list.any { NetflixActivity.isNetflix(it.pkg) && now - it.at < TimeUnit.DAYS.toMillis(2) }) {
            NetflixRecentWorker.schedule(context)
        }
        val solved = mutableSetOf<String>()
        for (e in list) {
            if (TitleParser.guesses(e.fields, e.app).isEmpty()) continue
            val r = process(e, fromQueue = true)
            if (r == ScrobbleResult.SAVED || r == ScrobbleResult.WATCHED || r == ScrobbleResult.IGNORED || r == ScrobbleResult.TOO_SHORT) {
                solved += unresolvedKey(e)
            }
        }
        if (solved.isNotEmpty()) writeUnresolved(readUnresolved().filterNot { unresolvedKey(it) in solved })
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

    private fun entry(
        event: ScrobbleEvent,
        raw: String,
        media: MediaRef?,
        duration: Long?,
        result: ScrobbleResult,
        position: Long,
        fromQueue: Boolean,
    ) = ScrobbleLogEntry(
        at = event.at,
        app = event.app,
        pkg = event.pkg,
        reason = if (fromQueue) "${event.reason} (reîncercat)" else event.reason,
        raw = raw + if (event.estimated) " [minut estimat]" else "",
        matched = label(media),
        positionMs = position,
        durationMs = duration,
        result = result.wire,
    )

    private fun appendLog(e: ScrobbleLogEntry) {
        writeLog((listOf(e) + _log.value).take(MAX_LOG))
    }

    /** În timpul redării păstrăm o singură linie „în redare” pentru titlul curent, actualizată. */
    private fun replaceLastTick(e: ScrobbleLogEntry) {
        val current = _log.value
        val first = current.firstOrNull()
        val rest = if (first != null && first.reason == "tick" && first.pkg == e.pkg && first.raw == e.raw) current.drop(1) else current
        writeLog((listOf(e) + rest).take(MAX_LOG))
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
        const val KEY_WATCHED_PCT = "watched_percent"
        const val KEY_UNRESOLVED = "unresolved"
        const val KEY_LAST_RETRY = "unresolved_last_retry"
        const val KEY_NETFLIX_STATUS = "netflix_status_"
        const val MAX_UNRESOLVED = 100
        const val UNRESOLVED_DAYS = 30L
        const val MAX_LOG = 50
    }
}
