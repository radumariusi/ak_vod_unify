package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.publish.PublishMode
import ro.ak.vodunify.ui.navigation.LocalCatalogActions
import ro.ak.vodunify.ui.components.readable

/** Setările „Publicare progres”: profilul activ, modul și destinațiile lui. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PublishSection(viewModel: PublishSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val actions = LocalCatalogActions.current
    val profile = settings.active

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Publicare progres", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Trimite poziția (din „Setează minutul” sau cea mai nouă dintre surse) în trackere, ca punct de reluare. " +
                    "Nu marchează nimic ca văzut și nu pornește sesiuni de vizionare false.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )

            Text("Profil activ", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.profiles.forEach { p ->
                    FilterChip(
                        selected = p.index == settings.activeIndex,
                        onClick = { viewModel.setActive(p.index) },
                        label = { Text("${p.name} · ${p.mode.label}") },
                    )
                }
            }

            Text("Mod pentru „${profile.name}”", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PublishMode.entries.forEach { m ->
                    FilterChip(
                        selected = profile.mode == m,
                        onClick = { viewModel.setMode(profile.index, m) },
                        label = { Text(m.label) },
                    )
                }
            }
            Text(profile.mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())

            Text("Destinații pentru „${profile.name}”", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.destinations.forEach { d ->
                    FilterChip(
                        selected = d.id in profile.destinations,
                        onClick = { viewModel.toggleDestination(profile.index, d.id) },
                        label = { Text(d.label + if (d.id in connected) "" else " (neconectat)") },
                    )
                }
            }
            Text(
                "Nuvio citește reluarea din Trakt: bifează Trakt dacă vrei ca Nuvio să pornească de unde ai rămas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )
            OutlinedButton(onClick = actions.openDiagnostics, modifier = Modifier.padding(top = 6.dp)) {
                Text("Diagnostic rute")
            }
        }
    }
}
