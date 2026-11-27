package ro.ak.vodunify.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.core.playback.PosterBadgeMode
import ro.ak.vodunify.core.playback.PosterStage
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.prefs.UiPrefs
import ro.ak.vodunify.ui.components.Dropdown

@Composable
fun PosterBadgesSection() {
    val settings by UiPrefs.posterBadges.collectAsStateWithLifecycle()
    val registryVersion by AppRegistry.version.collectAsStateWithLifecycle()
    val services = remember(registryVersion) {
        StreamingService.active.associate { it.id to it.displayName }
    }

    SettingsCard("Iconițe pe postere") {
        SettingHint("Iconițele folosesc disponibilitatea din RO și US. Ordinea de aici se aplică pe Acasă și în Lista mea. Continuă vizionarea arată doar aplicația redării.")
        PosterStage.entries.forEach { stage ->
            val policy = settings.policy(stage)
            Expandable(stage.label, policy.mode.label) {
                Dropdown(
                    label = "Afișare",
                    value = policy.mode,
                    options = PosterBadgeMode.entries.filter { it != PosterBadgeMode.ALL_WITH_LAST || stage != PosterStage.UNWATCHED },
                    optionLabel = { it.label },
                    onSelect = { UiPrefs.setPosterBadgeMode(stage, it) },
                )
                if (policy.mode != PosterBadgeMode.NONE) {
                    SettingHint(
                        if (policy.mode == PosterBadgeMode.FIRST) "Se afișează prima opțiune disponibilă din această ordine."
                        else "Ordinea stabilește unde apar iconițele. Playerul standard apare când nu există nicio platformă și nu se afișează o aplicație din istoric.",
                    )
                    policy.priorities.forEachIndexed { index, current ->
                        Dropdown(
                            label = "Prioritatea ${index + 1}",
                            value = current,
                            options = policy.priorities,
                            optionLabel = { it.label },
                            onSelect = { selected ->
                                UiPrefs.setPosterBadgePriorities(stage, policy.priorities.swap(index, policy.priorities.indexOf(selected)))
                            },
                        )
                    }
                }
            }
        }
        val order = settings.serviceOrder
        Expandable("Ordinea serviciilor de streaming", order.firstNotNullOfOrNull(services::get) ?: "Ordinea catalogului") {
            SettingHint("Pentru două servicii din aceeași bibliotecă, decide care apare primul. Celelalte urmează în ordinea din catalog.")
            order.forEachIndexed { index, id ->
                Dropdown<String?>(
                    label = "Serviciul ${index + 1}",
                    value = id,
                    options = listOf<String?>(null) + services.keys,
                    optionLabel = { if (it == null) "Elimină prioritatea" else services[it] ?: it },
                    onSelect = { selected ->
                        val changed = when {
                            selected == null -> order - id
                            selected in order -> order.swap(index, order.indexOf(selected))
                            else -> order.toMutableList().apply { this[index] = selected }.toList()
                        }
                        UiPrefs.setPosterBadgeServiceOrder(changed)
                    },
                )
            }
            val remaining = services.keys - order.toSet()
            if (remaining.isNotEmpty()) {
                Dropdown<String?>(
                    label = null,
                    value = null,
                    options = remaining.toList(),
                    optionLabel = { id -> services[id] ?: "Adaugă un serviciu la priorități" },
                    onSelect = { id -> if (id != null) UiPrefs.setPosterBadgeServiceOrder(order + id) },
                    dialogTitle = "Adaugă un serviciu la priorități",
                )
            }
        }
    }
}

private fun <T> List<T>.swap(first: Int, second: Int): List<T> = toMutableList().apply {
    val old = this[first]
    this[first] = this[second]
    this[second] = old
}
