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
import ro.ak.vodunify.core.playback.AppRegistry
import ro.ak.vodunify.ui.components.ChoiceDialog
import ro.ak.vodunify.ui.components.ChoiceOption
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Recitește numele și listele după fiecare aplicație adăugată / eliminată.
    val registryVersion by viewModel.registryVersion.collectAsStateWithLifecycle()
    val groupedProofs = remember(proofs, installed, registryVersion) { viewModel.groupProofs(proofs.values, installed) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshInstalled() }

    ManageAppsCard(viewModel, installed)

    SettingsCard(
        "Continuă vizionarea: cum se citește fiecare aplicație",
        collapsible = true,
        summary = "${TargetApp.active.size} aplicații, ${installed.size} instalate",
    ) {
        SettingHint(
            "AK Scrobbler confirmă singur o metodă când recunoaște o vizionare: ✓ cu data și titlul. " +
                "„Netestat” = pornește ceva în aplicație câteva minute, apoi revino aici.",
        )
        val sorted = TargetApp.active.sortedWith(
            compareBy<TargetApp> { it !in installed }.thenBy { it.role.ordinal },
        )
        sorted.forEach { app ->
            val proof = groupedProofs[app]?.maxByOrNull { it.at }
            AppMethodRow(app, installed[app], proof, onRemove = { viewModel.remove(app) })
        }
        val unknown = groupedProofs[null].orEmpty().sortedByDescending { it.at }
        if (unknown.isNotEmpty()) {
            Text("Alte aplicații prinse de AK Scrobbler", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            unknown.forEach { p ->
                Text("${p.app} (${p.pkg})", style = MaterialTheme.typography.bodyMedium)
                ProofLine(p)
            }
        }
        OutlinedButton(onClick = viewModel::refreshInstalled) { Text("Actualizează lista aplicațiilor instalate") }
    }

    SettingsCard(
        "Servicii de streaming, pe biblioteci",
        collapsible = true,
        summary = StreamingService.active
            .filter { s -> TargetApp.byId(s.appId)?.let { it in installed } == true }
            .joinToString(", ") { "${it.displayName} (${settings.marketOf(it).code})" }
            .ifEmpty { "Niciun serviciu instalat" },
    ) {
        SettingHint(
            "Fiecare titlu se caută doar în biblioteca serviciului: dacă treci Disney+ pe RO, verificăm catalogul " +
                "Disney+ din România (date TMDb / JustWatch). Serviciile tale sunt cele instalate.",
        )
        val (mine, others) = StreamingService.active.filter { s -> TargetApp.byId(s.appId)?.isActive == true }.partition { s -> TargetApp.byId(s.appId)?.let { it in installed } == true }
        Text("Serviciile tale (instalate)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        if (mine.isEmpty()) SettingHint("Niciun serviciu instalat.")
        mine.forEach { ServiceRow(it, settings, installed, viewModel) }
        Text("Neinstalate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        others.forEach { ServiceRow(it, settings, installed, viewModel) }
    }

    SettingsCard(
        "Playere",
        collapsible = true,
        summary = "Standard: " + (
            (settings.defaultPlayerApp ?: TargetApp.players.firstOrNull { it in installed })?.displayName ?: "niciunul"
            ),
    ) {
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
private fun AppMethodRow(app: TargetApp, installed: InstalledApp?, proof: AppProof?, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(
            "${app.displayName} · ${app.role.label}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        InstallLine(installed)
        if (proof != null) {
            ProofLine(proof)
        } else {
            Text("Metoda: ${app.method}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
        }
    }
    OutlinedButton(onClick = onRemove) { Text("Elimină") }
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
        if (app != null) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.remove(app) }) { Text("Elimină") }
        }
    }
}

/**
 * Adaugă o aplicație instalată (ca intrare cunoscută nerecunoscută, ca player nou sau ca serviciu
 * de streaming nou) și readuce ce ai eliminat.
 */
@Composable
private fun ManageAppsCard(viewModel: PlaybackSettingsViewModel, installed: Map<TargetApp, InstalledApp>) {
    val launchable by viewModel.launchable.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    var step by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    SettingsCard("Adaugă sau elimină aplicații și servicii") {
        SettingHint(
            "Adaugă: alegi o aplicație instalată, apoi spui ce este (o aplicație din listă pe care n-am recunoscut-o, " +
                "un player nou sau un serviciu de streaming nou). Elimină: butonul de lângă fiecare aplicație sau serviciu, mai jos.",
        )
        OutlinedButton(onClick = {
            message = null
            viewModel.loadLaunchable()
            step = 1
        }) { Text("Adaugă o aplicație instalată") }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.readable()) }
        val hidden = TargetApp.entries.filter { !it.isCustom && AppRegistry.isHidden(it.id) }
        if (hidden.isNotEmpty()) {
            Text("Eliminate", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            hidden.forEach { app ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${app.displayName} · ${app.role.label}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { viewModel.restore(app) }) { Text("Readaugă") }
                }
            }
        }
    }

    when (step) {
        1 -> {
            val byPackage = installed.values.associateBy { it.packageName }
            ChoiceDialog(
                title = "Alege aplicația instalată",
                options = if (launchable.isEmpty()) {
                    listOf(ChoiceOption("Se încarcă…", enabled = false))
                } else {
                    launchable.map { (pkg, label) ->
                        ChoiceOption(label, byPackage[pkg]?.let { "Recunoscută: ${it.app.displayName}" } ?: pkg)
                    }
                },
                selected = null,
                onPick = { i ->
                    picked = launchable.getOrNull(i)
                    step = if (picked != null) 2 else 0
                },
                onDismiss = { step = 0 },
                dismissOnPick = false,
            )
        }
        2 -> {
            val (pkg, label) = picked ?: return
            val known = TargetApp.entries.filter { !it.isCustom && it !in installed }.sortedBy { it.role.ordinal }
            val options = known.mapIndexed { idx, app ->
                ChoiceOption(app.displayName, app.role.label, heading = if (idx == 0) "Din listă (nerecunoscute)" else null)
            } +
                listOf(
                    ChoiceOption("Player nou (tip Stremio)", label, heading = "Nouă"),
                    ChoiceOption("Player IPTV nou", label),
                    ChoiceOption("Serviciu de streaming nou", "Poți alege și furnizorul TMDb"),
                )
            ChoiceDialog(
                title = "Ce este $label?",
                options = options,
                selected = null,
                onPick = { i ->
                    when {
                        i < known.size -> {
                            viewModel.assign(known[i], pkg)
                            message = "$label e acum ${known[i].displayName}."
                            step = 0
                        }
                        i == known.size || i == known.size + 1 -> {
                            val role = if (i == known.size) AppRole.PLAYER else AppRole.IPTV
                            message = if (viewModel.addPlayer(role, pkg, label)) "Am adăugat $label (${role.label})." else "Nu mai sunt locuri libere pentru ${role.label}: elimină una adăugată."
                            step = 0
                        }
                        else -> {
                            viewModel.loadProviders()
                            step = 3
                        }
                    }
                },
                onDismiss = { step = 0 },
                dismissOnPick = false,
            )
        }
        3 -> {
            val (pkg, label) = picked ?: return
            ChoiceDialog(
                title = "Ce serviciu este $label?",
                options = listOf(ChoiceOption("Adaugă fără furnizor TMDb", "Apare pe Acasă cu buton de deschidere")) +
                    providers.map { ChoiceOption(it.second) },
                selected = null,
                onPick = { i ->
                    val provider = providers.getOrNull(i - 1)
                    val name = provider?.second ?: label
                    message = if (viewModel.addService(provider?.first, name, pkg)) {
                        "Am adăugat serviciul $name."
                    } else {
                        "Nu mai sunt locuri libere pentru servicii. Elimină unul adăugat."
                    }
                    step = 0
                },
                onDismiss = { step = 0 },
                dismissOnPick = false,
            )
        }
    }
}
