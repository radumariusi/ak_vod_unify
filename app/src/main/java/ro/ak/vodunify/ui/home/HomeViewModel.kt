package ro.ak.vodunify.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class HomeItem(val id: String, val title: String, val subtitle: String, val progress: Float? = null)
data class HomeRow(val title: String, val items: List<HomeItem>)
data class HomeHero(val title: String, val description: String)
data class HomeUiState(val hero: HomeHero, val rows: List<HomeRow>)

/** Etapa 1: conținut demonstrativ. Din etapa 2 rândurile vin din TMDb + Room. */
@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(demoState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private fun demoState() = HomeUiState(
        hero = HomeHero(
            title = "Bun venit în AK VOD Unify",
            description = "Istoricul și progresul tău din Trakt, SIMKL și WeTrakr, într-un singur loc. " +
                "Alegi apoi unde vezi: Netflix, Nuvio, Stremio sau DebridStream.",
        ),
        rows = listOf(
            HomeRow("Continuă vizionarea", List(8) { HomeItem("c$it", "Titlu ${it + 1}", "S1 · E${it + 2}", progress = 0.15f + it * 0.1f) }),
            HomeRow("Episoade noi", List(10) { HomeItem("n$it", "Serial ${it + 1}", "Episod nou") }),
            HomeRow("Populare acum", List(12) { HomeItem("p$it", "Film ${it + 1}", "2026") }),
        ),
    )
}
