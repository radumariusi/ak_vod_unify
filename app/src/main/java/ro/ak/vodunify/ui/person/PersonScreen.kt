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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import ro.ak.vodunify.ui.components.PosterCard
import ro.ak.vodunify.ui.components.layoutSpec
import java.time.LocalDate
import java.time.Period

@Composable
fun PersonScreen(viewModel: PersonViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> ErrorPanel(s.error, onRetry = viewModel::retry)
        is LoadState.Data -> PersonContent(s.value)
    }
}

/**
 * Pagina unui actor, ca în AK VOD Hub: fotografia, datele (născut, vârsta, locul, ce face), biografia
 * pe scurt (OK o deschide toată) și filmografia ca grilă de postere, filme și seriale împreună.
 */
@Composable
private fun PersonContent(person: PersonDetails) {
    val spec = layoutSpec()
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (spec.isTv) LaunchedEffect(person.id) { runCatching { focus.requestFocus() } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(spec.posterWidth + 4.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 18.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (spec.isTv) 18.dp else 12.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                NetworkImage(
                    person.profileUrl,
                    person.name,
                    Modifier.size(104.dp, 156.dp).clip(RoundedCornerShape(8.dp)),
                    fallbackText = person.name,
                )
                Column(Modifier.widthIn(max = 820.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(person.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    personLines(person).forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (person.biography.isNotBlank()) {
                        // Focusabil: pe TV biografia se deschide toată cu OK, pe telefon cu tap.
                        FocusCard(
                            onClick = { expanded = !expanded },
                            focusScale = 1.01f,
                            modifier = Modifier.focusRequester(focus),
                        ) {
                            Column(Modifier.padding(10.dp)) {
                                Text(
                                    person.biography,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = if (expanded) Int.MAX_VALUE else 4,
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
                    Text(
                        "Filme și seriale (${person.credits.size})",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        items(person.credits, key = { it.mediaKey }) { item -> PosterCard(item, rowKey = "person") }
        item(key = "attribution", span = { GridItemSpan(maxLineSpan) }) { AttributionNote(includeJustWatch = false) }
    }
}

/** „Născut pe 1967-01-30 (vârsta 59)”, locul nașterii, ce face. */
private fun personLines(person: PersonDetails): List<String> {
    val age = runCatching {
        val born = LocalDate.parse(person.birthday)
        val end = person.deathday?.let { LocalDate.parse(it) } ?: LocalDate.now()
        Period.between(born, end).years.takeIf { it in 0..130 }
    }.getOrNull()
    val birth = person.birthday?.let { b ->
        buildString {
            append("Născut(ă) pe ").append(b)
            person.deathday?.let { append(" · decedat(ă) pe ").append(it) }
            age?.let { append(" (vârsta ").append(it).append(")") }
        }
    }
    return listOfNotNull(
        birth,
        person.placeOfBirth?.takeIf { it.isNotBlank() },
        person.department?.let { "Cunoscut(ă) pentru: ${departmentLabel(it)}" },
    )
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
