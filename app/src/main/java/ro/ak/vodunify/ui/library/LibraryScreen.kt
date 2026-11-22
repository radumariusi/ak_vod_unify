package ro.ak.vodunify.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ro.ak.vodunify.ui.components.PlatformBadge
import ro.ak.vodunify.ui.components.PosterBottomRow
import ro.ak.vodunify.ui.components.Dropdown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import ro.ak.vodunify.core.prefs.LibraryLayout
import ro.ak.vodunify.ui.components.DefaultScroll
import ro.ak.vodunify.ui.components.PivotScroll
import ro.ak.vodunify.ui.components.SectionTitle
import ro.ak.vodunify.ui.components.rowSpacing
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.CircularProgressIndicator
import ro.ak.vodunify.ui.components.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.tracking.SyncStatus
import ro.ak.vodunify.ui.components.FocusCard
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

/**
 * Lista mea: ce vrei să vezi, ce ai în curs (filme neterminate, seriale începute) și ce ai văzut,
 * amestecate („Toate”) sau pe file, cu filtru pe sursă. La seriale: progresul pe episoade.
 * Filele și filtrele sunt FilterChip-uri: merg cu D-pad (focus + OK) și cu touch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val spec = layoutSpec()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val actions = LocalCatalogActions.current

    if (state.layout == LibraryLayout.ROWS) {
        LibraryRows(state, status, viewModel)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(spec.posterWidth + 8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
        // Aceeași distanță între postere ca în rândurile din Acasă.
        horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
        verticalArrangement = Arrangement.spacedBy(rowSpacing()),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Lista mea", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (state.connected && ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
                        OutlinedButton(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează") }
                    }
                }
                (status as? SyncStatus.Failed)?.let {
                    Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dropdown(
                        label = null,
                        value = state.filter.tab,
                        options = LibraryTab.visible,
                        optionLabel = { "Arată: ${it.label}" },
                        onSelect = viewModel::selectTab,
                        dialogTitle = "Ce arăți",
                    )
                    if (state.sources.size > 1 || state.filter.source != null) {
                        val options = listOf<String?>(null) + state.sources.map { it.id }
                        val labels = state.sources.associate { it.id to it.label }
                        Dropdown(
                            label = null,
                            value = state.filter.source,
                            options = options,
                            optionLabel = { id -> "Sursa: " + (if (id == null) "Toate" else labels[id] ?: id) + " (${state.counts[id] ?: 0})" },
                            onSelect = viewModel::selectSource,
                            dialogTitle = "Sursa",
                        )
                    }
                }
                Text(
                    when {
                        state.total == 0 -> ""
                        state.total > state.items.size -> "${state.items.size} din ${state.total} titluri"
                        else -> "${state.total} titluri"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when {
            state.loading -> item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            !state.connected -> item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Conectează Trakt sau SIMKL ca să-ți vezi aici istoricul, progresul și lista „De văzut”, din toate sursele la un loc.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = actions.openSettings) { Text("Deschide Setări") }
                }
            }
            state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (status is SyncStatus.Running) "Se sincronizează…" else "Nimic aici pentru filtrul ales.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> items(state.items, key = { it.key }) { item -> LibraryCard(item) }
        }
    }
}

/** Lista mea pe rânduri orizontale, câte unul pe categorie și tip (ca în Acasă), fără filtre. */
@Composable
private fun LibraryRows(state: LibraryUiState, status: SyncStatus, viewModel: LibraryViewModel) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    PivotScroll(pivot = 54.dp, enabled = spec.isTv) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp)) {
            item(key = "header") {
                Row(
                    Modifier.padding(horizontal = spec.screenPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Lista mea", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (state.connected && ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
                        OutlinedButton(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează") }
                    }
                }
            }
            when {
                state.loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                !state.connected -> item(key = "connect") {
                    Column(Modifier.padding(horizontal = spec.screenPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Conectează Trakt sau SIMKL ca să-ți vezi aici istoricul.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = actions.openSettings) { Text("Deschide Setări") }
                    }
                }
                state.rows.isEmpty() -> item(key = "empty") {
                    Text(
                        if (status is SyncStatus.Running) "Se sincronizează…" else "Nimic aici încă. Ce vezi, prin AK Scrobbler, apare aici.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = spec.screenPadding),
                    )
                }
                else -> items(state.rows, key = { it.title.substringBefore(" (") }) { row ->
                    Column {
                        SectionTitle(row.title)
                        DefaultScroll {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
                            ) {
                                items(row.items, key = { it.key }) { LibraryCard(it, rowKey = row.title.substringBefore(" (")) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCard(item: LibraryItem, rowKey: String = "library") {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    // Fără text sub poster (ca peste tot): episodul / progresul stă pe poster.
    FocusCard(
        onClick = { item.tmdbId?.let { actions.openTitle(item.kind, it) } },
        modifier = Modifier.wrapContentSize().size(spec.posterWidth, spec.posterHeight),
        restoreKey = "$rowKey|${item.key}",
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOf(item.title, item.subtitle).filter { it.isNotBlank() }.joinToString(", ") }) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            // Jos, ca pe toate posterele: sigla aplicației și eticheta (episodul / progresul).
            PosterBottomRow(
                Modifier.align(Alignment.BottomStart).padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                label = item.subtitle,
            ) { PlatformBadge(item.kind, item.tmdbId) }
            if (item.showsProgress && item.progress != null) {
                LinearProgressIndicator(
                    progress = { item.progress ?: 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xAA000000),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}
