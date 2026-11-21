package ro.ak.vodunify.ui.person

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.catalog.PersonDetails
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.layoutSpec

@Composable
fun PersonScreen(viewModel: PersonViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> ErrorPanel(s.error, onRetry = viewModel::retry)
        is LoadState.Data -> PersonContent(s.value)
    }
}

@Composable
private fun PersonContent(person: PersonDetails) {
    val spec = layoutSpec()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (spec.isTv) LaunchedEffect(person.id) { runCatching { focus.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.padding(horizontal = spec.screenPadding).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                NetworkImage(
                    person.profileUrl,
                    person.name,
                    Modifier.size(spec.posterWidth * 1.3f, spec.posterHeight * 1.3f).clip(RoundedCornerShape(12.dp)),
                    fallbackText = person.name,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(person.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    val facts = listOfNotNull(
                        person.department?.let(::departmentLabel),
                        person.birthday?.let { "Născut(ă): $it" },
                        person.deathday?.let { "Decedat(ă): $it" },
                        person.placeOfBirth,
                    )
                    facts.forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (person.biography.isNotBlank()) {
                        // Card focusabil: pe TV se poate extinde biografia cu OK, pe telefon cu tap.
                        FocusCard(
                            onClick = { expanded = !expanded },
                            focusScale = 1.01f,
                            modifier = Modifier.widthIn(max = 900.dp).focusRequester(focus),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    person.biography,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (expanded) "Mai puțin" else "Citește tot",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }
        item(key = "movies") { TitleRow("Filme", person.movies) }
        item(key = "shows") { TitleRow("Seriale", person.shows) }
        item(key = "attribution") { AttributionNote(includeJustWatch = false) }
    }
}

private fun departmentLabel(d: String) = when (d) {
    "Acting" -> "Actorie"
    "Directing" -> "Regie"
    "Writing" -> "Scenariu"
    "Production" -> "Producție"
    "Sound" -> "Muzică/sunet"
    "Camera" -> "Imagine"
    "Editing" -> "Montaj"
    else -> d
}
