package ro.ak.vodunify.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.onFocusChanged
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.PosterRatings
import ro.ak.vodunify.ui.components.LocalPosterLongPress
import ro.ak.vodunify.ui.components.TitleLogo
import ro.ak.vodunify.ui.components.TitleBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import ro.ak.vodunify.ui.components.RowPeek
import ro.ak.vodunify.ui.components.LocalRowPeek
import ro.ak.vodunify.ui.components.clipBelowNextTitle
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
    // Siglele titlurilor din rândurile personale (Continuă, Noutăți, În curând) se pregătesc din timp, ca hero-ul
    // să le arate pe loc la focus.
    LaunchedEffect(personal) {
        val p = personal
        viewModel.prefetchLogos(
            (p.continueMovies + p.continueShows + p.newMovies + p.newEpisodes + p.soonMovies + p.soonEpisodes)
                .mapNotNull { item -> item.tmdbId?.let { item.kind to it } },
        )
    }
    when (val s = state) {
        LoadState.Loading -> LazyColumn(Modifier.fillMaxSize()) {
            item { personalRows() }
            item { LoadingPanel() }
        }
        is LoadState.Failed -> LazyColumn(Modifier.fillMaxSize()) {
            item { ErrorPanel(s.error, onRetry = viewModel::retry) }
            item { personalRows() }
        }
        is LoadState.Data -> HomeContent(s.value, personalRows, viewModel)
    }
}

@Composable
private fun HomeContent(
    catalog: HomeCatalog,
    personalRows: @Composable () -> Unit,
    viewModel: HomeViewModel,
) {
    val spec = layoutSpec()
    val listFocus = remember { FocusRequester() }
    val restore = LocalFocusRestore.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Siglele titlurilor din rânduri se pregătesc din timp (adresa + imaginea în cache).
    LaunchedEffect(catalog) {
        viewModel.prefetchLogos((catalog.hero + catalog.rows.flatMap { it.items.take(12) }).map { it.kind to it.id })
    }

    // Pe TV focusul pornește pe primul poster (nu mai e buton „Detalii”: OK pe orice poster deschide fișa);
    // la întoarcerea dintr-o fișă focusul revine pe cardul deschis (vezi FocusRestore).
    if (spec.isTv) {
        LaunchedEffect(Unit) { if (restore.pending == null) runCatching { listFocus.requestFocus() } }
    }
    // Înapoi de pe un rând de jos urcă la primul rând; abia de acolo întreabă dacă ieși din aplicație.
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
    BackHandler(enabled = spec.isTv && scrolled) {
        scope.launch {
            listState.scrollToItem(0)
            runCatching { listFocus.requestFocus() }
        }
    }

    if (spec.isTv) {
        // Ca în AK VOD Hub: hero-ul stă fix sus și arată titlul cu focus; doar rândurile se derulează,
        // fiecare rând cu focus urcă întreg sub hero (derulare cu pivot). Hero-ul se micșorează cât să încapă
        // un rând întreg de postere dedesubt.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Sub hero încape exact un rând de postere cu titlul lui și, dedesubt, titlul rândului următor.
            val heroHeight = minOf(maxHeight * 0.52f, 240.dp, maxHeight - spec.posterHeight - 84.dp).coerceAtLeast(140.dp)
            val peek = remember { RowPeek() }
            Column(Modifier.fillMaxSize()) {
                Hero(
                    catalog.hero,
                    viewModel,
                    Modifier.fillMaxWidth().height(heroHeight),
                    compact = heroHeight < 200.dp,
                )
                // Rândul cu focus urcă sus, cu titlul lui; deasupra lui nu se vede nimic din rândul anterior,
                // sub el doar titlul rândului următor.
                PivotScroll(pivot = 24.dp) {
                    CompositionLocalProvider(LocalRowPeek provides peek) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().focusRequester(listFocus).clipBelowNextTitle(peek, ROW_TITLE_HEIGHT),
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
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (catalog.hero.isNotEmpty()) {
                item(key = "hero") { Hero(catalog.hero, viewModel, Modifier.fillMaxWidth().height(spec.heroHeight)) }
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
    viewModel: HomeViewModel,
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
    // Sigla: pe loc dacă e deja știută (pregătită din timp), fără să apară întâi numele; altfel se cere după
    // un focus scurt stabil (nu la fiecare poster peste care treci). Numele apare doar dacă titlul n-are siglă.
    val cached = remember(shown.kind, shown.id) { viewModel.cachedLogo(shown.kind, shown.id) }
    var fetched by remember(shown.kind, shown.id) { mutableStateOf<String?>(null) }
    if (cached == null) {
        LaunchedEffect(shown.kind, shown.id) {
            delay(120)
            fetched = viewModel.logo(shown.kind, shown.id).orEmpty()
        }
    }
    val logo = cached ?: fetched

    // Pe telefon, atingerea hero-ului deschide fișa (pe TV hero-ul doar arată titlul cu focus).
    Box(if (spec.isTv) modifier else modifier.clickable { actions.openTitle(shown.kind, shown.id) }) {
        if (backdrop == null) TitleBackdrop(shown.backdropUrl, Modifier.matchParentSize())
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxHeight()
                .padding(start = spec.screenPadding, end = spec.screenPadding, top = 4.dp, bottom = 8.dp)
                .widthIn(max = 620.dp)
                .fillMaxWidth(if (spec.isCompact) 1f else 0.6f),
            // Lipit de jos: descrierea ține mereu 3 rânduri, deci sigla stă la aceeași înălțime la orice titlu.
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
        ) {
            TitleLogo(
                shown.title,
                logo?.ifEmpty { null },
                pending = logo == null,
                height = if (compact) 48.dp else 60.dp,
                textStyle = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            )
            // Ca pe poster: notele, sigla aplicației / serviciului / cinema, apoi data sau episodul.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PosterRatings(shown.kind, shown.id, shown.rating)
                TitleBadge(shown.badge, shown.kind, shown.id)
                Text(
                    shown.meta,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shown.overview.isNotBlank() || spec.isTv) {
                Text(
                    shown.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    minLines = if (spec.isTv) 3 else 1,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Fără butoane: OK pe orice poster deschide fișa. Punctele arată titlurile promovate, pe rând.
            if (items.size > 1 && backdrop?.focused == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
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

/** Cât ocupă titlul unui rând (SectionTitle): atât se vede din rândul de sub cel cu focus. */
private val ROW_TITLE_HEIGHT = 40.dp
