package ro.ak.vodunify.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.scrobble.NetflixRecentWorker
import ro.ak.vodunify.core.scrobble.ScrobbleLogEntry
import ro.ak.vodunify.core.scrobble.ScrobbleRepository
import javax.inject.Inject

@HiltViewModel
class ScrobblerSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScrobbleRepository,
) : ViewModel() {
    val log: StateFlow<List<ScrobbleLogEntry>> = repository.log
    val enabled: StateFlow<Boolean> = repository.enabled
    val watchedPercent: StateFlow<Int> = repository.watchedPercent

    val unresolved = repository.unresolved
    val netflix = repository.netflix
    private val _retryStatus = MutableStateFlow<String?>(null)
    val retryStatus = _retryStatus.asStateFlow()
    private var retrying = false

    fun setWatchedPercent(value: Int) = repository.setWatchedPercent(value)

    private val _installed = MutableStateFlow(repository.isScrobblerInstalled())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    fun refresh() {
        _installed.value = repository.isScrobblerInstalled()
    }

    fun retryIdentification() {
        if (retrying) return
        retrying = true
        _retryStatus.value = "Reîncerc potrivirea cu TMDb și verific activitatea Netflix."
        NetflixRecentWorker.retryNow(context)
        viewModelScope.launch {
            try {
                repository.retryUnresolved()
                _retryStatus.value = "Verificarea TMDb s-a încheiat. Activitatea Netflix se procesează în fundal."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _retryStatus.value = "Nu am putut termina verificarea TMDb: ${e.message}"
            } finally {
                retrying = false
            }
        }
    }

    fun setEnabled(value: Boolean) = repository.setEnabled(value)

    fun clearLog() = repository.clearLog()

    fun launchIntent(): Intent? {
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(repository.scrobblerPackage) ?: pm.getLaunchIntentForPackage(repository.scrobblerPackage))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
