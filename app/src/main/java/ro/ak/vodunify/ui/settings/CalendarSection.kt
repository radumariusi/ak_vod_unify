package ro.ak.vodunify.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.calendar.model.CalendarSettings
import ro.ak.vodunify.core.calendar.model.ContentFilter
import ro.ak.vodunify.core.calendar.model.NotificationMode
import ro.ak.vodunify.core.calendar.model.QualityThresholds
import ro.ak.vodunify.core.calendar.model.SavedShow
import ro.ak.vodunify.core.calendar.model.StreamingCatalog
import java.util.Locale

/** Setări → Calendar (din AK VOD Calendar). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarSection(viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Calendar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val s = settings
            if (s == null) {
                Text("Se încarcă…")
                return@Column
            }

            Label("Aplicațiile tale și țara fiecăreia")
            Hint(
                "Bifează aplicațiile pe care le ai. Țara (RO / US) decide ora la care apare un episod și dacă e pe aplicația ta. " +
                    "Apasă pe țară ca s-o schimbi.",
            )
            StreamingCatalog.all.forEach { app ->
                val user = s.apps.firstOrNull { it.appId == app.id } ?: return@forEach
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = user.installed,
                        onClick = { viewModel.setAppInstalled(app.id, !user.installed) },
                        label = { Text(if (user.installed) "✓ ${app.name}" else app.name) },
                    )
                    if (user.installed) {
                        OutlinedButton(onClick = { viewModel.toggleAppRegion(app.id) }) { Text("Țara: ${user.region.label} (${user.region.code})") }
                    }
                }
            }
            OutlinedButton(onClick = viewModel::detectApps) { Text("Detectează aplicațiile instalate") }

            Label("Praguri pentru seriale")
            ThresholdEditor(s.thresholds, newLabel = "Seriale noi doar dacă sunt „mult așteptate”", onChange = viewModel::updateShowThresholds)
            Label("Praguri pentru filme")
            ThresholdEditor(s.movieThresholds, newLabel = "Filme fără destule voturi doar dacă sunt „mult așteptate”", onChange = viewModel::updateMovieThresholds)

            Label("Note IMDb (opțional, cheie OMDb)")
            OmdbKeyField(s, viewModel)

            Label("Categorii ascunse")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ContentFilter.entries.forEach { c ->
                    FilterChip(
                        selected = c in s.hiddenCategories,
                        onClick = { viewModel.toggleCategory(c) },
                        label = { Text(if (c in s.hiddenCategories) "Ascuns: ${c.label}" else c.label) },
                    )
                }
            }
            Hint("Ce urmărești apare mereu, chiar dacă e într-o categorie ascunsă.")

            Label("Notificări și canalul „Azi apare”")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                NotificationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = s.notifications.mode == mode,
                        onClick = {
                            viewModel.setNotificationMode(mode)
                            if (mode != NotificationMode.OFF && Build.VERSION.SDK_INT >= 33 && !viewModel.canNotify()) {
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            if (s.notifications.mode != NotificationMode.OFF) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { viewModel.shiftNotificationHour(-1) }) { Text("−") }
                    Text("Ora: %02d:00 (ora României)".format(s.notifications.hour))
                    OutlinedButton(onClick = { viewModel.shiftNotificationHour(1) }) { Text("+") }
                }
                FilterChip(
                    selected = s.notifications.includeMovies,
                    onClick = viewModel::toggleIncludeMovies,
                    label = { Text("Include și filmele") },
                )
            }
            if (viewModel.tvChannelSupported) {
                FilterChip(
                    selected = s.tvChannel,
                    onClick = viewModel::toggleTvChannel,
                    label = { Text("Canalul „Azi apare” pe ecranul principal TV") },
                )
            }
            OutlinedButton(onClick = viewModel::showTodayNow) { Text("Arată acum ce apare azi") }
            Hint("Ce urmărești vine din Trakt și SIMKL (De văzut, seriale în curs), plus „Urmăresc” din Calendar.")

            SavedList("Seriale urmărite din Calendar", s.followedShows, "Nu mai urmări", viewModel::unfollowShow)
            SavedList("Seriale ascunse", s.hiddenShows, "Reafișează", viewModel::unhideShow)
            SavedList("Filme urmărite din Calendar", s.followedMovies, "Nu mai urmări", viewModel::unfollowMovie)
            SavedList("Filme ascunse", s.hiddenMovies, "Reafișează", viewModel::unhideMovie)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThresholdEditor(t: QualityThresholds, newLabel: String, onChange: ((QualityThresholds) -> QualityThresholds) -> Unit) {
    val ro = Locale.forLanguageTag("ro-RO")
    Stepper("IMDb ≥ %.1f".format(ro, t.minImdbRating)) { d -> onChange { it.copy(minImdbRating = (it.minImdbRating + d * 0.5).coerceIn(0.0, 9.5)) } }
    Stepper("Voturi IMDb ≥ ${t.minImdbVotes}") { d -> onChange { it.copy(minImdbVotes = step(it.minImdbVotes, d, listOf(0, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000))) } }
    Stepper("TMDb ≥ %.1f".format(ro, t.minTmdbRating)) { d -> onChange { it.copy(minTmdbRating = (it.minTmdbRating + d * 0.5).coerceIn(0.0, 9.5)) } }
    Stepper("Voturi TMDb ≥ ${t.minTmdbVotes}") { d -> onChange { it.copy(minTmdbVotes = step(it.minTmdbVotes, d, listOf(0, 50, 100, 200, 300, 500, 1_000, 2_000, 5_000))) } }
    FilterChip(
        selected = t.newShowsOnlyIfAnticipated,
        onClick = { onChange { it.copy(newShowsOnlyIfAnticipated = !it.newShowsOnlyIfAnticipated) } },
        label = { Text(newLabel) },
    )
}

private fun step(current: Int, delta: Int, ladder: List<Int>): Int {
    val i = ladder.indexOfFirst { it >= current }.let { if (it < 0) ladder.lastIndex else it }
    val base = if (ladder[i] == current) i else if (delta > 0) i - 1 else i
    return ladder[(base + delta).coerceIn(0, ladder.lastIndex)]
}

@Composable
private fun Stepper(label: String, onDelta: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onDelta(-1) }) { Text("−") }
        OutlinedButton(onClick = { onDelta(1) }) { Text("+") }
        Text(label)
    }
}

@Composable
private fun OmdbKeyField(s: CalendarSettings, viewModel: CalendarSettingsViewModel) {
    var text by rememberSaveable(s.omdbApiKey) { mutableStateOf(s.omdbApiKey) }
    Hint(
        when {
            s.omdbApiKey.isNotBlank() -> "Cheie OMDb salvată în aplicație: pragurile folosesc notele IMDb."
            viewModel.omdbBuiltIn -> "Cheie OMDb inclusă la build (secretul OMDB_API_KEY): pragurile folosesc notele IMDb."
            else -> "Fără cheie, pragurile se aplică pe notele TMDb. Cheie gratuită pe omdbapi.com (1.000 de cereri pe zi)."
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text("Cheie OMDb") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.widthIn(max = 320.dp),
        )
        OutlinedButton(onClick = { viewModel.setOmdbKey(text) }) { Text("Salvează") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedList(title: String, items: List<SavedShow>, action: String, onAction: (Int) -> Unit) {
    if (items.isEmpty()) return
    Label("$title (${items.size})")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            OutlinedButton(onClick = { onAction(item.id) }) { Text("${item.name} · $action") }
        }
    }
}
