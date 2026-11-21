package ro.ak.vodunify.ui.calendar

import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.calendar.format.dayLabel
import ro.ak.vodunify.core.calendar.format.monthLabel
import ro.ak.vodunify.core.calendar.format.weekLabel
import ro.ak.vodunify.core.calendar.model.MovieReleaseKind
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.ui.components.ErrorPanel
import ro.ak.vodunify.ui.components.layoutSpec
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
    viewModel: CalendarViewModel = hiltViewModel(),
    chooser: WatchChooserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val actions = LocalCatalogActions.current
    val context = LocalContext.current
    val spec = layoutSpec()

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

    val dayList = rememberLazyListState()
    val weekList = rememberLazyListState()
    val monthList = rememberLazyListState()
    var shownPeriod by rememberSaveable { mutableStateOf("") }
    val period = "${state.tab}-${state.mode}-${state.from}"
    LaunchedEffect(period) {
        // Înapoi sus când se schimbă perioada, dar nu la întoarcerea din pagina unui titlu.
        if (shownPeriod.isNotEmpty() && shownPeriod != period) {
            dayList.scrollToItem(0)
            weekList.scrollToItem(0)
            monthList.scrollToItem(0)
        }
        shownPeriod = period
    }

    val open: (CalendarItem) -> Unit = { actions.openTitle(if (it.isMovie) TitleKind.MOVIE else TitleKind.TV, it.tmdbId) }
    val chooseWhere: (CalendarItem) -> Unit = viewModel::chooseWhere
    var showFilters by rememberSaveable { mutableStateOf(false) }

    val icons by viewModel.icons.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalCalendarIcons provides icons) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = spec.screenPadding).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Dropdown(null, state.tab, CalendarTab.entries, { it.label }, viewModel::setTab, dialogTitle = "Arată")
                Dropdown(null, state.mode, CalendarMode.entries, { it.label }, viewModel::setMode, dialogTitle = "Perioada")
                OutlinedButton(onClick = { showFilters = true }) { Text("Filtre") }
                OutlinedButton(onClick = viewModel::reload) { Text("Reîncarcă") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = viewModel::previous) { Text("◀") }
                Text(
                    when (state.mode) {
                        CalendarMode.DAY -> dayLabel(state.from)
                        CalendarMode.WEEK -> weekLabel(state.from, state.to)
                        CalendarMode.MONTH -> monthLabel(state.from)
                        CalendarMode.UPCOMING -> "De la ${dayLabel(state.from)}, $UPCOMING_DAYS zile"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                OutlinedButton(onClick = viewModel::next) { Text("▶") }
                OutlinedButton(onClick = viewModel::goToToday) { Text("Azi") }
            }
            if (showFilters) CalendarFilters(state, viewModel) { showFilters = false }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val error = state.error
        when {
            error != null && state.passed == null -> ErrorPanel(error, onRetry = viewModel::reload, modifier = Modifier.padding(spec.screenPadding))
            state.passed == null -> Info(
                if (state.mode == CalendarMode.DAY) "Se încarcă…" else "Se încarcă perioada… (prima dată durează mai mult)",
            )
            else -> {
                Summary(state)
                if (!state.hasMyApps) Info("Sfat: aplicațiile tale și biblioteca fiecăreia (RO / US) se iau din Setări → Aplicații și servicii.")
                val noun = if (state.tab == CalendarTab.MOVIES) "filme" else "episoade"
                when {
                    state.visible.isEmpty() -> Info("Nimic în perioada asta după filtrele tale.")
                    state.mode == CalendarMode.DAY -> DayList(
                        items = state.visible,
                        listState = dayList,
                        onOpen = open,
                        onWatch = chooseWhere,
                        onFollow = viewModel::toggleFollow,
                        onHide = viewModel::hide,
                        onWatched = viewModel::markWatched,
                        onList = viewModel::addToList,
                    )
                    state.mode == CalendarMode.WEEK -> WeekList(state.days, state.visible, weekList, noun, open, chooseWhere)
                    state.mode == CalendarMode.UPCOMING -> WeekList(state.days, state.visible, weekList, noun, open, chooseWhere, skipEmpty = true)
                    else -> MonthGrid(state.from, state.to, state.visible, monthList, open, chooseWhere, viewModel::openDay)
                }
            }
        }
    }
    }
    WatchChooserHost(chooser)
}

@Composable
private fun Summary(state: CalendarUiState) {
    val s = state.summary
    val movies = state.tab == CalendarTab.MOVIES
    val parts = buildList {
        add(if (movies) "${s.count} lansări" else "${s.count} episoade")
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
    val kinds = if (state.tab == CalendarTab.MOVIES) MovieReleaseKind.entries else emptyList()
    val options = buildList {
        add(ChoiceOption("Doar ce urmăresc", onOff(state.onlyFollowed)))
        add(
            ChoiceOption(
                if (state.tab == CalendarTab.MOVIES) "Streaming doar pe aplicațiile mele" else "Doar aplicațiile mele",
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
