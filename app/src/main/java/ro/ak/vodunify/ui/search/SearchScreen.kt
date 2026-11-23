package ro.ak.vodunify.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import ro.ak.vodunify.ui.components.Dropdown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.text.font.FontWeight
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.rowSpacing
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import ro.ak.vodunify.ui.components.FilterChip
import androidx.compose.material3.Icon
import ro.ak.vodunify.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.catalog.Genre
import ro.ak.vodunify.core.catalog.SearchFilters
import ro.ak.vodunify.core.catalog.SearchResult
import ro.ak.vodunify.core.catalog.SearchType
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.PersonCard
import ro.ak.vodunify.ui.components.PosterCard
import ro.ak.vodunify.ui.components.TextInput
import ro.ak.vodunify.ui.components.layoutSpec
import java.time.Year

@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spec = layoutSpec()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val years = remember { (Year.now().value downTo Year.now().value - 40).toList() }

    LazyVerticalGrid(
        columns = GridCells.FixedSize(spec.posterWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
        // Aceeași distanță între postere ca în rândurile din Acasă.
        horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
        verticalArrangement = Arrangement.spacedBy(rowSpacing()),
    ) {
        item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
            if (spec.isTv) {
                TextInput(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = "Caută",
                    placeholder = "Un film, un serial sau o persoană",
                    imeAction = ImeAction.Search,
                    onDone = viewModel::submit,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                )
            } else {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
                    singleLine = true,
                    placeholder = { Text("Caută un film, un serial sau o persoană") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Șterge")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        viewModel.submit()
                    }),
                )
            }
        }
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Filters(state.filters, state.genres, years, viewModel)
        }

        val error = state.error
        when {
            error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                ErrorPanel(error, onRetry = viewModel::submit)
            }
            state.isIdle -> item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Scrie un titlu sau un nume, ori alege un gen/an ca să răsfoiești catalogul.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.results.isEmpty() && !state.loading -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("Niciun rezultat.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Rezultatele pe secțiuni: Filme, Seriale, Persoane (fiecare cu titlul ei, pe tot rândul).
        val groups = listOf(
            "Filme" to state.results.filter { it is SearchResult.Title && it.item.kind == TitleKind.MOVIE },
            "Seriale" to state.results.filter { it is SearchResult.Title && it.item.kind == TitleKind.TV },
            "Persoane" to state.results.filter { it is SearchResult.Person },
        ).filter { it.second.isNotEmpty() }
        groups.forEach { (label, list) ->
            if (groups.size > 1) {
                item(key = "section_$label", span = { GridItemSpan(maxLineSpan) }) {
                    Text("$label (${list.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            items(list, key = { resultKey(it) }) { result ->
                when (result) {
                    is SearchResult.Title -> PosterCard(result.item)
                    is SearchResult.Person -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PersonCard(result.person.copy(role = result.knownFor))
                    }
                }
            }
        }

        if (state.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LoadingPanel() }
        } else if (state.canLoadMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = viewModel::loadMore) { Text("Mai multe rezultate") }
                }
            }
        }
        item(key = "attribution", span = { GridItemSpan(maxLineSpan) }) { AttributionNote(includeJustWatch = false) }
    }
}

private fun resultKey(r: SearchResult) = when (r) {
    is SearchResult.Title -> r.item.mediaKey
    is SearchResult.Person -> "person:${r.person.id}"
}

/** Filtrele ca liste de alegere (tip, gen, an), pe un rând: nu mai sunt zeci de butoane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(filters: SearchFilters, genres: List<Genre>, years: List<Int>, viewModel: SearchViewModel) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            label = null,
            value = filters.type,
            options = SearchType.entries,
            optionLabel = { "Tip: ${it.label}" },
            onSelect = viewModel::onTypeChange,
            dialogTitle = "Ce cauți",
        )
        if (filters.type != SearchType.PERSON) {
            val genreOptions = listOf<Genre?>(null) + genres
            Dropdown(
                label = null,
                value = genreOptions.firstOrNull { it?.id == filters.genreId },
                options = genreOptions,
                optionLabel = { "Gen: " + (it?.name ?: "Oricare") },
                onSelect = { viewModel.onGenreChange(it?.id) },
                dialogTitle = "Genul",
            )
            val yearOptions = listOf<Int?>(null) + years
            Dropdown(
                label = null,
                value = filters.year,
                options = yearOptions,
                optionLabel = { "An: " + (it?.toString() ?: "Oricare") },
                onSelect = viewModel::onYearChange,
                dialogTitle = "Anul",
            )
        }
    }
}
