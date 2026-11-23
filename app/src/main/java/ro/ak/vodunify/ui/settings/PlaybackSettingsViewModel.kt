package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.playback.InstalledApp
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.playback.AppRole
import ro.ak.vodunify.core.playback.ServiceLogos
import ro.ak.vodunify.core.playback.PlaybackPreferences
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.playback.TargetResolver
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.scrobble.AppProof
import ro.ak.vodunify.core.scrobble.ScrobbleRepository
import javax.inject.Inject

/** Folosit de „Redare” și „Aplicații și servicii”. */
@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val prefs: PlaybackPreferences,
    private val resolver: TargetResolver,
    private val logos: ServiceLogos,
    scrobbles: ScrobbleRepository,
) : ViewModel() {

    /** Crește la fiecare aplicație adăugată / eliminată (numele și listele se recitesc). */
    val registryVersion: StateFlow<Int> = AppRegistry.version

    private val _launchable = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    /** Toate aplicațiile din launcher (pachet, nume), pentru „Adaugă aplicație”. */
    val launchable: StateFlow<List<Pair<String, String>>> = _launchable.asStateFlow()

    private val _providers = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    /** Furnizorii TMDb (id, nume), pentru „Serviciu de streaming nou”. */
    val providers: StateFlow<List<Pair<Int, String>>> = _providers.asStateFlow()

    fun loadLaunchable() {
        viewModelScope.launch { _launchable.value = withContext(Dispatchers.IO) { resolver.launchableApps() } }
    }

    fun loadProviders() {
        viewModelScope.launch { _providers.value = runCatching { logos.providers() }.getOrDefault(emptyList()) }
    }

    /** Leagă o intrare din listă de pachetul ales de utilizator. */
    fun assign(app: TargetApp, pkg: String) {
        AppRegistry.assign(app, pkg)
        refreshInstalled()
    }

    /** Aplicație nouă (player tip Stremio sau IPTV), cu numele din launcher. False = nu mai sunt locuri. */
    fun addPlayer(role: AppRole, pkg: String, label: String): Boolean {
        val slot = TargetApp.freeSlot(role) ?: return false
        AppRegistry.assign(slot, pkg, label)
        refreshInstalled()
        return true
    }

    /** Serviciu de streaming nou: furnizorul TMDb ales, deschis în aplicația [pkg]. False = nu mai sunt locuri. */
    fun addService(providerId: Int, name: String, pkg: String): Boolean {
        val slot = StreamingService.freeSlot() ?: return false
        AppRegistry.addService(slot, providerId, name, pkg)
        refreshInstalled()
        return true
    }

    fun remove(app: TargetApp) {
        AppRegistry.remove(app)
        refreshInstalled()
    }

    fun restore(app: TargetApp) {
        AppRegistry.restore(app)
        refreshInstalled()
    }

    val settings: StateFlow<PlaybackSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackSettings())

    private val _installed = MutableStateFlow<Map<TargetApp, InstalledApp>>(emptyMap())
    val installed: StateFlow<Map<TargetApp, InstalledApp>> = _installed.asStateFlow()
    private var refreshJob: Job? = null

    /** Ce a prins AK Scrobbler din fiecare aplicație (pe pachet). */
    val proofs: StateFlow<Map<String, AppProof>> = scrobbles.proofs

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { _installed.value = withContext(Dispatchers.IO) { resolver.installedApps() } }
    }

    fun setPlaybackMarket(market: Market) {
        viewModelScope.launch { prefs.setPlaybackMarket(market) }
    }

    fun setPreferred(service: StreamingService, app: TargetApp) {
        viewModelScope.launch { prefs.setPreferredApp(service, app) }
    }

    fun setServiceMarket(service: StreamingService, market: Market) {
        viewModelScope.launch { prefs.setServiceMarket(service, market) }
    }

    fun setDefaultPlayer(app: TargetApp?) {
        viewModelScope.launch { prefs.setDefaultPlayer(app) }
    }

    /** Atribuie o dovadă unei singure aplicații. Pachetul identifică mai precis decât numele din launcher. */
    fun groupProofs(proofs: Collection<AppProof>, installed: Map<TargetApp, InstalledApp>): Map<TargetApp?, List<AppProof>> {
        val active = TargetApp.active
        val byPackage = installed.values.associate { it.packageName to it.app }
        return proofs.groupBy { proof ->
            byPackage[proof.pkg]
                ?: TargetApp.byPackage(proof.pkg)?.takeIf { it in active }
                ?: active.maxByOrNull { it.labelScore(proof.app) }?.takeIf { it.matchesLabel(proof.app) }
        }
    }
}
