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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
                    "iar peste pragul de mai jos le trecem automat ca văzute, în istoric. Publicarea în Trakt/SIMKL " +
                    "urmează profilul de mai sus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (installed) "✅ AK Scrobbler e instalat" else "❌ AK Scrobbler nu e instalat: ia ak-scrobbler-….apk din același release",
                color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = enabled, onClick = { viewModel.setEnabled(true) }, label = { Text("Primește") })
                FilterChip(selected = !enabled, onClick = { viewModel.setEnabled(false) }, label = { Text("Oprit") })
                if (installed) {
                    OutlinedButton(onClick = { viewModel.launchIntent()?.let(context::startActivity) }) { Text("Deschide AK Scrobbler") }
                }
                if (log.isNotEmpty()) OutlinedButton(onClick = viewModel::clearLog) { Text("Golește jurnalul") }
            }
            Text("Văzut de la", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScrobbleRules.WATCHED_CHOICES.forEach { p ->
                    FilterChip(selected = watchedPercent == p, onClick = { viewModel.setWatchedPercent(p) }, label = { Text("$p%") })
                }
            }
            if (unresolved > 0) {
                Text(
                    "⏳ $unresolved vizionări încă fără titlu recunoscut: le păstrez și le completez automat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Netflix nu spune titlul: îl iau din „Activitate de vizionare” prin AK Import, după minut. " +
                    "Conectează-te o dată la Netflix în AK Import (RO sau US, unde e contul tău).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            netflix.forEach { line ->
                Text("Netflix · $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Primite recent", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
            if (log.isEmpty()) {
                Text(
                    "Nimic încă. Pornește ceva în Netflix (sau altă aplicație), pune pauză, apoi revino aici.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                log.take(15).forEach { LogLine(it) }
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
