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
import ro.ak.vodunify.core.scrobble.ScrobbleLogEntry
import ro.ak.vodunify.core.scrobble.ScrobblePuller
import ro.ak.vodunify.core.scrobble.ScrobbleRepository
import javax.inject.Inject

@HiltViewModel
class ScrobblerSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScrobbleRepository,
    private val puller: ScrobblePuller,
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

    /** Un singur rând de stare: „totul e pregătit” sau exact ce lipsește. null = necunoscut. */
    private val _readiness = MutableStateFlow<List<String>?>(null)
    val readiness: StateFlow<List<String>?> = _readiness.asStateFlow()

    fun refresh() {
        _installed.value = repository.isScrobblerInstalled()
        puller.pullInForegroundAsync()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _readiness.value = readReadiness() }
    }

    private fun readReadiness(): List<String>? {
        for (auth in listOf("ro.ak.vodscrobbler.diag", "ro.ak.vodscrobbler.debug.diag")) {
            val row = runCatching {
                context.contentResolver.query(android.net.Uri.parse("content://$auth/readiness"), null, null, null, null)?.use { c ->
                    if (!c.moveToFirst() || c.columnCount < 6) null else (0 until 6).map { c.getString(it).orEmpty() }
                }
            }.getOrNull() ?: continue
            val listener = row[0]; val accessibility = row[1]; val connected = row[2]; val engines = row[3]; val spoken = row[4]
            val problems = mutableListOf<String>()
            if (listener != "1") problems += "❌ AK Scrobbler nu are acces la notificări: deschide AK Scrobbler și dă-i accesul."
            if (accessibility != "1") problems += "❌ Accesibilitatea AK Scrobbler e oprită: deschide AK Scrobbler și pornește-o."
            else if (connected != "1") problems += "❌ Accesibilitatea AK Scrobbler nu e conectată: repornește TV-ul."
            if (engines != "?" && spoken != "1") {
                problems += "⚠ Vocea Google (sinteza vocală) e activă, deci Apple TV nu-și arată titlul. TV-ul nu vorbește. " +
                    "Dezactiveaz-o o singură dată prin ADB: pm disable-user --user 0 com.google.android.tts"
            }
            return problems.ifEmpty { listOf("✅ Totul e pregătit: titlurile se citesc din orice aplicație, fără voce și fără chenar.") }
        }
        return null
    }

    fun retryIdentification() {
        if (retrying) return
        retrying = true
        _retryStatus.value = "Preiau evenimentele din AK Scrobbler, reîncerc TMDb și verific activitatea Netflix."
        viewModelScope.launch {
            try {
                puller.pullInForeground(forceRetryUnresolved = true)
                _retryStatus.value = "Verificarea TMDb și activitatea Netflix s-au încheiat."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _retryStatus.value = "Nu am putut termina verificarea: ${e.message}"
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
