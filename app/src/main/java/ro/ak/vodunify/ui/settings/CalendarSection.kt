package ro.ak.vodunify.ui.settings

import ro.ak.vodunify.ui.components.OnOffDropdown
import ro.ak.vodunify.ui.components.Dropdown
import ro.ak.vodunify.ui.components.TextInput
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
import ro.ak.vodunify.ui.components.FilterChip
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
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
import ro.ak.vodunify.ui.components.readable

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

            Hint(
                "Aplicațiile tale și țara fiecăreia (ora de apariție, „doar pe aplicațiile mele”) vin din Aplicații și " +
                    "servicii: serviciile instalate, fiecare în biblioteca lui (RO sau US).",
            )

            Label("Praguri pentru seriale")
            ThresholdEditor(s.thresholds, newLabel = "Seriale noi doar dacă sunt „mult așteptate”", onChange = viewModel::updateShowThresholds)
            Label("Praguri pentru filme")
            ThresholdEditor(s.movieThresholds, newLabel = "Filme fără destule voturi doar dacă sunt „mult așteptate”", onChange = viewModel::updateMovieThresholds)

            Hint("Notele IMDb (cheia OMDb) se pun din Setări → Catalog → Note IMDb.")

            Expandable("Categorii", summary = "${s.hiddenCategories.size} ascunse din ${ContentFilter.entries.size}") {
            ContentFilter.entries.forEach { c ->
                Dropdown(
                    label = c.label,
                    value = c in s.hiddenCategories,
                    options = listOf(false, true),
                    optionLabel = { if (it) "Ascunse" else "Afișate" },
                    onSelect = { hide -> if (hide != (c in s.hiddenCategories)) viewModel.toggleCategory(c) },
                )
            }
            }
            Hint("Ce urmărești apare mereu, chiar dacă e într-o categorie ascunsă.")

            Label("Notificări și canalul „Azi apare”")
            Dropdown(
                label = "Notificări",
                value = s.notifications.mode,
                options = NotificationMode.entries,
                optionLabel = { it.label },
                onSelect = { mode ->
                    viewModel.setNotificationMode(mode)
                    if (mode != NotificationMode.OFF && Build.VERSION.SDK_INT >= 33 && !viewModel.canNotify()) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            )
            if (s.notifications.mode != NotificationMode.OFF) {
                Dropdown(
                    label = "Ora (ora României)",
                    value = s.notifications.hour,
                    options = (0..23).toList(),
                    optionLabel = { "%02d:00".format(it) },
                    onSelect = { h -> viewModel.shiftNotificationHour(h - s.notifications.hour) },
                )
                OnOffDropdown("Include și filmele", s.notifications.includeMovies, { if (it != s.notifications.includeMovies) viewModel.toggleIncludeMovies() }, on = "Da", off = "Nu")
            }
            if (viewModel.tvChannelSupported) {
                OnOffDropdown("Canalul „Azi apare” pe ecranul TV", s.tvChannel, { if (it != s.tvChannel) viewModel.toggleTvChannel() })
            }
            OutlinedButton(onClick = viewModel::showTodayNow) { Text("Arată acum ce apare azi") }
            Hint(
                if (ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
                    "Ce urmărești vine din Trakt și SIMKL (De văzut, seriale în curs), plus „Urmăresc” din Calendar."
                } else {
                    "Ce urmărești vine din biblioteca ta (De văzut, seriale începute), plus „Urmăresc” din Calendar."
                },
            )

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
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThresholdEditor(t: QualityThresholds, newLabel: String, onChange: ((QualityThresholds) -> QualityThresholds) -> Unit) {
    val ro = Locale.forLanguageTag("ro-RO")
    val ratings = (0..19).map { it * 0.5 }
    fun rating(v: Double) = if (v <= 0.0) "Oricare" else "≥ %.1f".format(ro, v)
    fun votes(v: Int) = if (v <= 0) "Oricâte" else "≥ $v"
    Dropdown("Notă IMDb", ratings.minByOrNull { kotlin.math.abs(it - t.minImdbRating) } ?: 0.0, ratings, ::rating, { v -> onChange { it.copy(minImdbRating = v) } })
    val imdbVotes = (listOf(0, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000) + t.minImdbVotes).distinct().sorted()
    Dropdown("Voturi IMDb", t.minImdbVotes, imdbVotes, ::votes, { v -> onChange { it.copy(minImdbVotes = v) } })
    Dropdown("Notă TMDb", ratings.minByOrNull { kotlin.math.abs(it - t.minTmdbRating) } ?: 0.0, ratings, ::rating, { v -> onChange { it.copy(minTmdbRating = v) } })
    val tmdbVotes = (listOf(0, 50, 100, 200, 300, 500, 1_000, 2_000, 5_000) + t.minTmdbVotes).distinct().sorted()
    Dropdown("Voturi TMDb", t.minTmdbVotes, tmdbVotes, ::votes, { v -> onChange { it.copy(minTmdbVotes = v) } })
    OnOffDropdown(newLabel, t.newShowsOnlyIfAnticipated, { v -> onChange { it.copy(newShowsOnlyIfAnticipated = v) } }, on = "Da", off = "Nu")
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

/** Setări → Catalog → Note IMDb: cheia OMDb (postere, episoade, pragurile din Calendar). */
@Composable
fun OmdbKeySection(viewModel: CalendarSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Note IMDb (cheie OMDb)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OmdbKeyField(s, viewModel)
        }
    }
}

@Composable
private fun OmdbKeyField(s: CalendarSettings, viewModel: CalendarSettingsViewModel) {
    var text by rememberSaveable(s.omdbApiKey) { mutableStateOf(s.omdbApiKey) }
    Hint(
        when {
            s.omdbApiKey.isNotBlank() -> "Cheie OMDb salvată: notele IMDb apar pe postere, la episoade și în pragurile Calendarului."
            viewModel.omdbBuiltIn -> "Cheie OMDb inclusă la build (secretul OMDB_API_KEY): notele IMDb apar pe postere și la episoade."
            else -> "Fără cheie nu apar note IMDb (doar TMDb). Cheie gratuită pe omdbapi.com → API Key (1.000 de cereri pe zi)."
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextInput(
            value = text,
            onValueChange = { text = it },
            label = "Cheie OMDb",
            onDone = { viewModel.setOmdbKey(text) },
            modifier = Modifier.widthIn(max = 360.dp),
        )
        OutlinedButton(onClick = { viewModel.setOmdbKey(text) }) { Text("Salvează") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedList(title: String, items: List<SavedShow>, action: String, onAction: (Int) -> Unit) {
    if (items.isEmpty()) return
    Expandable(title, summary = "${items.size}") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                OutlinedButton(onClick = { onAction(item.id) }) { Text("${item.name} · $action") }
            }
        }
    }
}
