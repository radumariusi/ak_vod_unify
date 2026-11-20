package ro.ak.vodunify.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
        columns = GridCells.Adaptive(spec.posterWidth + 8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "query", span = { GridItemSpan(maxLineSpan) }) {
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

        items(state.results, key = { resultKey(it) }) { result ->
            when (result) {
                is SearchResult.Title -> PosterCard(result.item)
                is SearchResult.Person -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PersonCard(result.person.copy(role = result.knownFor))
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

@Composable
private fun Filters(filters: SearchFilters, genres: List<Genre>, years: List<Int>, viewModel: SearchViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchType.entries, key = { it.name }) { type ->
                FilterChip(
                    selected = filters.type == type,
                    onClick = { viewModel.onTypeChange(type) },
                    label = { Text(type.label) },
                )
            }
        }
        if (filters.type != SearchType.PERSON) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "any_genre") {
                    FilterChip(
                        selected = filters.genreId == null,
                        onClick = { viewModel.onGenreChange(null) },
                        label = { Text("Orice gen") },
                    )
                }
                items(genres, key = { it.id }) { g ->
                    FilterChip(
                        selected = filters.genreId == g.id,
                        onClick = { viewModel.onGenreChange(if (filters.genreId == g.id) null else g.id) },
                        label = { Text(g.name) },
                    )
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "any_year") {
                    FilterChip(
                        selected = filters.year == null,
                        onClick = { viewModel.onYearChange(null) },
                        label = { Text("Orice an") },
                    )
                }
                items(years, key = { it }) { y ->
                    FilterChip(
                        selected = filters.year == y,
                        onClick = { viewModel.onYearChange(if (filters.year == y) null else y) },
                        label = { Text(y.toString()) },
                    )
                }
            }
        }
    }
}
