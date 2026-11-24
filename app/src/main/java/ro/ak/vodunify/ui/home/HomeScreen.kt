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
import ro.ak.vodunify.ui.components.TitleHero
import ro.ak.vodunify.ui.components.tvHomeRowHeight
import ro.ak.vodunify.ui.components.tvTitleHeroHeight
import ro.ak.vodunify.ui.components.TitleBadge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import ro.ak.vodunify.core.catalog.CatalogRow
import ro.ak.vodunify.core.catalog.CatalogItem
import ro.ak.vodunify.core.catalog.ServiceCatalogStatus
import ro.ak.vodunify.ui.components.AppIconBadge
import ro.ak.vodunify.ui.components.BadgeSpec
import ro.ak.vodunify.ui.components.SectionTitle
import ro.ak.vodunify.ui.components.peekRow
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.ui.components.AttributionNote
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.LoadState
import ro.ak.vodunify.ui.components.LoadingPanel
import ro.ak.vodunify.ui.components.FocusedTitle
import ro.ak.vodunify.ui.components.LocalBackdrop
import ro.ak.vodunify.ui.components.returningFocus
import ro.ak.vodunify.ui.components.HomeRows
import ro.ak.vodunify.ui.components.LocalHomeRows
import ro.ak.vodunify.ui.components.FixedRowsScroll
import ro.ak.vodunify.ui.components.HomeRowFocus
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import ro.ak.vodunify.core.prefs.UiPrefs
import ro.ak.vodunify.core.playback.PosterBadge
import ro.ak.vodunify.core.playback.ServiceBadge
import ro.ak.vodunify.ui.components.RatingChip
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.TitleRow
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.navigation.LocalCatalogActions

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshApps() }
    val personal by viewModel.personal.collectAsStateWithLifecycle()
    val sync by viewModel.syncStatus.collectAsStateWithLifecycle()
    val personalRows = remember(personal, sync, viewModel) { personalHomeRows(personal, sync, viewModel::syncNow) }
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
            items(personalRows, key = { it.key }) { it.content() }
            item { LoadingPanel() }
        }
        is LoadState.Failed -> LazyColumn(Modifier.fillMaxSize()) {
            item { ErrorPanel(s.error, onRetry = viewModel::retry) }
            items(personalRows, key = { it.key }) { it.content() }
        }
        is LoadState.Data -> HomeContent(s.value, personalRows, viewModel, personalReady = personal.state != PersonalState.LOADING)
    }
}

