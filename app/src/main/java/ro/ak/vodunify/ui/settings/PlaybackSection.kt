package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import ro.ak.vodunify.ui.components.FilterChip
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
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

/** Setările „Alege unde vezi”: piața conturilor și aplicația preferată pentru fiecare serviciu. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybackSection(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Redare (Alege unde vezi)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Text("Piața conturilor tale de streaming", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Market.entries.forEach { m ->
                    FilterChip(
                        selected = settings.playbackMarket == m,
                        onClick = { viewModel.setPlaybackMarket(m) },
                        label = { Text("${m.label} (${m.code})") },
                    )
                }
            }
            Text(
                "Serviciile oficiale se aleg din această piață. Dacă titlul e doar în cealaltă, primești o avertizare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            StreamingService.entries.forEach { service ->
                val official = TargetApp.byId(service.appId) ?: return@forEach
                val current = settings.preferredApp(service)
                Text("${service.displayName}: deschide în", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(official) + TargetApp.players).forEach { app ->
                        val suffix = if (app in installed) "" else " (neinstalat)"
                        FilterChip(
                            selected = current == app,
                            onClick = { viewModel.setPreferred(service, app) },
                            label = { Text((if (app == official) "Aplicația oficială" else app.displayName) + suffix) },
                        )
                    }
                }
            }

            Text("Aplicații detectate", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (installed.isEmpty()) {
                Text("Niciuna dintre aplicațiile cunoscute.", style = MaterialTheme.typography.bodySmall)
            } else {
                installed.values.forEach {
                    Text("${it.app.displayName}: ${it.label} (${it.packageName})", style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = viewModel::refreshInstalled) { Text("Detectează din nou") }
        }
    }
}
