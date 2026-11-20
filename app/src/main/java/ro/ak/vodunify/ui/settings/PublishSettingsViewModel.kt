package ro.ak.vodunify.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.publish.PublishMode
import ro.ak.vodunify.core.publish.PublishRepository
import ro.ak.vodunify.core.publish.PublishSettings
import javax.inject.Inject

data class DestinationOption(val id: String, val label: String)

@HiltViewModel
class PublishSettingsViewModel @Inject constructor(
    private val publisher: PublishRepository,
) : ViewModel() {

    val settings: StateFlow<PublishSettings> = publisher.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PublishSettings())

    val connected: StateFlow<Set<String>> = publisher.connectedDestinations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val destinations: List<DestinationOption> = publisher.writableTrackers.map { DestinationOption(it.id, it.displayName) }

    fun setActive(index: Int) {
        viewModelScope.launch { publisher.setActiveProfile(index) }
    }

    fun setMode(index: Int, mode: PublishMode) {
        viewModelScope.launch { publisher.setMode(index, mode) }
    }

    fun toggleDestination(index: Int, id: String) {
        viewModelScope.launch { publisher.toggleDestination(index, id) }
    }
}
