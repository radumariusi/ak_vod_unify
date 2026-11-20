package ro.ak.vodunify.ui.watch

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.playback.InstalledApp
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.PlaybackRequest
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
    /** Pagina JustWatch/TMDb cu toate ofertele din piața de redare. */
    val offersLink: String? = null,
    /** Lansare care așteaptă confirmarea „Deschide acum / Așteaptă sincronizarea”. */
    val confirm: ResolvedLaunch? = null,
    val syncing: Boolean = false,
)

@HiltViewModel
class WatchChooserViewModel @Inject constructor(
    private val resolver: TargetResolver,
    private val prefs: PlaybackPreferences,
    private val resumeLookup: ResumeLookup,
) : ViewModel() {

    private val _state = MutableStateFlow<ChooserState?>(null)
    val state: StateFlow<ChooserState?> = _state.asStateFlow()

    /** Mesaj scurt pentru Toast (consumat de UI). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loadJob: Job? = null

    fun open(request: PlaybackRequest, availability: List<MarketAvailability>) {
        _state.value = ChooserState(request)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val settings = prefs.settings.first()
            val (installed, launches) = withContext(Dispatchers.IO) {
                val apps = resolver.installedApps()
                apps to resolveAll(apps.values, request)
            }
            val options = WatchOptionsBuilder.build(availability, settings, installed.keys)
            val resume = runCatching { resumeLookup.current(request) }.getOrDefault(ResumeInfo.Manual)
            _state.update {
                it?.copy(
                    loading = false,
                    options = options,
                    launches = launches,
                    resume = resume,
                    offersLink = availability.firstOrNull { a -> a.market == settings.playbackMarket }?.link,
                )
            }
        }
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
            app == null -> openOffers(s)
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

    private fun start(target: ResolvedLaunch) {
        val s = _state.value ?: return
        if (resolver.launch(target)) {
            val pos = s.resume.positionMs?.takeIf { s.resume.hasPosition }
            if (pos != null) say("În ${target.installed.app.displayName}, reia de la ${formatPosition(pos)}")
            _state.value = null
        } else {
            _state.value = s.copy(confirm = null)
            say("${target.installed.label} nu a putut fi deschis")
        }
    }

    private fun openOffers(s: ChooserState) {
        val link = s.offersLink
        if (link != null && resolver.tryStart(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        say("Serviciul nu are încă integrare în aplicație")
    }

    private fun resolveAll(apps: Collection<InstalledApp>, request: PlaybackRequest): Map<TargetApp, ResolvedLaunch> =
        apps.mapNotNull { a -> resolver.resolve(a, request)?.let { a.app to it } }.toMap()

    private fun say(text: String) {
        _message.value = text
    }
}
