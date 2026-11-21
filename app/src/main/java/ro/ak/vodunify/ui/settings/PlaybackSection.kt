package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.ui.components.FilterChip

/**
 * „Redare”: biblioteca principală, playerul standard și unde se deschide fiecare serviciu.
 * La fiecare titlu poți alege oricând altă rețea sau alt player din „Alege unde vezi”.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSection(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    SettingsCard("Redare") {
        Text("Biblioteca principală", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Market.entries.forEach { m ->
                FilterChip(
                    selected = settings.playbackMarket == m,
                    onClick = { viewModel.setPlaybackMarket(m) },
                    label = { Text(if (m == Market.US) "SUA (prin VPN)" else m.label) },
                )
            }
        }
        SettingHint("Prima verificată când cauți unde e un titlu. Fiecare serviciu are biblioteca lui (RO sau US), în Aplicații și servicii.")

        Text("Player standard", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        SettingHint("Pornește titlurile pe care nu le ai în niciun serviciu (sau doar în cealaltă bibliotecă).")
        val players = TargetApp.players.sortedBy { it !in installed }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = settings.defaultPlayer == null,
                onClick = { viewModel.setDefaultPlayer(null) },
                label = { Text("Primul instalat") },
            )
            players.forEach { app ->
                FilterChip(
                    selected = settings.defaultPlayer == app.id,
                    onClick = { viewModel.setDefaultPlayer(app) },
                    label = { Text(app.displayName + if (app in installed) "" else " (neinstalat)") },
                )
            }
        }

        Text("Serviciile se deschid în", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        SettingHint("Implicit aplicația oficială. Poți alege un player pentru un serviciu pe care nu-l ai.")
        val installedPlayers = players.filter { it in installed }
        StreamingService.entries.forEach { service ->
            val official = TargetApp.byId(service.appId) ?: return@forEach
            val current = settings.preferredApp(service)
            val options = (listOf(official) + installedPlayers + listOfNotNull(current.takeIf { it != official })).distinct()
            Text(
                "${service.displayName} · biblioteca ${settings.marketOf(service).code}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { app ->
                    FilterChip(
                        selected = current == app,
                        onClick = { viewModel.setPreferred(service, app) },
                        label = {
                            val name = if (app == official) "Aplicația oficială" else app.displayName
                            Text(name + if (app in installed) "" else " (neinstalat)")
                        },
                    )
                }
            }
        }
    }
}
