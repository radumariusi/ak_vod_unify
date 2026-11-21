package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.backup.BackupRepository
import ro.ak.vodunify.core.backup.DeviceCode
import ro.ak.vodunify.core.backup.DriveFile
import javax.inject.Inject

@HiltViewModel
class BackupViewModel @Inject constructor(private val repo: BackupRepository) : ViewModel() {
    val configured: Boolean get() = repo.auth.isConfigured
    val connected: StateFlow<Boolean> = repo.auth.connected
    val status: StateFlow<String?> = repo.status
    val auto: StateFlow<Boolean> = repo.auto

    private val _code = MutableStateFlow<DeviceCode?>(null)
    val code: StateFlow<DeviceCode?> = _code.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _files = MutableStateFlow<List<DriveFile>?>(null)
    val files: StateFlow<List<DriveFile>?> = _files.asStateFlow()

    /** Copia aleasă pentru restaurare (așteaptă confirmarea). */
    private val _confirm = MutableStateFlow<DriveFile?>(null)
    val confirm: StateFlow<DriveFile?> = _confirm.asStateFlow()

    private var login: Job? = null

    fun connect() {
        login?.cancel()
        login = viewModelScope.launch {
            try {
                val code = repo.auth.startDeviceFlow()
                _code.value = code
                repo.auth.awaitAuthorization(code)
                repo.onConnected()
                _message.value = "Conectat la Google Drive"
                refreshList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Conectare eșuată: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _code.value = null
            }
        }
    }

    fun cancelConnect() {
        login?.cancel()
        _code.value = null
    }

    fun disconnect() {
        repo.disconnect()
        _files.value = null
    }

    fun setAuto(value: Boolean) = repo.setAuto(value)

    fun backupNow() = run("Backup salvat pe Google Drive") {
        repo.backupNow()
        _files.value = repo.list()
    }

    fun refreshList() = run(null) { _files.value = repo.list() }

    fun askRestore(file: DriveFile) {
        _confirm.value = file
    }

    fun cancelRestore() {
        _confirm.value = null
    }

    fun restore() {
        val file = _confirm.value ?: return
        _confirm.value = null
        run(null) {
            repo.restore(file)
            repo.restart()
        }
    }

    private fun run(done: String?, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                if (done != null) _message.value = done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Eroare: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }
}
