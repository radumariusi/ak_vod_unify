package ro.ak.vodunify.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.onFocusChanged
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.LocalPosterLongPress
import ro.ak.vodunify.ui.components.TitleLogo
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import ro.ak.vodunify.ui.components.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
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
import ro.ak.vodunify.ui.components.FocusedTitle
import ro.ak.vodunify.ui.components.LocalBackdrop
import ro.ak.vodunify.ui.components.LocalFocusRestore
import ro.ak.vodunify.ui.components.PivotScroll
import ro.ak.vodunify.ui.components.RatingChip
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val personal by viewModel.personal.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val personalRows: @Composable () -> Unit = { PersonalSection(personal, sync, viewModel::syncNow) }
    when (val s = state) {
        LoadState.Loading -> LazyColumn(Modifier.fillMaxSize()) {
            item { personalRows() }
            item { LoadingPanel() }
        }
        is LoadState.Failed -> LazyColumn(Modifier.fillMaxSize()) {
            item { ErrorPanel(s.error, onRetry = viewModel::retry) }
            item { personalRows() }
        }
        is LoadState.Data -> HomeContent(s.value, personalRows, viewModel::logo)
    }
}

@Composable
private fun HomeContent(
    catalog: HomeCatalog,
    personalRows: @Composable () -> Unit,
    loadLogo: suspend (TitleKind, Long) -> String?,
) {
    val spec = layoutSpec()
    val heroFocus = remember { FocusRequester() }
    val restore = LocalFocusRestore.current
    var heroFocused by remember { mutableStateOf(false) }

    // Pe TV punem focusul inițial pe butonul principal, ca D-pad-ul să aibă de unde porni;
    // la întoarcerea dintr-o fișă focusul revine pe cardul deschis (vezi FocusRestore).
    if (spec.isTv && catalog.hero.isNotEmpty()) {
        LaunchedEffect(Unit) { if (restore.pending == null) runCatching { heroFocus.requestFocus() } }
    }
    // Din orice rând: Înapoi (sau OK ținut apăsat pe un poster) urcă direct la butoanele din hero, pentru
    // titlul cu focus. Abia din hero, Înapoi întreabă dacă ieși din aplicație.
    BackHandler(enabled = spec.isTv && !heroFocused) { runCatching { heroFocus.requestFocus() } }
    val jumpToHero: () -> Unit = { runCatching { heroFocus.requestFocus() } }

    if (spec.isTv) {
        // Ca în AK VOD Hub: hero-ul stă fix sus și arată titlul cu focus; doar rândurile se derulează,
        // fiecare rând cu focus urcă întreg sub hero (derulare cu pivot). Hero-ul se micșorează cât să încapă
        // un rând întreg de postere dedesubt.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val heroHeight = minOf(maxHeight * 0.52f, 260.dp, maxHeight - spec.posterHeight - 76.dp).coerceAtLeast(150.dp)
            Column(Modifier.fillMaxSize()) {
                Hero(
                    catalog.hero,
                    heroFocus,
                    loadLogo,
                    Modifier.fillMaxWidth().height(heroHeight).onFocusChanged { heroFocused = it.hasFocus },
                    compact = heroHeight < 210.dp,
                )
                CompositionLocalProvider(LocalPosterLongPress provides jumpToHero) {
                    PivotScroll(pivot = 8.dp) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 240.dp),
                        ) {
                            item(key = "personal") { personalRows() }
                            items(catalog.rows, key = { it.id }) { row -> TitleRow(row.title, row.items) }
                            item(key = "attribution") { AttributionNote() }
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (catalog.hero.isNotEmpty()) {
                item(key = "hero") { Hero(catalog.hero, heroFocus, loadLogo, Modifier.fillMaxWidth().height(spec.heroHeight)) }
            }
            item(key = "personal") { personalRows() }
            items(catalog.rows, key = { it.id }) { row -> TitleRow(row.title, row.items) }
            item(key = "attribution") { AttributionNote() }
        }
    }
}

/**
 * Hero-ul din Acasă, ca în AK VOD Hub: fără cadru propriu, peste fundalul panoramic al ecranului.
 * Arată titlul cu focus (poster sau „Continuă”), cu sigla lui; până atunci, titlurile promovate, pe rând.
 */
@Composable
private fun Hero(
    items: List<CatalogItem>,
    focusRequester: FocusRequester,
    loadLogo: suspend (TitleKind, Long) -> String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val spec = layoutSpec()
    val actions = LocalCatalogActions.current
    val backdrop = LocalBackdrop.current
    var index by rememberSaveable { mutableIntStateOf(0) }
    val promoted = items.getOrNull(index.coerceIn(0, (items.size - 1).coerceAtLeast(0)))?.let { FocusedTitle.of(it) }
    val shown = backdrop?.focused ?: promoted ?: return

    // Rotește titlurile promovate cât timp nu ai ales încă nimic cu focusul.
    LaunchedEffect(items.size, backdrop?.focused == null) {
        while (items.size > 1 && backdrop?.focused == null) {
            delay(12_000)
            index = (index + 1) % items.size
        }
    }
    LaunchedEffect(promoted?.id) {
        if (backdrop != null && backdrop.focused == null) backdrop.url = promoted?.backdropUrl
    }
    // Sigla se cere după un focus stabil (nu la fiecare poster peste care treci).
    val logo by produceState<String?>(null, shown.kind, shown.id) {
        value = null
        delay(250)
        value = loadLogo(shown.kind, shown.id)
    }

    Box(modifier) {
        if (backdrop == null) TitleBackdrop(shown.backdropUrl, Modifier.matchParentSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = spec.screenPadding, vertical = 8.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth(if (spec.isCompact) 1f else 0.6f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TitleLogo(
                shown.title,
                logo,
                height = if (compact) 44.dp else 60.dp,
                textStyle = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.rating?.takeIf { it > 0 }?.let { RatingChip(it) }
                Text(
                    shown.meta,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shown.overview.isNotBlank()) {
                Text(
                    shown.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { actions.openTitle(shown.kind, shown.id) },
                    modifier = Modifier.focusRequester(focusRequester),
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Detalii")
                }
                if (items.size > 1 && backdrop?.focused == null) {
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
                                        if (i == index) MaterialTheme.colorScheme.primary
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
