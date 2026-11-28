package ro.ak.vodunify.ui.watch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.connector.ExternalIds
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.publish.PublishMode
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.publish.summary
import ro.ak.vodunify.core.playback.InstalledApp
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.PlaybackRequest
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.ResolvedLaunch
import ro.ak.vodunify.core.playback.ResumeInfo
import ro.ak.vodunify.core.playback.ResumeLookup
import ro.ak.vodunify.core.playback.ResumeState
import ro.ak.vodunify.core.playback.ServiceOption
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver
import ro.ak.vodunify.core.playback.WatchOptions
import ro.ak.vodunify.core.playback.WatchOptionsBuilder
import ro.ak.vodunify.core.playback.formatPosition
import javax.inject.Inject

data class ChooserState(
    val request: PlaybackRequest,
    val loading: Boolean = true,
    val options: WatchOptions? = null,
    /** Ținta validată pentru fiecare aplicație instalată. */
    val launches: Map<TargetApp, ResolvedLaunch> = emptyMap(),
    val resume: ResumeInfo = ResumeInfo.Manual,
    /** Pornire directă („Vezi”): nu arătăm lista decât dacă nu avem ce porni singuri. */
    val direct: Boolean = false,
    /** Lansare care așteaptă confirmarea „Deschide acum / Așteaptă sincronizarea”. */
    val confirm: ResolvedLaunch? = null,
    val syncing: Boolean = false,
    /** Modul de publicare al profilului activ. */
    val publishMode: PublishMode = PublishMode.ON_DEMAND,
    /** Dialogul „Setează minutul” e deschis. */
    val editingMinute: Boolean = false,
    val publishing: Boolean = false,
    val appsUnavailable: Boolean = false,
) {
    /** Poți seta minutul / publica doar pentru un film sau un episod. */
    val canSetMinute: Boolean get() = request.type != PlaybackRequest.Type.SHOW
}

