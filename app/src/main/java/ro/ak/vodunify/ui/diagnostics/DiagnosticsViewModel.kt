package ro.ak.vodunify.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ro.ak.vodunify.core.diagnostics.GitHubLogUploader
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.publish.PublishSettings
import ro.ak.vodunify.core.publish.RouteItem
import ro.ak.vodunify.core.publish.summary
import ro.ak.vodunify.core.scrobble.ScrobblePuller
import ro.ak.vodunify.core.scrobble.ScrobbleRepository
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val publisher: PublishRepository,
    private val tracking: TrackingRepository,
    private val scrobble: ScrobbleRepository,
    private val scrobblePuller: ScrobblePuller,
    private val okHttp: OkHttpClient,
) : ViewModel() {

    /** Null = se încarcă. */
    val items: StateFlow<List<RouteItem>?> = publisher.diagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<PublishSettings> = publisher.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PublishSettings())

    val sync: StateFlow<SyncStatus> = tracking.status

    private val _busy = MutableStateFlow<String?>(null)
    /** Elementul care se publică acum (itemKey), sau "sync". */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _githubToken = MutableStateFlow(GitHubLogUploader.getToken(context))
    val githubToken: StateFlow<String> = _githubToken.asStateFlow()

    private val _uploadedGistUrl = MutableStateFlow<String?>(null)
    val uploadedGistUrl: StateFlow<String?> = _uploadedGistUrl.asStateFlow()

    fun saveGithubToken(token: String) {
        GitHubLogUploader.setToken(context, token)
        _githubToken.value = GitHubLogUploader.getToken(context)
        _message.value = if (_githubToken.value.isBlank()) "Token GitHub șters" else "Token GitHub salvat"
    }

    fun uploadLogsToGitHub() = launchAction("upload_logs") {
        val report = GitHubLogUploader.collectFullReport(
            context,
            scrobble.log.value,
            items.value.orEmpty(),
            netflixStatus = scrobble.netflix.value,
            unresolvedCount = scrobble.unresolved.value,
        )
        val res = GitHubLogUploader.upload(okHttp, _githubToken.value, report).getOrThrow()
        _uploadedGistUrl.value = res.url
        res.note ?: "Jurnal încărcat în ${GitHubLogUploader.LOG_REPO} (latest.txt). Claude îl poate citi direct, nu mai trebuie trimis link."
    }

    fun saveLogsLocally() = launchAction("save_logs") {
        val report = GitHubLogUploader.collectFullReport(
            context,
            scrobble.log.value,
            items.value.orEmpty(),
            netflixStatus = scrobble.netflix.value,
            unresolvedCount = scrobble.unresolved.value,
        )
        val path = GitHubLogUploader.saveLocal(context, report).getOrThrow()
        "Jurnal salvat local: $path"
    }

    fun label(id: String) = publisher.label(id)

    fun publish(item: RouteItem) = launchAction(item.itemKey) {
        publisher.publishItem(TrackingEntities.media(item.ref), manual = true).summary()
    }

    /** Preia coada din Scrobbler, sincronizează sursele și reverifică publicările. */
    fun syncAndVerify() = launchAction("sync") {
        val pulled = scrobblePuller.pullInForeground(forceRetryUnresolved = true)
        tracking.sync()
        publisher.verifyPending()
        when (val s = tracking.status.value) {
            is SyncStatus.Failed -> "Sincronizare cu erori: ${s.message}"
            else -> if (pulled > 0) "Preluat $pulled evenimente din Scrobbler · sincronizat" else "Sincronizat și reverificat"
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun launchAction(key: String, block: suspend () -> String) {
        if (_busy.value != null) return
        _busy.value = key
        viewModelScope.launch {
            _message.value = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Eroare: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = null
            }
        }
    }
}
