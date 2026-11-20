package ro.ak.vodunify.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.NetworkImage
import ro.ak.vodunify.ui.components.PlaceholderRow
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.formatRating
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LoadState.Loading -> LoadingPanel(Modifier.fillMaxSize())
        is LoadState.Failed -> LazyColumn(Modifier.fillMaxSize()) {
            item { ErrorPanel(s.error, onRetry = viewModel::retry) }
            item { PlaceholderRows() }
        }
        is LoadState.Data -> HomeContent(s.value)
    }
}

@Composable
private fun HomeContent(catalog: HomeCatalog) {
    val spec = layoutSpec()
    val heroFocus = remember { FocusRequester() }

    // Pe TV punem focusul inițial pe butonul principal, ca D-pad-ul să aibă de unde porni.
    if (spec.isTv && catalog.hero.isNotEmpty()) {
        LaunchedEffect(Unit) { runCatching { heroFocus.requestFocus() } }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (catalog.hero.isNotEmpty()) item(key = "hero") { Hero(catalog.hero, heroFocus) }
        item(key = "placeholders") { PlaceholderRows() }
        items(catalog.rows, key = { it.id }) { row -> TitleRow(row.title, row.items) }
        item(key = "attribution") { AttributionNote() }
    }
}

@Composable
private fun PlaceholderRows() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PlaceholderRow("Continuă vizionarea", "Apare după conectarea Trakt (etapa 3): progresul tău din toate sursele.")
        PlaceholderRow("Următorul episod", "Apare după conectarea Trakt (etapa 3): episoadele noi din serialele urmărite.")
    }
}

@Composable
private fun Hero(items: List<CatalogItem>, focusRequester: FocusRequester) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val current = items[index.coerceIn(0, items.lastIndex)]

    // Rotește automat titlul promovat.
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(12_000)
            index = (index + 1) % items.size
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(spec.heroHeight)
            .padding(horizontal = spec.screenPadding)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Crossfade(current, label = "hero") { item ->
            NetworkImage(item.backdropUrl, null, Modifier.fillMaxSize())
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF20E1116),
                        0.55f to Color(0x990E1116),
                        1f to Color.Transparent,
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xCC0E1116))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (spec.isCompact) 16.dp else 32.dp)
                .fillMaxWidth(if (spec.isCompact) 1f else 0.55f),
        ) {
            Text(
                current.title,
                style = if (spec.isCompact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(current.year?.toString(), current.kind.label, formatRating(current.rating)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                current.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (spec.isCompact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { actions.openTitle(current.kind, current.id) },
                    modifier = Modifier.focusRequester(focusRequester),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Detalii")
                }
                if (items.size > 1) {
                    OutlinedButton(onClick = { index = (index + 1) % items.size }) {
                        Text("Următorul")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items.indices.forEach { i ->
                            Box(
                                Modifier
                                    .size(if (i == index) 10.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i == index) MaterialTheme.colorScheme.onBackground
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    ),
                            )
                        }
                    }
                }
            }
        }
    }
}
