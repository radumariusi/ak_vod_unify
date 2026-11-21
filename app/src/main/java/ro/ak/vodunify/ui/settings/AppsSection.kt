package ro.ak.vodunify.ui.settings

import ro.ak.vodunify.ui.components.Dropdown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.playback.AppRole
import ro.ak.vodunify.core.playback.InstalledApp
import ro.ak.vodunify.core.playback.PlaybackSettings
import ro.ak.vodunify.core.playback.StreamingService
import ro.ak.vodunify.core.playback.TargetApp
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.scrobble.AppProof
import ro.ak.vodunify.ui.components.FilterChip
import ro.ak.vodunify.ui.components.OutlinedButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ro.ak.vodunify.ui.components.readable

/**
 * „Aplicații și servicii”, în trei liste:
 * 1. cum obținem „Continuă vizionarea” din fiecare aplicație (metoda + dovada din vizionările reale);
 * 2. serviciile de streaming, pe biblioteci (RO direct / US prin VPN);
 * 3. playerele (tip Stremio și IPTV), cu cel standard.
 */
@Composable
fun AppsSection(viewModel: PlaybackSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val proofs by viewModel.proofs.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    SettingsCard("Continuă vizionarea: cum se citește fiecare aplicație") {
        SettingHint(
            "AK Scrobbler confirmă singur o metodă când recunoaște o vizionare: ✓ cu data și titlul. " +
                "„Netestat” = pornește ceva în aplicație câteva minute, apoi revino aici.",
        )
        val sorted = TargetApp.entries.sortedWith(
            compareBy<TargetApp> { it !in installed }.thenBy { it.role.ordinal },
        )
        sorted.forEach { app ->
            AppMethodRow(app, installed[app], viewModel.proofFor(app, proofs, installed))
        }
        val unknown = viewModel.unknownProofs(proofs, installed)
        if (unknown.isNotEmpty()) {
            Text("Alte aplicații prinse de AK Scrobbler", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            unknown.forEach { p ->
                Text("${p.app} (${p.pkg})", style = MaterialTheme.typography.bodyMedium)
                ProofLine(p)
            }
        }
        OutlinedButton(onClick = viewModel::refreshInstalled) { Text("Detectează din nou") }
    }

    SettingsCard("Servicii de streaming, pe biblioteci") {
        SettingHint(
            "Fiecare titlu se caută doar în biblioteca serviciului: dacă treci Disney+ pe RO, verificăm catalogul " +
                "Disney+ din România (date TMDb / JustWatch). Serviciile tale sunt cele instalate.",
        )
        val (mine, others) = StreamingService.entries.partition { s -> TargetApp.byId(s.appId)?.let { it in installed } == true }
        Text("Serviciile tale (instalate)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (mine.isEmpty()) SettingHint("Niciun serviciu instalat.")
        mine.forEach { ServiceRow(it, settings, installed, viewModel) }
        Text("Neinstalate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        others.forEach { ServiceRow(it, settings, installed, viewModel) }
    }

    SettingsCard("Playere") {
        SettingHint("Aici pornesc titlurile pe care nu le ai în niciun serviciu. Standardul se schimbă din Redare.")
        listOf(AppRole.PLAYER, AppRole.IPTV).forEach { role ->
            Text(role.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            TargetApp.players.filter { it.role == role }.sortedBy { it !in installed }.forEach { app ->
                val isDefault = settings.defaultPlayerApp == app ||
                    (settings.defaultPlayer == null && TargetApp.players.firstOrNull { it in installed } == app)
                Column(Modifier.fillMaxWidth()) {
                    Text(app.displayName + if (isDefault) " · Standard" else "", style = MaterialTheme.typography.bodyMedium)
                    InstallLine(installed[app])
                }
            }
        }
    }
}

@Composable
private fun AppMethodRow(app: TargetApp, installed: InstalledApp?, proof: AppProof?) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            "${app.displayName} · ${app.role.label}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        InstallLine(installed)
        Text("Metoda: ${app.method}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
        if (proof != null) ProofLine(proof)
    }
}

@Composable
private fun ProofLine(proof: AppProof) {
    val date = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(Date(proof.at))
    Text(
        "✓ Confirmat $date · ${proof.method}" + (proof.title?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.readable(),
    )
}

@Composable
private fun InstallLine(installed: InstalledApp?) {
    Text(
        installed?.let { "Instalat: ${it.label} (${it.packageName})" } ?: "Neinstalat",
        style = MaterialTheme.typography.bodySmall,
        color = if (installed != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.readable(),
    )
}

@Composable
private fun ServiceRow(
    service: StreamingService,
    settings: PlaybackSettings,
    installed: Map<TargetApp, InstalledApp>,
    viewModel: PlaybackSettingsViewModel,
) {
    val app = TargetApp.byId(service.appId)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(service.displayName + (service.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (app != null && app in installed) "Instalat" else "Neinstalat",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.readable(),
            )
        }
        Dropdown(
            label = null,
            value = settings.marketOf(service),
            options = Market.entries,
            optionLabel = { if (it == Market.US) "US (VPN)" else "RO" },
            onSelect = { viewModel.setServiceMarket(service, it) },
            dialogTitle = "Biblioteca pentru ${service.displayName}",
            optionDetail = { if (it == Market.US) "Cauți titlurile în catalogul american, prin VPN" else "Catalogul din România" },
        )
    }
}
