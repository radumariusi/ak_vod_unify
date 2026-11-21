package ro.ak.vodunify.ui.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.imports.ImportSources
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.ak.vodunify.ui.components.readable

/** „Istoric din servicii”: AK Import RO / US și CSV-ul Netflix. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImportSection(viewModel: ImportSettingsViewModel = hiltViewModel()) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.readCsv(uri)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Istoric din servicii (AK Import)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "AK Import RO și AK Import US te conectează în contul serviciului și aduc istoricul aici " +
                    "(Netflix: tot istoricul, cu minutul unde ai rămas). Pune doar AK Import US în tunelul VPN US; " +
                    "AK Import RO și AK VOD Unify merg direct.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModel.routes.forEach { route ->
                    if (route in installed) {
                        OutlinedButton(onClick = { viewModel.launchIntent(route)?.let(context::startActivity) }) {
                            Text("Deschide AK Import $route")
                        }
                    } else {
                        Text(
                            "AK Import $route: neinstalat",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
                OutlinedButton(onClick = {
                    try {
                        picker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "*/*"))
                    } catch (_: ActivityNotFoundException) {
                        viewModel.setMessage("Acest dispozitiv nu are selector de fișiere. Pe TV folosește AK Import (Netflix).")
                    }
                }) { Text("Importă CSV Netflix") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.readable()) }

            preview?.let { p ->
                Text(
                    "${p.parse.items.size} titluri în ${p.fileName}" + if (p.parse.skipped > 0) " (${p.parse.skipped} trailere/rânduri goale sărite)" else "",
                    fontWeight = FontWeight.Medium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.parse.profiles.size > 1) {
                        p.parse.profiles.forEach { prof -> Button(onClick = { viewModel.importCsv(prof) }) { Text("Doar „$prof”") } }
                        OutlinedButton(onClick = { viewModel.importCsv(null) }) { Text("Toate profilurile") }
                    } else {
                        Button(onClick = { viewModel.importCsv(null) }) { Text("Importă") }
                    }
                    OutlinedButton(onClick = viewModel::cancelCsv) { Text("Renunță") }
                }
            }

            if (pending > 0) Text("⏳ $pending titluri așteaptă să fie găsite în TMDb…")
            val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
            stats.forEach { s ->
                val name = ImportSources.labels[s.service] ?: s.service
                Text(
                    "$name · ultimul import ${fmt.format(Date(s.lastImportAt))}" +
                        (if (s.route.isNotBlank()) " · din ${s.route}" else "") +
                        (if (s.profile.isNotBlank()) " · profil ${s.profile}" else ""),
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "${s.received} primite · ${s.matched} găsite în TMDb · ${s.resumePoints} începute (cu minut) · ${s.unmatched} negăsite",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.readable(),
                )
                if (s.unmatchedSamples.isNotEmpty()) {
                    Text(
                        "Negăsite, de exemplu: " + s.unmatchedSamples.takeLast(5).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { viewModel.clear(s.service) }) { Text("Șterge istoricul importat din $name") }
            }
        }
    }
}
