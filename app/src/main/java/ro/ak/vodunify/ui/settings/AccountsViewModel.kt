package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.connector.ConnectorAccount
import ro.ak.vodunify.core.connector.ConnectorRegistry
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.DeviceAuthPoll
import ro.ak.vodunify.core.connector.DeviceCode
import ro.ak.vodunify.core.db.SyncStateEntity
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.core.tracking.TrackingRepository
import java.io.IOException
import javax.inject.Inject

sealed interface LoginState {
    data object Idle : LoginState
    data object Requesting : LoginState
    data class WaitingForUser(val code: DeviceCode) : LoginState
    data class Error(val message: String) : LoginState
}

/** Conturile conectorilor cu login prin cod (Trakt acum, SIMKL în etapa 5). */
@HiltViewModel
class AccountsViewModel @Inject constructor(
    registry: ConnectorRegistry,
    private val tracking: TrackingRepository,
) : ViewModel() {

    val connectors: List<DeviceAuthConnector> = registry.all.filterIsInstance<DeviceAuthConnector>()

    val accounts: Map<String, StateFlow<ConnectorAccount?>> = connectors.associate { c ->
        c.id to c.account.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    val syncStates: Map<String, StateFlow<SyncStateEntity?>> = connectors.associate { c ->
        c.id to tracking.syncState(c.id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    val syncStatus: StateFlow<SyncStatus> = tracking.status

    private val _login = MutableStateFlow<Map<String, LoginState>>(emptyMap())
    val login: StateFlow<Map<String, LoginState>> = _login.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    private fun set(id: String, state: LoginState) = _login.update { it + (id to state) }

    fun startLogin(c: DeviceAuthConnector) {
        jobs[c.id]?.cancel()
        jobs[c.id] = viewModelScope.launch {
            set(c.id, LoginState.Requesting)
            val code = try {
                c.startDeviceAuth()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                set(c.id, LoginState.Error("Nu am putut obține un cod: ${e.message}"))
                return@launch
            }
            set(c.id, LoginState.WaitingForUser(code))
            var interval = code.intervalSeconds.toLong()
            while (isActive) {
                delay(interval * 1000)
                val result = try {
                    c.pollDeviceAuth(code)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IOException) {
                    continue // rețea instabilă: încercăm din nou la următorul interval
                } catch (e: Exception) {
                    DeviceAuthPoll.Failed(e.message ?: "Eroare necunoscută")
                }
                when (result) {
                    DeviceAuthPoll.Pending -> Unit
                    DeviceAuthPoll.SlowDown -> interval += 1
                    is DeviceAuthPoll.Approved -> {
                        set(c.id, LoginState.Idle)
                        tracking.sync()
                        return@launch
                    }
                    DeviceAuthPoll.Denied -> {
                        set(c.id, LoginState.Error("Accesul a fost refuzat pe ${c.displayName}."))
                        return@launch
                    }
                    DeviceAuthPoll.Expired -> {
                        set(c.id, LoginState.Error("Codul a expirat. Cere unul nou."))
                        return@launch
                    }
                    is DeviceAuthPoll.Failed -> {
                        set(c.id, LoginState.Error(result.message))
                        return@launch
                    }
                }
            }
        }
    }

    fun cancelLogin(c: DeviceAuthConnector) {
        jobs.remove(c.id)?.cancel()
        set(c.id, LoginState.Idle)
    }

    fun signOut(c: DeviceAuthConnector) {
        viewModelScope.launch {
            runCatching { c.signOut() }
            tracking.clearSource(c.id)
            set(c.id, LoginState.Idle)
        }
    }

    fun syncNow() {
        viewModelScope.launch { tracking.sync() }
    }
}
