package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.playback.InstalledApp
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
    scrobbles: ScrobbleRepository,
) : ViewModel() {

    val settings: StateFlow<PlaybackSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackSettings())

    private val _installed = MutableStateFlow<Map<TargetApp, InstalledApp>>(emptyMap())
    val installed: StateFlow<Map<TargetApp, InstalledApp>> = _installed.asStateFlow()

    /** Ce a prins AK Scrobbler din fiecare aplicație (pe pachet). */
    val proofs: StateFlow<Map<String, AppProof>> = scrobbles.proofs

    init {
        refreshInstalled()
    }

    fun refreshInstalled() {
        viewModelScope.launch { _installed.value = withContext(Dispatchers.IO) { resolver.installedApps() } }
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

    /** Dovada pentru o aplicație: după pachetul instalat, pachetele cunoscute sau numele. */
    fun proofFor(app: TargetApp, proofs: Map<String, AppProof>, installed: Map<TargetApp, InstalledApp>): AppProof? =
        proofs.values.filter { p ->
            p.pkg == installed[app]?.packageName || TargetApp.byPackage(p.pkg) == app || app.matchesLabel(p.app)
        }.maxByOrNull { it.at }

    /** Aplicații văzute de AK Scrobbler care nu sunt în listă. */
    fun unknownProofs(proofs: Map<String, AppProof>, installed: Map<TargetApp, InstalledApp>): List<AppProof> =
        proofs.values.filter { p -> TargetApp.entries.none { proofFor(it, mapOf(p.pkg to p), installed) != null } }
            .sortedByDescending { it.at }
}
