package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.update.ApkInstaller
import ro.ak.vodunify.core.update.CompanionApp
import ro.ak.vodunify.core.update.CompanionStatus
import ro.ak.vodunify.core.update.UpdateRepository
import javax.inject.Inject

data class CompanionsUi(
    val loading: Boolean = false,
    val items: List<CompanionStatus> = emptyList(),
    /** Aplicația care se descarcă acum și progresul ei. */
    val downloading: CompanionApp? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val needsPermission: Boolean = false,
)

/** Instalează / actualizează AK Scrobbler din ultimul release. */
@HiltViewModel
class CompanionsViewModel @Inject constructor(
    private val updates: UpdateRepository,
    private val installer: ApkInstaller,
) : ViewModel() {
    private val _state = MutableStateFlow(CompanionsUi())
    val state: StateFlow<CompanionsUi> = _state.asStateFlow()

    fun check() {
        if (_state.value.loading) return
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            _state.value = runCatching { updates.companions() }.fold(
                { _state.value.copy(loading = false, items = it) },
                { _state.value.copy(loading = false, message = "Nu pot verifica: ${it.message}") },
            )
        }
    }

    /** După revenirea în aplicație (instalare terminată sau permisiune dată). */
    fun refreshInstalled() {
        val s = _state.value
        if (s.items.isEmpty()) return
        _state.value = s.copy(
            items = s.items.map { it.copy(installedCode = updates.installedVersionCode(it.companion.packageName)) },
            needsPermission = s.needsPermission && !installer.canInstall(),
        )
    }

    fun permissionIntent() = installer.installPermissionIntent()

    fun install(item: CompanionStatus) {
        if (_state.value.downloading != null) return
        if (!installer.canInstall()) {
            _state.value = _state.value.copy(needsPermission = true)
            return
        }
        _state.value = _state.value.copy(downloading = item.companion, progress = 0f, message = null)
        viewModelScope.launch {
            runCatching {
                val apk = updates.downloadCompanion(item) { p -> _state.value = _state.value.copy(progress = p) }
                installer.install(apk, expectedPackage = item.companion.packageName)
            }.onSuccess {
                _state.value = _state.value.copy(downloading = null, message = "Confirmă instalarea ${item.companion.label} în fereastra sistemului.")
            }.onFailure {
                _state.value = _state.value.copy(downloading = null, message = "Eroare la ${item.companion.label}: ${it.message}")
            }
        }
    }
}
