package ro.ak.vodunify.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ro.ak.vodunify.ui.components.PlatformBadge
import ro.ak.vodunify.ui.components.PosterRatings
import ro.ak.vodunify.ui.components.PosterViewingStatus
import ro.ak.vodunify.ui.components.Dropdown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import ro.ak.vodunify.core.prefs.LibraryLayout
import ro.ak.vodunify.ui.components.DefaultScroll
import ro.ak.vodunify.core.prefs.UiPrefs
import ro.ak.vodunify.ui.components.FixedRowsScroll
import ro.ak.vodunify.ui.components.HomeRowFocus
import ro.ak.vodunify.ui.components.HomeRows
import ro.ak.vodunify.ui.components.LocalHomeRows
import ro.ak.vodunify.ui.components.KeepFocusedContentVisible
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
    KeepFocusedContentVisible(spec.isTv) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(spec.posterWidth),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
            // Aceeași distanță între postere ca în rândurile din Acasă.
            horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
            verticalArrangement = Arrangement.spacedBy(rowSpacing()),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Fără titlul „Lista mea” (e deja fila curentă din meniu).
                    if (ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            if (state.connected) {
                                OutlinedButton(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează") }
                            }
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
}

/** Lista mea pe rânduri orizontale, câte unul pe categorie și tip (ca în Acasă), fără filtre. */
@Composable
private fun LibraryRows(state: LibraryUiState, status: SyncStatus, viewModel: LibraryViewModel) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val header = ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED
    val rowHeight = spec.posterHeight + 60.dp
    val rows = remember(rowHeight, list, scope) { HomeRows(rowHeight, list, scope) }
    val animate by UiPrefs.rowAnimation.collectAsStateWithLifecycle()
    val keys = remember(state.rows, header) {
        (if (header) listOf<String?>(null) else emptyList()) + state.rows.map { it.key }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bottom = if (spec.isTv) (maxHeight - rowHeight).coerceAtLeast(24.dp) else 24.dp
        FixedRowsScroll(spec.isTv) {
            CompositionLocalProvider(LocalHomeRows provides rows.takeIf { spec.isTv }) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { spec.isTv && rows.move(it, keys, animate) },
                    state = list,
                    contentPadding = PaddingValues(bottom = bottom),
                ) {
                    // Fără titlul „Lista mea” deasupra primului rând (e deja fila curentă din meniu).
                    if (header) {
                        item(key = "header") {
                            Row(
                                Modifier.padding(horizontal = spec.screenPadding),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                if (state.connected) {
                                    OutlinedButton(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează") }
                                }
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
                        else -> itemsIndexed(state.rows, key = { _, row -> row.key }) { index, row ->
                            HomeRowFocus(row.key, index + if (header) 1 else 0) {
                                Column {
                                    SectionTitle(row.title)
                                    DefaultScroll {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
                                        ) {
                                            items(row.items, key = { it.key }) { LibraryCard(it, rowKey = row.key) }
                                        }
                                    }
                                }
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
        focusScale = 1f,
        // OK ținut apăsat: meniul titlului (scoate din Lista mea, văzut / nevăzut, Continuă vizionarea).
        onLongClick = {
            item.tmdbId?.let { id ->
                actions.titleMenu(
                    ro.ak.vodunify.core.catalog.CatalogItem(
                        id = id,
                        kind = item.kind,
                        title = item.title,
                        year = null,
                        overview = "",
                        posterUrl = item.posterUrl,
                        backdropUrl = null,
                        rating = item.rating,
                    ),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize().semantics { contentDescription = listOf(item.title, item.subtitle).filter { it.isNotBlank() }.joinToString(", ") }) {
            NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
            PosterRatings(item.kind, item.tmdbId, item.rating, Modifier.align(Alignment.TopStart).padding(4.dp))
            PosterViewingStatus(item.kind, item.tmdbId, Modifier.align(Alignment.TopEnd).padding(4.dp), stage = item.posterStage)
            // Jos, ca pe toate posterele: sigla aplicației și eticheta (episodul / progresul).
            PlatformBadge(
                item.kind,
                item.tmdbId,
                Modifier.align(Alignment.BottomStart).padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                stage = item.posterStage,
                label = item.subtitle,
            )
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