@Composable
private fun HomeContent(
    catalog: HomeCatalog,
    personalRows: List<HomeRow>,
    viewModel: HomeViewModel,
    personalReady: Boolean,
) {
    val spec = layoutSpec()
    val listFocus = remember { FocusRequester() }
    val returning = returningFocus()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Siglele titlurilor din rânduri se pregătesc din timp (adresa + imaginea în cache).
    LaunchedEffect(catalog) {
        viewModel.prefetchLogos((catalog.hero + catalog.rows.flatMap { it.items.take(12) }).map { it.kind to it.id })
    }

    // Pe TV focusul pornește pe primul poster (nu mai e buton „Detalii”: OK pe orice poster deschide fișa);
    // la întoarcerea dintr-o fișă focusul revine pe cardul deschis (vezi FocusRestore).
    // Acasă pornește mereu de sus, de la Continuă vizionarea: focusul se pune abia după ce rândurile personale
    // sunt pe ecran (altfel ar cădea pe primul rând din catalog, iar rândurile apărute deasupra l-ar lăsa jos).
    if (spec.isTv) {
        var started by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(personalReady, returning) {
            if (started) return@LaunchedEffect
            if (returning != null) {
                started = true
                return@LaunchedEffect
            }
            if (!personalReady) delay(PERSONAL_WAIT_MS)
            if (listState.firstVisibleItemIndex != 0) {
                started = true
                return@LaunchedEffect
            }
            started = true
            listState.scrollToItem(0)
            runCatching { listFocus.requestFocus() }
        }
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
            // Poziții fixe: fiecare rând are aceeași înălțime (titlu + cel mai înalt card + spațiu), deci titlul
            // rândului cu focus și titlul rândului următor stau mereu în același loc, oricare ar fi cardurile.
            val slot = tvHomeRowHeight(spec)
            val heroHeight = tvTitleHeroHeight(maxHeight, spec)
            val listHeight = maxHeight - heroHeight
            val animate by UiPrefs.rowAnimation.collectAsStateWithLifecycle()
            val rows = remember(slot, listState, scope) { HomeRows(slot, listState, scope) }
            val allRows = personalRows + catalog.rows.map { row ->
                HomeRow(row.id, row.items.isNotEmpty() || row.serviceFallback != null) {
                    CatalogHomeRow(row, viewModel::openService)
                }
            }
            val keys = allRows.map { row -> row.key.takeIf { row.focusable } }
            val returningRow = keys.indexOf(returning?.homeRow).takeIf { returning?.homeRow != null }
            LaunchedEffect(returning, returningRow) {
                if (returningRow != null && returningRow >= 0 &&
                    (listState.firstVisibleItemIndex != returningRow || listState.firstVisibleItemScrollOffset != 0)
                ) {
                    listState.scrollToItem(returningRow)
                }
            }
            Column(Modifier.fillMaxSize()) {
                Hero(
                    catalog.hero,
                    viewModel,
                    heroHeight,
                    Modifier.fillMaxWidth().height(heroHeight),
                )
                // Se vede doar rândul cu focus și, dedesubt, titlul rândului următor (lista e exact atât de înaltă).
                FixedRowsScroll {
                    CompositionLocalProvider(LocalHomeRows provides rows) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(listHeight)
                                .onPreviewKeyEvent { rows.move(it, keys, animate) }
                                .focusRequester(listFocus),
                            // Și ultimul rând poate urca sus.
                            contentPadding = PaddingValues(bottom = slot),
                        ) {
                            itemsIndexed(allRows, key = { _, row -> row.key }) { index, row ->
                                if (row.focusable) HomeRowFocus(row.key, index, row.content) else row.content()
                            }
                            item(key = "attribution") { HomeAttribution(catalog) }
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
                item(key = "hero") { Hero(catalog.hero, viewModel, spec.heroHeight, Modifier.fillMaxWidth().height(spec.heroHeight)) }
            }
            items(personalRows, key = { it.key }) { it.content() }
            items(catalog.rows, key = { it.id }) { row -> CatalogHomeRow(row, viewModel::openService) }
            item(key = "attribution") { HomeAttribution(catalog) }
        }
    }
}

@Composable
private fun HomeAttribution(catalog: HomeCatalog) {
    AttributionNote(includeWhatToWatch = catalog.rows.any { it.id == "service_voyo_RO" && it.items.isNotEmpty() })
}

@Composable
private fun CatalogHomeRow(row: CatalogRow, openService: (String) -> Boolean) {
    val fallback = row.serviceFallback
    if (fallback == null) {
        val badge = row.service?.let {
            BadgeSpec.ConfirmedService(ServiceBadge(PosterBadge(it.packageName, it.label, it.id), it.market))
        }
        TitleRow(row.title, row.items, badge = badge)
        return
    }
    val context = LocalContext.current
    val padding = layoutSpec().screenPadding
    Column(Modifier.peekRow()) {
        SectionTitle(row.title)
        Column(
            Modifier.padding(horizontal = padding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                if (!openService(fallback.packageName)) {
                    android.widget.Toast.makeText(context, "Aplicația nu a putut fi deschisă.", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                AppIconBadge(fallback.packageName, fallbackLabel = row.title)
                Spacer(Modifier.width(8.dp))
                Text("Deschide aplicația")
            }
            val status = when (fallback.reason) {
                ServiceCatalogStatus.CHECKING -> "Se verifică disponibilitatea titlurilor…"
                ServiceCatalogStatus.NO_PROVIDER -> "Sursa de catalog nu acoperă această bibliotecă."
                ServiceCatalogStatus.NO_TITLES -> "Nu sunt listate momentan titluri pentru această bibliotecă."
                ServiceCatalogStatus.UNAVAILABLE -> "Catalogul nu s-a putut încărca acum."
            }
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    heroHeight: Dp,
    modifier: Modifier = Modifier,
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
        TitleHero(
            title = shown.title,
            logoUrl = logo?.ifEmpty { null },
            pendingLogo = logo == null,
            heroHeight = heroHeight,
        ) { lines ->
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
                    minLines = if (spec.isTv) lines else 1,
                    maxLines = lines,
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

/** Cât așteptăm rândurile personale înainte să punem focusul oricum (prima pornire, fără nimic salvat). */
private const val PERSONAL_WAIT_MS = 4_000L
