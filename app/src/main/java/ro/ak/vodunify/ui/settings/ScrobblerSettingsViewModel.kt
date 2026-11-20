package ro.ak.vodunify.ui.settings

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _unresolved = MutableStateFlow(repository.unresolvedCount())
    val unresolved: StateFlow<Int> = _unresolved.asStateFlow()

    private val _netflix = MutableStateFlow(repository.netflixStatus())
    val netflix: StateFlow<List<String>> = _netflix.asStateFlow()

    fun setWatchedPercent(value: Int) = repository.setWatchedPercent(value)

    private val _installed = MutableStateFlow(repository.isScrobblerInstalled())
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    fun refresh() {
        _installed.value = repository.isScrobblerInstalled()
        _unresolved.value = repository.unresolvedCount()
        _netflix.value = repository.netflixStatus()
    }

    fun setEnabled(value: Boolean) = repository.setEnabled(value)

    fun clearLog() = repository.clearLog()

    fun launchIntent(): Intent? {
        val pm = context.packageManager
        return (pm.getLeanbackLaunchIntentForPackage(repository.scrobblerPackage) ?: pm.getLaunchIntentForPackage(repository.scrobblerPackage))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
