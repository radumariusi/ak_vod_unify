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
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val prefs: PlaybackPreferences,
    private val resolver: TargetResolver,
) : ViewModel() {

    val settings: StateFlow<PlaybackSettings> = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackSettings())

    private val _installed = MutableStateFlow<Map<TargetApp, InstalledApp>>(emptyMap())
    val installed: StateFlow<Map<TargetApp, InstalledApp>> = _installed.asStateFlow()

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
}