@HiltViewModel
class WatchChooserViewModel @Inject constructor(
    private val resolver: TargetResolver,
    private val launchHints: ro.ak.vodunify.core.scrobble.LaunchHints,
    private val prefs: PlaybackPreferences,
    private val resumeLookup: ResumeLookup,
    private val publisher: PublishRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<ChooserState?>(null)
    val state: StateFlow<ChooserState?> = _state.asStateFlow()

    /** Mesaj scurt pentru Toast (consumat de UI). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loadJob: Job? = null

    private data class AvailableApps(val options: WatchOptions, val launches: Map<TargetApp, ResolvedLaunch>)

    /** Pornește direct pe serviciul tău (sau playerul standard); lista apare doar dacă nu se poate. */
    fun play(request: PlaybackRequest, availability: List<MarketAvailability>) = open(request, availability, direct = true)

    fun open(request: PlaybackRequest, availability: List<MarketAvailability>, direct: Boolean = false) {
        loadJob?.cancel()
        _state.value = ChooserState(request, direct = direct)
        loadJob = viewModelScope.launch {
            val appsTask = async(Dispatchers.IO) {
                readOrNull {
                    val settings = withTimeoutOrNull(750) { readOrNull { prefs.settings.first() } } ?: PlaybackSettings()
                    val apps = resolver.installedApps()
                    val options = WatchOptionsBuilder.build(availability, settings, apps.keys)
                    val targets = (options.services.mapNotNull { it.app } + options.players).distinct()
                    AvailableApps(options, resolveAll(targets.mapNotNull(apps::get), request))
                }
            }
            val resumeTask = async(Dispatchers.Default) { readOrNull { resumeLookup.current(request) } }
            val modeTask = async { readOrNull { publisher.settings.first().active.mode } }

            val apps = appsTask.awaitFor(2_500)
            val options = apps?.options ?: WatchOptionsBuilder.build(emptyList(), PlaybackSettings(), emptySet())
            val launches = apps?.launches.orEmpty()
            // Lista este gata imediat ce știm aplicațiile; minutul și profilul se completează separat.
            val initialResume = if (direct) resumeTask.awaitFor(750) ?: ResumeInfo.Manual else ResumeInfo.Manual
            _state.update {
                it?.copy(
                    loading = false,
                    options = options,
                    launches = launches,
                    resume = initialResume,
                    appsUnavailable = apps == null,
                )
            }
            if (direct) {
                val service = options.recommendedService
                val serviceApp = service?.app
                val player = options.recommendedPlayer
                // Continui standard în aplicația în care ai văzut ultima dată (dacă e printre variante).
                val last = initialResume.appPackage?.let { pkg ->
                    launches.entries.firstOrNull { it.value.installed.packageName == pkg }?.key
                }
                val lastService = last?.let { app -> options.services.firstOrNull { it.app == app && it.installed } }
                when {
                    last != null && lastService != null && launches.containsKey(last) -> chooseService(lastService)
                    last != null && last in options.players && launches.containsKey(last) -> choosePlayer(last)
                    service != null && serviceApp != null && launches.containsKey(serviceApp) -> chooseService(service)
                    player != null && launches.containsKey(player) -> choosePlayer(player)
                    else -> _state.update { it?.copy(direct = false) }
                }
            }
            launch {
                resumeTask.awaitFor(1_500)?.let { resume ->
                    _state.update { it?.copy(resume = resume) }
                }
            }
            launch {
                modeTask.awaitFor(1_500)?.let { mode ->
                    _state.update { it?.copy(publishMode = mode) }
                }
            }
        }
    }

    private suspend fun <T> Deferred<T?>.awaitFor(millis: Long): T? =
        withTimeoutOrNull(millis) { await() }.also { if (!isCompleted) cancel() }

    private suspend fun <T> readOrNull(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    fun dismiss() {
        loadJob?.cancel()
        _state.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Apăsare pe un serviciu oficial. */
    fun chooseService(option: ServiceOption) {
        val s = _state.value ?: return
        val app = option.app
        when {
            app == null -> say("${option.service.displayName} nu are încă aplicație în AK VOD Unify")
            option.installed -> s.launches[app]?.let(::choose) ?: say("${app.displayName} nu poate fi pornit")
            else -> {
                val store = resolver.storeIntent(app)
                if (store != null && resolver.tryStart(store)) return
                say("${app.displayName} nu e instalat. Instalează-l din magazinul dispozitivului.")
            }
        }
    }

    fun choosePlayer(app: TargetApp) {
        _state.value?.launches?.get(app)?.let(::choose) ?: say("${app.displayName} nu poate fi pornit")
    }

    /** Dacă avem o poziție nesigură, întâi întrebăm; altfel deschidem direct. */
    fun choose(target: ResolvedLaunch) {
        val s = _state.value ?: return
        val r = s.resume
        if (r.hasPosition && (r.state == ResumeState.PENDING || r.state == ResumeState.ESTIMATED)) {
            _state.value = s.copy(confirm = target)
        } else {
            start(target)
        }
    }

    fun confirmOpenNow() {
        _state.value?.confirm?.let(::start)
    }

    fun cancelConfirm() {
        _state.update { it?.copy(confirm = null) }
    }

    fun waitForSync() {
        val s = _state.value ?: return
        if (s.syncing) return
        _state.value = s.copy(syncing = true)
        viewModelScope.launch {
            val fresh = withTimeoutOrNull(20_000) { runCatching { resumeLookup.sync(s.request) }.getOrNull() }
            _state.update { cur -> cur?.copy(syncing = false, resume = fresh ?: cur.resume) }
            when {
                fresh == null -> say("Sincronizarea nu a răspuns. Poți deschide oricum.")
                fresh.state == ResumeState.PENDING -> say("Încă în așteptare. Poți deschide oricum.")
                else -> say("Sincronizat: ${fresh.state.label}")
            }
        }
    }

    // ---------- Setează minutul / Publică acum ----------

    fun openSetMinute() {
        _state.update { it?.copy(editingMinute = true) }
    }

    fun closeSetMinute() {
        _state.update { it?.copy(editingMinute = false) }
    }

    /** Salvează poziția ta (0 = o șterge); în modul automat o și publică. */
    fun saveMinute(positionMs: Long) {
        val s = _state.value ?: return
        val durationMs = s.request.runtimeMinutes?.takeIf { it > 0 }?.let { it * 60_000L }
        _state.value = s.copy(editingMinute = false, publishing = true)
        viewModelScope.launch {
            val text = try {
                val outcomes = publisher.setManualPosition(mediaRef(s.request), positionMs, durationMs)
                when {
                    positionMs <= 0 -> "Poziția locală a fost ștearsă"
                    outcomes.isEmpty() -> "Salvat: ${formatPosition(positionMs)}" +
                        if (durationMs == null) " (durata e necunoscută, deci nu se poate publica ca procent)" else ""
                    else -> "Salvat ${formatPosition(positionMs)} · ${outcomes.summary()}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
            refreshResume()
            say(text)
        }
    }

    fun publishNow() {
        val s = _state.value ?: return
        if (s.publishing) return
        _state.value = s.copy(publishing = true)
        viewModelScope.launch {
            val text = try {
                publisher.publishItem(mediaRef(s.request), manual = true).summary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            }
            refreshResume()
            say(text)
        }
    }

    private suspend fun refreshResume() {
        val s = _state.value ?: return
        val resume = runCatching { resumeLookup.current(s.request) }.getOrDefault(s.resume)
        _state.update { it?.copy(resume = resume, publishing = false) }
    }

    private fun mediaRef(r: PlaybackRequest) = MediaRef(
        type = when (r.type) {
            PlaybackRequest.Type.MOVIE -> MediaType.MOVIE
            PlaybackRequest.Type.SHOW -> MediaType.SHOW
            PlaybackRequest.Type.EPISODE -> MediaType.EPISODE
        },
        ids = ExternalIds(tmdb = r.tmdbId, imdb = r.imdbId),
        title = r.title,
        year = r.year,
        season = r.season,
        episode = r.episode,
        episodeTitle = r.episodeTitle,
    )

    private fun start(target: ResolvedLaunch) {
        val s = _state.value ?: return
        if (resolver.launch(target)) {
            launchHints.record(target.installed.packageName, mediaRef(s.request), s.request.runtimeMinutes, target.precision)
            val pos = s.resume.positionMs?.takeIf { s.resume.hasPosition }
            if (pos != null) say("În ${target.installed.app.displayName}, reia de la ${formatPosition(pos)}")
            _state.value = null
        } else {
            _state.value = s.copy(confirm = null, direct = false)
            say("${target.installed.label} nu a putut fi deschis")
        }
    }

    private fun resolveAll(apps: Collection<InstalledApp>, request: PlaybackRequest): Map<TargetApp, ResolvedLaunch> =
        apps.mapNotNull { a -> runCatching { resolver.resolve(a, request) }.getOrNull()?.let { a.app to it } }.toMap()

    private fun say(text: String) {
        _message.value = text
    }
}
