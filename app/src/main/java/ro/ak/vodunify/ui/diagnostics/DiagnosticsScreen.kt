package ro.ak.vodunify.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.playback.formatPosition
import ro.ak.vodunify.core.publish.PublishMode
import ro.ak.vodunify.core.publish.PublishStatus
import ro.ak.vodunify.core.publish.RouteDiagnostics
import ro.ak.vodunify.core.publish.RouteItem
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.ui.components.layoutSpec
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * „Diagnostic rute”: pentru fiecare film / episod început, poziția din fiecare sursă,
 * ce câștigă, starea publicării și ce primește fiecare player. Merge cu D-pad și touch.
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val spec = layoutSpec()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    val profile = settings.active

    LaunchedEffect(Unit) { if (spec.isTv) runCatching { firstFocus.requestFocus() } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.widthIn(max = 1000.dp)) {
                Text("Diagnostic rute", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Profil „${profile.name}” · ${profile.mode.label} · destinații: " +
                        (profile.destinations.sorted().joinToString { viewModel.label(it) }.ifBlank { "niciuna" }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "„Publicat” apare doar după ce am citit poziția înapoi din tracker. Peste 80% nu trimitem nimic: " +
                        "văzut se marchează doar cu „Marchează văzut”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = viewModel::syncAndVerify,
                        enabled = busy == null && sync !is SyncStatus.Running,
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { Text("Sincronizează și verifică") }
                    if (busy == "sync" || sync is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
            }
        }
        val list = items
        when {
            list == null -> item(key = "loading") { CircularProgressIndicator() }
            list.isEmpty() -> item(key = "empty") {
                Text(
                    "Nimic început. Folosește „Setează minutul” în „Alege unde vezi” sau sincronizează trackerele.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(list, key = { it.itemKey }) { item ->
                RouteCard(
                    item = item,
                    canPublishMode = profile.mode != PublishMode.VIEW_ONLY,
                    publishing = busy == item.itemKey,
                    enabled = busy == null,
                    onPublish = { viewModel.publish(item) },
                )
            }
        }
    }
}

@Composable
private fun RouteCard(item: RouteItem, canPublishMode: Boolean, publishing: Boolean, enabled: Boolean, onPublish: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (focused) BorderStroke(2.dp, Color.White) else null,
        // Cardul e focusabil ca să poți parcurge cu D-pad și elementele fără butoane.
        modifier = Modifier.fillMaxWidth().widthIn(max = 1000.dp).focusable(interactionSource = interaction),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title(item), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Actualizat ${time(item.lastUpdate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (item.positions.isNotEmpty()) Label("Poziție pe surse")
            item.positions.forEach { p ->
                val value = when {
                    !p.present -> "—"
                    else -> buildString {
                        append(RouteDiagnostics.pct(p.progress))
                        p.positionMs?.let { append(" · ").append(formatPosition(it)) }
                        p.updatedAt?.let { append(" · ").append(time(it)) }
                        if (p.echo) append(" · ecou al publicării noastre")
                    }
                }
                Line(
                    (if (p.isWinner) "★ " else "   ") + p.label,
                    value,
                    if (p.isWinner) MaterialTheme.colorScheme.primary else Color.Unspecified,
                )
            }

            if (item.destinations.isNotEmpty()) Label("Publicare")
            item.destinations.forEach { d ->
                val status = d.status?.let { s ->
                    buildString {
                        append(s.label)
                        d.sentProgress?.let { append(" ").append(RouteDiagnostics.pct(it)) }
                        if (s == PublishStatus.NOT_CONFIRMED || s == PublishStatus.PUBLISHED) {
                            append(" · citit înapoi: ").append(d.readBackProgress?.let { RouteDiagnostics.pct(it) } ?: "nimic")
                        }
                        d.attemptAt?.let { append(" · ").append(time(it)) }
                    }
                }
                Line(d.label, listOfNotNull(status, d.note).joinToString(" · "), statusColor(d.status))
            }

            if (item.players.isNotEmpty()) Label("Ce primește fiecare player")
            item.players.forEach { p ->
                Line(p.player, p.note, if (p.getsPosition) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (canPublishMode && item.canPublish) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = onPublish, enabled = enabled) { Text("Publică acum") }
                    if (publishing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Line(name: String, value: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 120.dp, max = 220.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun statusColor(s: PublishStatus?): Color = when (s) {
    PublishStatus.PUBLISHED -> Color(0xFF4CAF50)
    PublishStatus.SENT, PublishStatus.NOT_CONFIRMED -> Color(0xFFFFB300)
    PublishStatus.FAILED -> MaterialTheme.colorScheme.error
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun title(item: RouteItem): String {
    val r = item.ref
    return if (r.type == "EPISODE") {
        "${r.title} · S${r.season ?: "?"} E${r.episode ?: "?"}" + (r.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
    } else {
        listOfNotNull(r.title, r.year?.toString()).joinToString(" · ")
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM HH:mm")

private fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)
