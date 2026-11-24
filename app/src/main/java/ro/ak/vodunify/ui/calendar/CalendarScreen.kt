package ro.ak.vodunify.ui.calendar

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import ro.ak.vodunify.ui.components.ChoiceDialog
import ro.ak.vodunify.ui.components.ChoiceOption
import ro.ak.vodunify.ui.components.Dropdown
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import ro.ak.vodunify.core.calendar.format.dayLabel
import ro.ak.vodunify.core.calendar.format.monthLabel
import ro.ak.vodunify.core.calendar.format.weekLabel
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.IconButton
import ro.ak.vodunify.ui.components.tvTitleHeroHeight
import ro.ak.vodunify.ui.components.tvHomeRowHeight
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.clearReturningFocus
import ro.ak.vodunify.ui.navigation.LocalTabTopFocus
import ro.ak.vodunify.ui.navigation.LocalCatalogActions
import ro.ak.vodunify.ui.watch.WatchChooserHost
import ro.ak.vodunify.ui.watch.WatchChooserViewModel

/**
 * Calendarul (portat din AK VOD Calendar): ce episoade și filme apar, pe zi / săptămână / lună,
 * cu ora României. Apăsare = pagina titlului; apăsare lungă = „Alege unde vezi”.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarScreen(
    onBackdropChange: (String?) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
    chooser: WatchChooserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val actions = LocalCatalogActions.current
    val context = LocalContext.current
    val spec = layoutSpec()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshToday() }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(watch) {
        watch?.let { (request, availability) ->
            chooser.open(request, availability)
            viewModel.consumeWatch()
        }
    }

    val dayRow = rememberLazyListState()
    val weekList = rememberLazyListState()
    val monthList = rememberLazyListState()
    var shownDay by rememberSaveable { mutableStateOf("") }
    var shownWeek by rememberSaveable { mutableStateOf("") }
    var shownMonth by rememberSaveable { mutableStateOf("") }
    var monthReturnKey by rememberSaveable { mutableStateOf<String?>(null) }
    var monthSelectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusTop = LocalTabTopFocus.current
    val clearFocus = clearReturningFocus()
    val period = "${state.tab}-${state.from}-${state.to}"
    var focusedTitle by rememberSaveable(period) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.mode, period) {
        when {
            state.mode.isDay -> {
                if (shownDay.isNotEmpty() && shownDay != period) dayRow.scrollToItem(0)
                shownDay = period
            }
            state.mode == CalendarMode.MONTH -> {
                if (shownMonth.isNotEmpty() && shownMonth != period) monthList.scrollToItem(0)
                shownMonth = period
            }
            else -> {
                if (shownWeek.isNotEmpty() && shownWeek != period) weekList.scrollToItem(0)
                shownWeek = period
            }
        }
    }
    BackHandler {
        clearFocus()
        val origin = viewModel.back()
        monthReturnKey = origin
        if (origin == null) {
            focusedTitle = null
            scope.launch {
                dayRow.scrollToItem(0)
                focusTop()
            }
        }
    }

    val open: (CalendarItem) -> Unit = { actions.openTitle(if (it.isMovie) TitleKind.MOVIE else TitleKind.TV, it.tmdbId) }
    val itemActions = remember(viewModel) {
        CalendarItemActions(
            open = open,
            watch = viewModel::chooseWhere,
            watched = viewModel::markWatched,
            list = viewModel::addToList,
            follow = viewModel::toggleFollow,
            hide = viewModel::hide,
        )
    }
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val icons by viewModel.icons.collectAsStateWithLifecycle()
    val logoLanguage by viewModel.logoLanguage.collectAsStateWithLifecycle(initialValue = null)
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(60_000 - value.toEpochMilli() % 60_000)
        }
    }
    val visible = remember(state) { state.visible }
    val inMonth = state.mode == CalendarMode.MONTH
    val grouped = remember(visible, inMonth) { if (inMonth) emptyList() else groupSameDayEpisodes(visible) }
    val shown = grouped.firstOrNull { it.key == focusedTitle } ?: grouped.firstOrNull()
    val onFocus: (CalendarItem) -> Unit = { focusedTitle = it.key }
    SideEffect { onBackdropChange(shown?.backdropUrl) }
    DisposableEffect(onBackdropChange) {
        onDispose { onBackdropChange(null) }
    }

    CompositionLocalProvider(LocalCalendarIcons provides icons, LocalCalendarNow provides now) {
        Column(Modifier.fillMaxSize()) {
            CalendarToolbar(state, viewModel, showFilters, onFilters = { showFilters = it })

            val error = state.error
            when {
                error != null && state.passed == null -> ErrorPanel(error, onRetry = viewModel::reload, modifier = Modifier.padding(spec.screenPadding))
                state.passed == null -> Info(if (state.mode.isDay) "Se încarcă…" else "Se încarcă perioada…")
                else -> {
                    Summary(state)
                    if (!state.hasMyApps) Info("Sfat: aplicațiile tale și biblioteca fiecăreia (RO / US) se iau din Setări → Aplicații și servicii.")
                    if (error != null) Info("Unele lansări nu s-au putut încărca.")
                    when {
                        inMonth -> MonthCalendar(
                            state.from, state.to, visible, monthList, monthReturnKey,
                            selectedDate = monthSelectedDay,
                            onSelectDate = { monthSelectedDay = it.toString() },
                            onFocusRestored = { monthReturnKey = null },
                            onOpenDay = viewModel::openDay,
                            onOpenWeek = viewModel::openWeek,
                            logoLanguage = logoLanguage,
                            loadLogo = viewModel::titleLogo,
                            modifier = Modifier.weight(1f),
                        )
                        shown == null -> Info(if (state.loading) "Se încarcă perioada…" else "Nimic în perioada asta după filtrele tale.")
                        else -> BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            // Lasă loc posterelor și când bara se așază pe două rânduri pe un ecran îngust.
                            val ideal = if (spec.isTv) tvTitleHeroHeight(maxHeight, spec).coerceAtLeast(150.dp) else spec.heroHeight
                            val posterSpace = if (state.mode.isDay) spec.posterHeight + 20.dp else tvHomeRowHeight(spec)
                            val limit = if (spec.isTv) (maxHeight - posterSpace).coerceAtLeast(0.dp) else maxHeight * 0.55f
                            val heroHeight = ideal.coerceAtMost(limit)
                            Column(Modifier.fillMaxSize()) {
                                CalendarHero(shown, heroHeight, logoLanguage, showDate = !state.mode.isDay, loadLogo = viewModel::titleLogo)
                                if (state.mode.isDay) {
                                    DayView(grouped, dayRow, itemActions, onFocus)
                                } else {
                                    WeekList(
                                        state.days, grouped, weekList, state.tab.noun, itemActions, onFocus,
                                        skipEmpty = state.mode == CalendarMode.UPCOMING,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    WatchChooserHost(chooser)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarToolbar(
    state: CalendarUiState,
    viewModel: CalendarViewModel,
    showFilters: Boolean,
    onFilters: (Boolean) -> Unit,
) {
    val spec = layoutSpec()
    val selectors: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val centered = Modifier.align(Alignment.CenterVertically)
            Dropdown(null, state.tab, CalendarTab.entries, { it.label }, viewModel::setTab, centered, dialogTitle = "Arată")
            Dropdown(null, state.mode, CalendarMode.entries, { it.label }, viewModel::setMode, centered, dialogTitle = "Perioada")
            val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            OutlinedButton(onClick = { onFilters(true) }, modifier = centered, contentPadding = padding) { Text("Filtre") }
            OutlinedButton(onClick = viewModel::reload, modifier = centered, contentPadding = padding) { Text("Reîncarcă") }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = spec.screenPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 740.dp) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    selectors()
                    CalendarDateControls(state, viewModel, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectors()
                    CalendarDateControls(state, viewModel, Modifier.fillMaxWidth())
                }
            }
        }
        if (showFilters) CalendarFilters(state, viewModel) { onFilters(false) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun CalendarDateControls(state: CalendarUiState, viewModel: CalendarViewModel, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::previous) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Perioada anterioară")
        }
        Text(
            when (state.mode) {
                CalendarMode.TODAY, CalendarMode.DAY -> dayLabel(state.from)
                CalendarMode.WEEK -> weekLabel(state.from, state.to)
                CalendarMode.MONTH -> monthLabel(state.from)
                CalendarMode.UPCOMING -> "De la ${dayLabel(state.from)}, $UPCOMING_DAYS zile"
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        IconButton(onClick = viewModel::next) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Perioada următoare")
        }
        OutlinedButton(
            onClick = viewModel::goToToday,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) { Text("Azi") }
    }
}

@Composable
private fun Summary(state: CalendarUiState) {
    val s = state.summary
    val parts = buildList {
        add("${s.count} ${state.tab.noun}")
        add("ora României (≈ = estimat)")
        if (s.rejected > 0) add("${s.rejected} sub prag")
        if (s.hiddenByCategory > 0) add("${s.hiddenByCategory} din categorii ascunse")
        if (s.hiddenByUser > 0) add("${s.hiddenByUser} ascunse de tine")
        if (state.onlyFollowed) add("doar ce urmărești")
        if (state.onlyMyApps && s.notOnMyApps > 0) add("${s.notOnMyApps} pe aplicații pe care nu le ai")
        add(if (s.usedImdb) "note IMDb (OMDb)" else "note TMDb")
        add("ține apăsat pentru „Alege unde vezi”")
    }
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 6.dp),
    )
}

@Composable
private fun Info(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = layoutSpec().screenPadding, vertical = 12.dp),
    )
}

/** Filtrele calendarului ca listă: fiecare rând comută filtrul (Pornit / Oprit), lista rămâne deschisă. */
@Composable
private fun CalendarFilters(state: CalendarUiState, viewModel: CalendarViewModel, onDismiss: () -> Unit) {
    fun onOff(v: Boolean) = if (v) "Pornit" else "Oprit"
    val kinds = if (state.tab.includesMovies) MovieReleaseKind.entries else emptyList()
    val options = buildList {
        add(ChoiceOption("Doar ce urmăresc", onOff(state.onlyFollowed)))
        add(
            ChoiceOption(
                if (state.tab.includesMovies) "Streaming doar pe aplicațiile mele" else "Doar aplicațiile mele",
                onOff(state.onlyMyApps),
            ),
        )
        add(ChoiceOption("Arată și ce nu trece pragurile", onOff(state.showRejected)))
        kinds.forEachIndexed { i, kind ->
            add(ChoiceOption(kind.short, onOff(kind in state.movieKinds), heading = if (i == 0) "Tipuri de lansare" else null))
        }
    }
    ChoiceDialog(
        title = "Filtre",
        options = options,
        selected = null,
        dismissOnPick = false,
        onDismiss = onDismiss,
        onPick = { index ->
            when (index) {
                0 -> viewModel.toggleOnlyFollowed()
                1 -> viewModel.toggleOnlyMyApps()
                2 -> viewModel.toggleShowRejected()
                else -> kinds.getOrNull(index - 3)?.let(viewModel::toggleMovieKind)
            }
        },
        footer = "OK comută filtrul. Înapoi închide lista.",
    )
}
