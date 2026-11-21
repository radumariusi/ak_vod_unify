package ro.ak.vodunify.ui.settings

import ro.ak.vodunify.ui.components.OnOffDropdown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.ui.components.Button
import ro.ak.vodunify.ui.components.FilterChip
import ro.ak.vodunify.ui.components.OutlinedButton
import ro.ak.vodunify.ui.components.TextButton
import ro.ak.vodunify.ui.components.readable

/** Backup pe Google Drive: conectare cu cod (ca la TV), backup acum / zilnic, restaurare. */
@Composable
fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val code by viewModel.code.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val auto by viewModel.auto.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val confirm by viewModel.confirm.collectAsStateWithLifecycle()

    LaunchedEffect(connected) { if (connected && files == null) viewModel.refreshList() }

    SettingsCard("Backup pe Google Drive") {
        SettingHint(
            "Salvează tot ce ține aplicația (vizionări, în curs, Lista mea, calendarul, setările) în folderul " +
                "„AK VOD Unify backup” din Google Drive-ul tău. Aplicația vede doar fișierele create de ea. " +
                "Se păstrează ultimele 10 copii.",
        )
        when {
            !viewModel.configured -> Text(
                "Lipsesc cheile Google (secretele GOOGLE_CLIENT_ID și GOOGLE_CLIENT_SECRET în GitHub).",
                color = MaterialTheme.colorScheme.error,
            )
            code != null -> code?.let { c ->
                Text("Pe telefon, deschide ${c.verificationUrl} și introdu codul:", fontWeight = FontWeight.Medium)
                Text(c.userCode, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                SettingHint("Aștept confirmarea… (codul expiră în ${c.expiresIn / 60} minute)")
                OutlinedButton(onClick = viewModel::cancelConnect) { Text("Renunță") }
            }
            !connected -> Button(onClick = viewModel::connect) { Text("Conectează Google Drive") }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = viewModel::backupNow, enabled = !busy) { Text("Fă backup acum") }
                    OutlinedButton(onClick = viewModel::refreshList, enabled = !busy) { Text("Arată copiile") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                OnOffDropdown("Backup automat zilnic", auto, viewModel::setAuto)
                status?.let { Text("Ultimul: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.readable()) }
                files?.let { list ->
                    Text("Copii pe Drive", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
                    if (list.isEmpty()) SettingHint("Nicio copie încă.")
                    list.forEach { f ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium)
                                f.size?.toLongOrNull()?.let { SettingHint("${it / 1000} KB") }
                            }
                            OutlinedButton(onClick = { viewModel.askRestore(f) }, enabled = !busy) { Text("Restaurează") }
                        }
                    }
                }
                TextButton(onClick = viewModel::disconnect) { Text("Deconectează Google Drive") }
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable()) }
    }

    confirm?.let { f ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRestore,
            title = { Text("Restaurezi ${f.name}?") },
            text = { Text("Datele de acum din aplicație (vizionări, Lista mea, setări) se înlocuiesc cu cele din copie, apoi aplicația repornește.") },
            confirmButton = { Button(onClick = viewModel::restore) { Text("Restaurează") } },
            dismissButton = { OutlinedButton(onClick = viewModel::cancelRestore) { Text("Renunță") } },
        )
    }
}
