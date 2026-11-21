package ro.ak.vodunify.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.catalog.DiscoverFilters
import ro.ak.vodunify.core.catalog.DiscoverPeriod
import ro.ak.vodunify.core.catalog.DiscoverQuery
import ro.ak.vodunify.core.catalog.DiscoverService
import ro.ak.vodunify.core.catalog.DiscoverSort
import ro.ak.vodunify.core.catalog.Genre
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.Dropdown
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.OutlinedButton
import ro.ak.vodunify.ui.components.PosterCard
import ro.ak.vodunify.ui.components.layoutSpec

/**
 * Descoperă: tot catalogul, filtrat cu liste de alegere (ca restul aplicației): filme sau seriale, ordinea,
 * genul, perioada, serviciul (în biblioteca ta RO / US), nota minimă, țara de origine și limba originală.
 */
@Composable
fun DiscoverScreen(viewModel: DiscoverViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spec = layoutSpec()
    val periods = remember { DiscoverPeriod.options() }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(spec.posterWidth + 8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
            Filters(state.filters, state.genres, state.services, periods, viewModel)
        }
        val error = state.error
        when {
            error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) { ErrorPanel(error, onRetry = viewModel::reload) }
            state.items.isEmpty() && !state.loading -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("Nimic cu filtrele astea. Încearcă altă combinație.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.items, key = { it.mediaKey }) { PosterCard(it, rowKey = "discover") }
        if (state.loading) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LoadingPanel() }
        } else if (state.canLoadMore) {
            item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    OutlinedButton(onClick = viewModel::loadMore) { Text("Mai multe") }
                }
            }
        }
        item(key = "attribution", span = { GridItemSpan(maxLineSpan) }) { AttributionNote(includeJustWatch = state.filters.service != null) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(
    f: DiscoverFilters,
    genres: List<Genre>,
    services: List<DiscoverService>,
    periods: List<DiscoverPeriod>,
    viewModel: DiscoverViewModel,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            label = null,
            value = f.kind,
            options = listOf(TitleKind.MOVIE, TitleKind.TV),
            optionLabel = { if (it == TitleKind.MOVIE) "Filme" else "Seriale" },
            onSelect = viewModel::setKind,
            dialogTitle = "Ce descoperi",
        )
        Dropdown(
            label = null,
            value = f.sort,
            options = DiscoverSort.entries,
            optionLabel = { it.label },
            onSelect = { s -> viewModel.setFilters { it.copy(sort = s) } },
            dialogTitle = "Ordinea",
        )
        val genreOptions = listOf<Genre?>(null) + genres
        Dropdown(
            label = null,
            value = genreOptions.firstOrNull { it?.id == f.genreId },
            options = genreOptions,
            optionLabel = { "Gen: " + (it?.name ?: "Oricare") },
            onSelect = { g -> viewModel.setFilters { it.copy(genreId = g?.id) } },
            dialogTitle = "Genul",
        )
        Dropdown(
            label = null,
            value = periods.firstOrNull { it == f.period } ?: DiscoverPeriod.ANY,
            options = periods,
            optionLabel = { "Perioada: ${it.label}" },
            onSelect = { p -> viewModel.setFilters { it.copy(period = p) } },
            dialogTitle = "Lansat în",
        )
        val serviceOptions = listOf<DiscoverService?>(null) + services
        Dropdown(
            label = null,
            value = serviceOptions.firstOrNull { it == f.service },
            options = serviceOptions,
            optionLabel = { "Pe: " + (it?.label ?: "Oricare serviciu") },
            optionDetail = { s -> s?.let { if (it.owned) "Serviciul tău" else "Neinstalat" } },
            onSelect = { s -> viewModel.setFilters { it.copy(service = s) } },
            dialogTitle = "Disponibil pe (în biblioteca ta)",
        )
        val ratings = listOf<Int?>(null, 5, 6, 7, 8)
        Dropdown(
            label = null,
            value = f.minRating,
            options = ratings,
            optionLabel = { "Nota: " + (it?.let { r -> "≥ $r" } ?: "Oricare") },
            onSelect = { r -> viewModel.setFilters { it.copy(minRating = r) } },
            dialogTitle = "Nota minimă TMDb",
        )
        Dropdown(
            label = null,
            value = DiscoverQuery.countries.firstOrNull { it.first == f.country } ?: DiscoverQuery.countries.first(),
            options = DiscoverQuery.countries,
            optionLabel = { "Țara: ${it.second}" },
            onSelect = { c -> viewModel.setFilters { it.copy(country = c.first) } },
            dialogTitle = "Țara de origine",
        )
        Dropdown(
            label = null,
            value = DiscoverQuery.languages.firstOrNull { it.first == f.language } ?: DiscoverQuery.languages.first(),
            options = DiscoverQuery.languages,
            optionLabel = { "Limba: ${it.second}" },
            onSelect = { l -> viewModel.setFilters { it.copy(language = l.first) } },
            dialogTitle = "Limba originală",
        )
        if (f != DiscoverFilters(kind = f.kind)) {
            OutlinedButton(onClick = viewModel::reset) { Text("Șterge filtrele") }
        }
    }
}
