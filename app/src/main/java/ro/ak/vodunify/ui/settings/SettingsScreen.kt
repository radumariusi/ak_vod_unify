package ro.ak.vodunify.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.connector.ExternalTrackers
import ro.ak.vodunify.core.prefs.ContentLanguage
import ro.ak.vodunify.core.prefs.Market
import ro.ak.vodunify.core.update.UpdateState
import ro.ak.vodunify.ui.components.Button
import ro.ak.vodunify.ui.components.FilterChip
import ro.ak.vodunify.ui.components.OutlinedButton
import ro.ak.vodunify.ui.components.TextButton
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.theme.Accent

/** Categoriile setărilor (stânga pe TV / tabletă, listă pe telefon). */
private enum class SettingsCategory(val title: String, val subtitle: String) {
    APP("Aplicație", "Versiune, actualizări, aplicațiile AK"),
    CATALOG("Catalog", "Limba și piața TMDb"),
    APPS("Aplicații și servicii", "Ce citim din fiecare, biblioteci RO/US, playere"),
    PLAYBACK("Redare", "Player standard, unde se deschid serviciile"),
    SCROBBLER("AK Scrobbler", "Continuă vizionarea, automat"),
    IMPORT("Import istoric", "Netflix, Prime Video, fișiere CSV"),
    CALENDAR("Calendar", "Seriale urmărite, calitate"),
    BACKUP("Backup", "Google Drive"),
    ACCOUNTS("Conturi", "Trakt, SIMKL, publicare"),
    ;

    companion object {
        val visible: List<SettingsCategory>
            get() = entries.filter { it != ACCOUNTS || ExternalTrackers.ENABLED }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    accounts: AccountsViewModel = hiltViewModel(),
) {
    val spec = layoutSpec()
    var selectedName by rememberSaveable { mutableStateOf(if (spec.isCompact) null else SettingsCategory.APP.name) }
    val selected = selectedName?.let { n -> SettingsCategory.entries.firstOrNull { it.name == n } }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (viewModel.update.value is UpdateState.NeedsInstallPermission) viewModel.onReturnedFromPermission()
    }

    if (spec.isCompact) {
        if (selected == null) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spec.screenPadding, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SettingsCategory.visible.forEach { cat ->
                    CategoryItem(cat, selected = false, selectOnFocus = false) { selectedName = cat.name }
                }
            }
        } else {
            BackHandler { selectedName = null }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = spec.screenPadding, vertical = 8.dp)) {
                TextButton(onClick = { selectedName = null }) { Text("‹ Setări") }
                Text(selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                CategoryContent(selected, viewModel, accounts)
            }
        }
        return
    }

    val current = selected ?: SettingsCategory.APP
    Row(Modifier.fillMaxSize().padding(start = spec.screenPadding, top = 16.dp)) {
        Column(
            Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsCategory.visible.forEach { cat ->
                // Pe TV, focusul alege categoria (ca filele); dreapta (D-pad) intră în conținut.
                CategoryItem(cat, selected = cat == current, selectOnFocus = spec.isTv) { selectedName = cat.name }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(end = spec.screenPadding, bottom = 24.dp),
        ) {
            CategoryContent(current, viewModel, accounts)
        }
    }
}

@Composable
private fun CategoryItem(cat: SettingsCategory, selected: Boolean, selectOnFocus: Boolean, onSelect: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    val bg = when {
        focused -> c.secondary
        selected -> c.surfaceVariant
        else -> Color.Transparent
    }
    val fg = if (focused) c.onSecondary else c.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(bg)
            .onFocusChanged { if (it.isFocused && selectOnFocus && !selected) onSelect() }
            .clickable(interactionSource = interaction, indication = null, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(32.dp).background(if (selected && !focused) Accent else Color.Transparent))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(cat.title, color = fg, fontWeight = FontWeight.SemiBold)
            Text(cat.subtitle, style = MaterialTheme.typography.bodySmall, color = if (focused) fg else c.onSurfaceVariant)
        }
    }
}

@Composable
private fun CategoryContent(cat: SettingsCategory, viewModel: SettingsViewModel, accounts: AccountsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (cat) {
            SettingsCategory.APP -> AppCategory(viewModel)
            SettingsCategory.CATALOG -> CatalogCategory(viewModel)
            SettingsCategory.APPS -> AppsSection()
            SettingsCategory.PLAYBACK -> PlaybackSection()
            SettingsCategory.SCROBBLER -> ScrobblerSection()
            SettingsCategory.IMPORT -> ImportSection()
            SettingsCategory.CALENDAR -> CalendarSection()
            SettingsCategory.BACKUP -> BackupSection()
            SettingsCategory.ACCOUNTS -> {
                // Trakt/SIMKL ascunși implicit (vezi ExternalTrackers); codul rămâne.
                PublishSection()
                accounts.connectors.forEach { AccountCard(it, accounts) }
            }
        }
    }
}

@Composable
private fun AppCategory(viewModel: SettingsViewModel) {
    val spec = layoutSpec()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsCard("Aplicație") {
        Text("Versiune ${BuildConfig.VERSION_NAME} (cod ${BuildConfig.VERSION_CODE})")
        Text(
            if (spec.isTv) "Mod: Android TV (D-pad)" else "Mod: telefon/tabletă (touch)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (BuildConfig.DEBUG) {
            SettingHint("Build de debug: actualizarea instalează varianta release, ca aplicație separată.")
        }
        Spacer(Modifier.height(8.dp))
        UpdatePanel(
            state = update,
            onCheck = viewModel::checkForUpdates,
            onInstall = viewModel::downloadAndInstall,
            onGrantPermission = { context.startActivity(viewModel.installPermissionIntent()) },
        )
        CompanionsPanel()
    }
}

@Composable
private fun CatalogCategory(viewModel: SettingsViewModel) {
    val catalog by viewModel.catalogSettings.collectAsStateWithLifecycle()
    val cacheMessage by viewModel.cacheMessage.collectAsStateWithLifecycle()
    SettingsCard("Catalog (TMDb)") {
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
        SettingHint("Română: titluri traduse unde există (descrierile lipsă se iau în engleză). Original: titlul original al producției.")
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
