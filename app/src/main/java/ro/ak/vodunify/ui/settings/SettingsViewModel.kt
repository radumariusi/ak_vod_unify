package ro.ak.vodunify.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.prefs.CatalogPreferences
import ro.ak.vodunify.core.prefs.CatalogSettings
import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.connector.ConnectorRegistry
import ro.ak.vodunify.core.update.ApkInstaller
import ro.ak.vodunify.core.update.AvailableUpdate
import ro.ak.vodunify.core.update.UpdateRepository
import ro.ak.vodunify.core.update.UpdateState
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val updates: UpdateRepository,
    private val installer: ApkInstaller,
    registry: ConnectorRegistry,
    private val catalogPrefs: CatalogPreferences,
    private val catalog: CatalogRepository,
) : ViewModel() {

    val tmdbConfigured: Boolean = catalog.isConfigured

    val catalogSettings: StateFlow<CatalogSettings> = catalogPrefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogSettings())

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage: StateFlow<String?> = _cacheMessage.asStateFlow()

    fun setLanguage(language: ContentLanguage) {
        viewModelScope.launch { catalogPrefs.setLanguage(language) }
    }

    fun setMarket(market: Market) {
        viewModelScope.launch { catalogPrefs.setMarket(market) }
    }

    fun clearCatalogCache() {
        viewModelScope.launch {
            _cacheMessage.value = runCatching { catalog.clearCache() }
                .fold({ "Cache golit. Datele se reîncarcă din TMDb." }, { "Nu am putut goli cache-ul: ${it.message}" })
        }
    }

    val connectors: List<Connector> = registry.all

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    private var pending: AvailableUpdate? = null

    fun checkForUpdates() {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = runCatching { updates.checkLatest() }.fold(
                onSuccess = { found ->
                    pending = found
                    if (found == null) UpdateState.UpToDate else UpdateState.Available(found)
                },
                onFailure = { UpdateState.Error(it.message ?: "Eroare necunoscută") },
            )
        }
    }

    fun installPermissionIntent(): Intent = installer.installPermissionIntent()

    fun downloadAndInstall() {
        val target = pending ?: return
        if (!installer.canInstall()) {
            _update.value = UpdateState.NeedsInstallPermission
            return
        }
        _update.value = UpdateState.Downloading(target, 0f)
        viewModelScope.launch {
            runCatching {
                val apk = updates.download(target) { p -> _update.value = UpdateState.Downloading(target, p) }
                _update.value = UpdateState.Installing
                installer.install(apk)
            }.onFailure { _update.value = UpdateState.Error(it.message ?: "Eroare la instalare") }
        }
    }

    /** Apelat după revenirea din ecranul de permisiuni. */
    fun onReturnedFromPermission() {
        val target = pending ?: return
        _update.value = if (installer.canInstall()) UpdateState.Available(target) else UpdateState.NeedsInstallPermission
    }
}
