package ro.ak.vodunify.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
 * Biblioteca personală: titlurile din toate sursele conectate, reconciliate, cu file pe
 * stare (în curs / următorul episod / de văzut / văzute) și filtru pe sursă.
 * Filele și filtrele sunt FilterChip-uri: merg cu D-pad (focus + OK) și cu touch.
 */
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
    val spec = layoutSpec()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val status by viewModel.syncStatus.collectAsStateWithLifecycle()
    val actions = LocalCatalogActions.current

    LazyVerticalGrid(
        columns = GridCells.Adaptive(spec.posterWidth + 8.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (spec.isTv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (spec.isTv) 24.dp else 16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Bibliotecă", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (status is SyncStatus.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    if (state.connected && ro.ak.vodunify.core.connector.ExternalTrackers.ENABLED) {
                        OutlinedButton(onClick = viewModel::syncNow, enabled = status !is SyncStatus.Running) { Text("Sincronizează") }
                    }
                }
                (status as? SyncStatus.Failed)?.let {
                    Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                ChipRow(LibraryTab.entries.map { it to it.label }, selected = state.filter.tab, onSelect = viewModel::selectTab)
                if (state.sources.size > 1 || state.filter.source != null) {
                    val options = listOf<Pair<String?, String>>(null to "Toate sursele") + state.sources.map { it.id to it.label }
                    ChipRow(
                        options.map { (id, label) -> id to "$label (${state.counts[id] ?: 0})" },
                        selected = state.filter.source,
                        onSelect = viewModel::selectSource,
                    )
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

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options, key = { it.first?.toString() ?: "all" }) { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun LibraryCard(item: LibraryItem) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    Column(Modifier.width(spec.posterWidth)) {
        FocusCard(
            onClick = { item.tmdbId?.let { actions.openTitle(item.kind, it) } },
            modifier = Modifier.size(spec.posterWidth, spec.posterHeight),
            restoreKey = "library|${item.key}",
        ) {
            Box {
                NetworkImage(item.posterUrl, item.title, Modifier.fillMaxSize(), fallbackText = item.title)
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
        Spacer(Modifier.height(6.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotBlank()) {
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            item.sources.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
