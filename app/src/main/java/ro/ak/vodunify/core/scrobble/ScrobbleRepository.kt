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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
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
    private val _unresolved = MutableStateFlow(readUnresolved().size)
    val unresolved = _unresolved.asStateFlow()
    private val _netflix = MutableStateFlow(readNetflixStatus())
    val netflix = _netflix.asStateFlow()

    private val proofSerializer = MapSerializer(String.serializer(), AppProof.serializer())
    private val _proofs = MutableStateFlow(readProofs())

    /**
     * Dovada, pe fiecare aplicație (pachet), că „Continuă vizionarea” merge: ultima vizionare recunoscută
     * și metoda prin care am aflat titlul. Se completează singură, din vizionările reale.
     */
    val proofs: StateFlow<Map<String, AppProof>> = _proofs.asStateFlow()

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
    /**
     * Procesează un eveniment. Aruncă excepție doar pentru erori trecătoare (rețea), ca
     * scrobblerul să-l retrimită mai târziu.
     */
    suspend fun handle(event: ScrobbleEvent): ScrobbleResult = mutex.withLock {
        val result = process(event, fromQueue = false)
        val now = System.currentTimeMillis()
        if (_unresolved.value > 0 && now - prefs.getLong(KEY_LAST_RETRY, 0L) >= TimeUnit.MINUTES.toMillis(30)) {
            prefs.edit().putLong(KEY_LAST_RETRY, now).apply()
            ScrobbleRetryWorker.schedule(context)
        }
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
            if (media != null && (result == ScrobbleResult.SAVED || result == ScrobbleResult.WATCHED)) {
                recordProof(original, extra.isNotEmpty(), media)
            }
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
            // Netflix nu spune titlul: îl aflăm din activitatea profilului Netflix.
            if (!fromQueue && !event.isTick && NetflixActivity.isNetflix(event.pkg)) {
                NetflixRecentWorker.schedule(context)
            }
            return done(if (guesses.isEmpty()) ScrobbleResult.PENDING else ScrobbleResult.UNMATCHED)
        }
        if (event.playId.isNotEmpty()) {
            byPlay[event.playId] = match
            resolveSiblings(event.playId)
        }
        val media = match.media
        // Durata citită de pe ecran poate fi greșită (ex. ora din zi luată drept minut): dacă e mult
        // sub durata din TMDb, ne luăm după TMDb, ca să nu trecem ca văzut un episod abia început.
        val runtime = match.runtimeMs
        val duration = when {
            reportedDuration == null -> runtime
            runtime != null && event.fields["title_source"] == "screen" && reportedDuration < runtime * 6 / 10 -> runtime
            else -> reportedDuration
        }
        val position = if (event.estimated && !fromQueue) ScrobbleRules.mergeEstimated(event.positionMs, knownPosition(media)) else event.positionMs
        val progress = ScrobbleRules.progress(position, duration)

        if (ScrobbleRules.isFinished(progress, _watchedPercent.value / 100f)) {
            markWatchedLocally(media, event.at)
            return done(ScrobbleResult.WATCHED, media, duration, position)
        }
        // Pauza / oprirea se publică (în modul automat); punctele intermediare doar se salvează.
        publisher.setManualPosition(media, position, duration, publish = !event.isTick && !fromQueue, appPackage = event.pkg, recordedAt = event.at)
        return done(ScrobbleResult.SAVED, media, duration, position)
    }

    // ---------- Netflix: titlul din „Activitate de vizionare” ----------

    /**
     * Activitatea profilului Netflix: potrivim vizionările Netflix fără titlu după zi + minut și le
     * procesăm ca pe orice vizionare (minut salvat / văzut).
     */
    suspend fun applyNetflixRecent(result: NetflixRecentResult): Unit = mutex.withLock {
        var matched = 0
        if (result.status == "ok") {
            for (e in readUnresolved().sortedBy { it.at }) {
                if (!NetflixActivity.isNetflix(e.pkg)) continue
                val item = NetflixActivity.match(e, result.items) ?: continue
                val bookmark = item.bookmark?.takeIf { it > 0 && e.estimated }?.times(1_000)
                val recovered = e.copy(
                    fields = NetflixActivity.fields(item),
                    durationMs = item.duration?.takeIf { it > 0 }?.times(1_000) ?: e.durationMs,
                    positionMs = bookmark ?: e.positionMs,
                    estimated = bookmark == null && e.estimated,
                )
                val r = process(recovered, fromQueue = true)
                if (r == ScrobbleResult.SAVED || r == ScrobbleResult.WATCHED || r == ScrobbleResult.IGNORED || r == ScrobbleResult.TOO_SHORT) {
                    matched++
                    writeUnresolved(readUnresolved().filterNot { unresolvedKey(it) == unresolvedKey(e) })
                } else {
                    keepUnresolved(recovered)
                }
            }
        }
        val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date())
        val line = when (result.status) {
            "ok" -> "$time · ${result.items.size} titluri citite, $matched vizionări recunoscute"
            "not_logged_in" -> "$time · ${result.message ?: "neconectat"}"
            else -> "$time · eroare: ${result.message ?: "-"}"
        }
        prefs.edit().putString(KEY_NETFLIX_STATUS + result.route.ifBlank { "?" }, line).apply()
        _netflix.value = readNetflixStatus()
    }

    private fun readNetflixStatus(): List<String> = listOfNotNull(prefs.getString(KEY_NETFLIX_STATUS + "RO", null))

    private suspend fun knownPosition(media: MediaRef): Long? =
        trackingDao.allResume().first().firstOrNull { it.id == "$LOCAL_SOURCE|${media.itemKey}" }?.positionMs

    // ---------- Vizionări neidentificate încă ----------

    private fun readUnresolved(): List<ScrobbleEvent> = runCatching {
        prefs.getString(KEY_UNRESOLVED, null)?.let { json.decodeFromString(eventsSerializer, it) }
    }.getOrNull().orEmpty()

    private fun writeUnresolved(list: List<ScrobbleEvent>) {
        prefs.edit().putString(KEY_UNRESOLVED, json.encodeToString(eventsSerializer, list)).apply()
        _unresolved.value = list.size
    }

    private fun unresolvedKey(e: ScrobbleEvent) = e.playId.ifEmpty { "${e.pkg}|${e.at}" }

    /** Păstrăm ultimul eveniment al fiecărei vizionări neidentificate (cu minutul cel mai nou). */
    private fun keepUnresolved(event: ScrobbleEvent) {
        val key = unresolvedKey(event)
        val pending = readUnresolved()
        if (pending.any { unresolvedKey(it) == key && it.at > event.at }) return
        val list = pending.filterNot { unresolvedKey(it) == key } + event
        writeUnresolved(list.sortedBy { it.at }.takeLast(MAX_UNRESOLVED))
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
    suspend fun retryUnresolved() {
        if (!matcher.isConfigured) return
        val now = System.currentTimeMillis()
        val list = mutex.withLock {
            prefs.edit().putLong(KEY_LAST_RETRY, now).apply()
            readUnresolved()
        }
        if (list.any { NetflixActivity.isNetflix(it.pkg) && now - it.at < TimeUnit.DAYS.toMillis(2) }) {
            NetflixRecentWorker.schedule(context)
        }
        for (e in list.sortedByDescending { it.at }) {
            if (TitleParser.guesses(e.fields, e.app).isEmpty()) continue
            mutex.withLock {
                if (e !in readUnresolved()) return@withLock
                val r = process(e, fromQueue = true)
                if (r == ScrobbleResult.SAVED || r == ScrobbleResult.WATCHED || r == ScrobbleResult.IGNORED || r == ScrobbleResult.TOO_SHORT) {
                    writeUnresolved(readUnresolved().filterNot { unresolvedKey(it) == unresolvedKey(e) })
                }
            }
        }
    }

    private suspend fun markWatchedLocally(media: MediaRef, at: Long) {
        val key = "watched_${media.itemKey}"
        val last = prefs.getLong(key, 0L)
        trackingDao.deleteResumeByIdBefore("$LOCAL_SOURCE|${media.itemKey}", at)
        if (kotlin.math.abs(at - last) < TimeUnit.HOURS.toMillis(6)) return
        val event = WatchEvent(media, Instant.ofEpochMilli(at), LOCAL_SOURCE)
        trackingDao.insertHistory(listOf(TrackingEntities.history(event)))
        if (at >= last) {
            if (media.type != MediaType.EPISODE) trackingDao.upsertWatched(listOf(TrackingEntities.watched(event)))
            prefs.edit().putLong(key, at).apply()
        }
    }

    private fun recordProof(event: ScrobbleEvent, fromServiceCode: Boolean, media: MediaRef) {
        val proof = AppProof(
            pkg = event.pkg,
            app = event.app,
            at = event.at,
            method = AppProof.method(event.fields["title_source"], fromServiceCode, event.estimated),
            title = label(media),
        )
        val current = _proofs.value[event.pkg]
        // O dată la 10 minute e destul (punctele intermediare vin des).
        if (current != null && current.method == proof.method && proof.at - current.at < TimeUnit.MINUTES.toMillis(10)) return
        val next = _proofs.value + (event.pkg to proof)
        _proofs.value = next
        prefs.edit().putString(KEY_PROOFS, json.encodeToString(proofSerializer, next)).apply()
    }

    private fun readProofs(): Map<String, AppProof> = runCatching {
        prefs.getString(KEY_PROOFS, null)?.let { json.decodeFromString(proofSerializer, it) }
    }.getOrNull().orEmpty()

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
        const val KEY_PROOFS = "proofs"
        const val MAX_UNRESOLVED = 2_000
        val eventsSerializer = ListSerializer(ScrobbleEvent.serializer())
        const val MAX_LOG = 50
    }
}

/** O vizionare recunoscută dintr-o aplicație: dovada că metoda merge (vezi [ScrobbleRepository.proofs]). */
@Serializable
data class AppProof(
    val pkg: String,
    val app: String,
    val at: Long,
    val method: String,
    val title: String? = null,
) {
    companion object {
        fun method(titleSource: String?, fromServiceCode: Boolean, estimated: Boolean): String {
            val base = when {
                titleSource == "netflix_activity" -> "Activitatea Netflix"
                fromServiceCode -> "Sesiune media + codul titlului din serviciu"
                titleSource == "screen" -> "Cititor de ecran"
                titleSource == "notification" -> "Notificare"
                else -> "Sesiune media"
            }
            return if (estimated) "$base, minut estimat" else base
        }
    }
}
