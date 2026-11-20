package ro.ak.vodunify.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.publish.PublishSettings
import ro.ak.vodunify.core.publish.RouteItem
import ro.ak.vodunify.core.publish.summary
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingEntities
import ro.ak.vodunify.core.tracking.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val publisher: PublishRepository,
    private val tracking: TrackingRepository,
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

    fun label(id: String) = publisher.label(id)

    fun publish(item: RouteItem) = launchAction(item.itemKey) {
        publisher.publishItem(TrackingEntities.media(item.ref), manual = true).summary()
    }

    /** Sincronizează toate sursele; după sincronizare se reverifică publicările neconfirmate. */
    fun syncAndVerify() = launchAction("sync") {
        tracking.sync()
        publisher.verifyPending()
        when (val s = tracking.status.value) {
            is SyncStatus.Failed -> "Sincronizare cu erori: ${s.message}"
            else -> "Sincronizat și reverificat"
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
