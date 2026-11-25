package ro.ak.vodunify.ui.settings

import ro.ak.vodunify.ui.components.OnOffDropdown
import ro.ak.vodunify.ui.components.Dropdown
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.scrobble.ScrobbleLogEntry
import ro.ak.vodunify.core.scrobble.ScrobbleResult
import ro.ak.vodunify.core.scrobble.ScrobbleRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.ak.vodunify.ui.components.readable
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

/** „AK Scrobbler”: ce a citit aplicația însoțitoare din Netflix, Prime, SkyShowtime etc. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScrobblerSection(viewModel: ScrobblerSettingsViewModel = hiltViewModel()) {
    val log by viewModel.log.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val watchedPercent by viewModel.watchedPercent.collectAsStateWithLifecycle()
    val unresolved by viewModel.unresolved.collectAsStateWithLifecycle()
    val netflix by viewModel.netflix.collectAsStateWithLifecycle()
    val retryStatus by viewModel.retryStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AK Scrobbler (ce rulează pe TV)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Aplicația mică AK Scrobbler vede ce redă Netflix, Prime Video, SkyShowtime, HBO Max, Disney+, Apple TV, " +
                    "Stremio sau alt player și trimite aici titlul și minutul. Le salvăm local, în „Continuă vizionarea”, " +
                    "iar peste pragul de mai jos le trecem automat ca văzute, în istoric." +
                    if (ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) " Publicarea în Trakt/SIMKL urmează profilul de mai sus." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )
            Text(
                if (installed) "✅ AK Scrobbler e instalat" else "❌ AK Scrobbler nu e instalat: ia ak-scrobbler-….apk din același release",
                color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            OnOffDropdown("Primește vizionările", enabled, viewModel::setEnabled, on = "Da", off = "Oprit")
            val actions = LocalCatalogActions.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed) {
                    OutlinedButton(onClick = { viewModel.launchIntent()?.let(context::startActivity) }) { Text("Deschide AK Scrobbler") }
                }
                OutlinedButton(onClick = actions.openDiagnostics) { Text("Jurnal complet & GitHub") }
                if (log.isNotEmpty()) OutlinedButton(onClick = viewModel::clearLog) { Text("Golește jurnalul") }
                if (unresolved > 0) OutlinedButton(onClick = viewModel::retryIdentification) { Text("Reîncearcă identificarea") }
            }
            Dropdown(
                label = "Marcat ca văzut de la",
                value = watchedPercent,
                options = ScrobbleRules.WATCHED_CHOICES,
                optionLabel = { "$it%" },
                onSelect = viewModel::setWatchedPercent,
            )
            if (unresolved > 0) {
                Text(
                    "⏳ $unresolved vizionări încă fără titlu recunoscut: le păstrez și le completez automat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.readable(),
                )
            }
            Text(
                "Pentru titlurile Netflix lipsă, se folosește activitatea profilului tău. Conectează Netflix în AK Scrobbler " +
                    "sau din Istoric din servicii aici în AK VOD Unify.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )
            netflix.forEach { line ->
                Text("Netflix · $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
            }
            retryStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.readable()) }
            if (log.isEmpty()) {
                Text("Primite recent", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Nimic încă. Pornește ceva în Netflix (sau altă aplicație), pune pauză, apoi revino aici.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.readable(),
                )
            } else {
                Expandable("Primite recent", summary = "${log.size.coerceAtMost(40)} înregistrări") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { log.take(40).forEach { LogLine(it) } }
                }
            }
        }
    }
}

@Composable
private fun LogLine(e: ScrobbleLogEntry) {
    val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(e.at))
    val position = clock(e.positionMs) + (e.durationMs?.let { " / ${clock(it)}" } ?: "")
    val result = ScrobbleResult.entries.firstOrNull { it.wire == e.result }
    val reason = when (e.reason) {
        "pause" -> "pauză"
        "stop" -> "oprit"
        "tick" -> "în redare"
        else -> e.reason
    }
    Text(
        "$time · ${e.app} · ${e.matched ?: e.raw} · $position · $reason → ${result?.label ?: e.result}",
        style = MaterialTheme.typography.bodySmall,
        color = if (result == ScrobbleResult.UNMATCHED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.readable(),
    )
    if (e.matched != null && e.matched != e.raw) {
        Text(
            "   citit: ${e.raw}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}
