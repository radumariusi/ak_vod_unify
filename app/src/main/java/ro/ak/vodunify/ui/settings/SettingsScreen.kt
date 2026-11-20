package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.connector.Capability
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.connector.ConnectorKind
import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.update.UpdateState
import ro.ak.vodunify.ui.components.layoutSpec

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val spec = layoutSpec()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val catalog by viewModel.catalogSettings.collectAsStateWithLifecycle()
    val cacheMessage by viewModel.cacheMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (viewModel.update.value is UpdateState.NeedsInstallPermission) viewModel.onReturnedFromPermission()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Section("Aplicație") {
                Text("Versiune ${BuildConfig.VERSION_NAME} (cod ${BuildConfig.VERSION_CODE})")
                Text(
                    if (spec.isTv) "Mod: Android TV (D-pad)" else "Mod: telefon/tabletă (touch)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (BuildConfig.DEBUG) {
                    Text(
                        "Build de debug: actualizarea instalează varianta release, ca aplicație separată.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                UpdatePanel(
                    state = update,
                    onCheck = viewModel::checkForUpdates,
                    onInstall = viewModel::downloadAndInstall,
                    onGrantPermission = { context.startActivity(viewModel.installPermissionIntent()) },
                )
            }
        }
        item {
            Section("Catalog (TMDb)") {
                Text(
                    if (viewModel.tmdbConfigured) "Cheie TMDb prezentă" else "Lipsește cheia TMDb (secretul TMDB_API_KEY)",
                    color = if (viewModel.tmdbConfigured) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                Text("Limba titlurilor și descrierilor", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ContentLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = catalog.language == lang,
                            onClick = { viewModel.setLanguage(lang) },
                            label = { Text(lang.label) },
                        )
                    }
                }
                Text(
                    "Română: titluri traduse unde există (descrierile lipsă se iau în engleză). Original: titlul original al producției.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text("Piața (disponibilitate și cataloage pe servicii)", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Market.entries.forEach { m ->
                        FilterChip(
                            selected = catalog.market == m,
                            onClick = { viewModel.setMarket(m) },
                            label = { Text("${m.label} (${m.code})") },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = viewModel::clearCatalogCache) { Text("Golește cache-ul catalogului") }
                cacheMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Acest produs folosește API-ul TMDb, dar nu este aprobat sau certificat de TMDb. " +
                        "Disponibilitatea pe servicii de streaming este furnizată de JustWatch.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { PlaybackSection() }
        item {
            Text("Conectori", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        items(viewModel.connectors, key = { it.id }) { ConnectorRow(it) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun UpdatePanel(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Se verifică…")
            }
            UpdateState.UpToDate -> Text("Ai cea mai nouă versiune.")
            is UpdateState.Available -> {
                Text("Versiune nouă: ${state.update.versionName}", fontWeight = FontWeight.SemiBold)
                if (state.update.notes.isNotBlank()) {
                    Text(state.update.notes.take(600), style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onInstall) { Text("Descarcă și instalează") }
            }
            is UpdateState.Downloading -> {
                Text("Se descarcă ${(state.progress * 100).toInt()}%")
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            UpdateState.NeedsInstallPermission -> {
                Text("Permite instalarea din această aplicație, apoi revino aici.")
                Button(onClick = onGrantPermission) { Text("Deschide permisiunea") }
            }
            UpdateState.Installing -> Text("Se pornește instalarea… confirmă în fereastra sistemului.")
            is UpdateState.Error -> Text("Eroare: ${state.message}", color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(onClick = onCheck) { Text("Verifică actualizări") }
    }
}

@Composable
private fun ConnectorRow(connector: Connector) {
    Card(
        onClick = {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(connector.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                val status = when {
                    connector.kind == ConnectorKind.PLAYER -> "Țintă redare · vezi Redare"
                    connector.isConfigured -> "Chei prezente · conectare în curând"
                    else -> "Lipsesc cheile API"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                connector.capabilities.joinToString(" · ") { it.label() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Capability.label() = when (this) {
    Capability.HISTORY_READ -> "istoric"
    Capability.RESUME_READ -> "citire progres"
    Capability.RESUME_WRITE -> "scriere progres"
    Capability.DEEP_LINK -> "deep link"
}
