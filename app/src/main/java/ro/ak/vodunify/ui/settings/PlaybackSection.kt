package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.ui.components.Dropdown

/**
 * „Redare”: playerul standard și unde se deschide fiecare serviciu al tău, ca liste de alegere.
 * La fiecare titlu poți alege oricând altă variantă din „Alege unde vezi”.
 */
@Composable
fun PlaybackSection(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    SettingsCard("Redare") {
        val players = TargetApp.players.sortedBy { it !in installed }
        val playerOptions = listOf<TargetApp?>(null) + players
        Dropdown(
            label = "Player standard",
            value = settings.defaultPlayerApp?.takeIf { it in players },
            options = playerOptions,
            optionLabel = { it?.displayName ?: "Primul instalat" },
            onSelect = viewModel::setDefaultPlayer,
            optionDetail = { app -> if (app == null) null else if (app in installed) "Instalat" else "Neinstalat" },
            optionEnabled = { app -> app == null || app in installed },
        )
        SettingHint("Pornește titlurile care nu sunt pe niciun serviciu al tău.")

        Text("Serviciile tale se deschid în", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        SettingHint("Implicit aplicația oficială. Poți alege un player pentru un serviciu.")
        val installedPlayers = players.filter { it in installed }
        val services = StreamingService.entries.filter { s ->
            val official = TargetApp.byId(s.appId)
            (official != null && official in installed) || settings.preferredApp(s) != official
        }
        if (services.isEmpty()) SettingHint("Niciun serviciu de streaming instalat.")
        services.forEach { service ->
            val official = TargetApp.byId(service.appId) ?: return@forEach
            val current = settings.preferredApp(service)
            val options = (listOf(official) + installedPlayers + listOfNotNull(current.takeIf { it != official })).distinct()
            Dropdown(
                label = "${service.displayName} (${settings.marketOf(service).code})",
                value = current,
                options = options,
                optionLabel = { app ->
                    (if (app == official) "Aplicația oficială" else app.displayName) + if (app in installed) "" else " (neinstalat)"
                },
                onSelect = { viewModel.setPreferred(service, it) },
                dialogTitle = "${service.displayName} se deschide în",
            )
        }
    }
}
