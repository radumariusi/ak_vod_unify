package ro.ak.vodunify.ui.calendar

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import ro.ak.vodunify.ui.components.PosterLabel
import ro.ak.vodunify.ui.components.PosterBottomRow
import ro.ak.vodunify.ui.components.PosterRatingChips
import ro.ak.vodunify.ui.components.ViewingStatusBadge
import ro.ak.vodunify.ui.components.FocusBorderWidth
import ro.ak.vodunify.ui.components.restoreCardFocus
import ro.ak.vodunify.ui.components.rememberRestoringRowState
import ro.ak.vodunify.ui.theme.FocusBorder
import ro.ak.vodunify.ui.components.AppIconBadge
import ro.ak.vodunify.ui.components.CinemaBadges
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import ro.ak.vodunify.ui.components.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ro.ak.vodunify.core.calendar.format.dayLabel
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.DefaultScroll
import ro.ak.vodunify.ui.components.FixedRowsScroll
import ro.ak.vodunify.ui.components.HomeRowFocus
import ro.ak.vodunify.ui.components.HomeRows
import ro.ak.vodunify.ui.components.LocalHomeRows
import ro.ak.vodunify.ui.components.returningFocus
import ro.ak.vodunify.ui.components.rowSpacing
import ro.ak.vodunify.ui.components.tvHomeRowHeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.ak.vodunify.core.prefs.UiPrefs
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.TemporalAdjusters

private val confirmKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Card cu apăsare și apăsare lungă, atât pe telefon (tap / ținut apăsat) cât și pe TV
 * (OK / OK ținut apăsat pe telecomandă). Focus vizibil: scalare + contur alb.
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusScale: Float = 1.05f,
    restoreKey: String? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "calendarCardScale")
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    var longFired by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .scale(scale)
            .restoreCardFocus(restoreKey)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick { click(); true }
                onLongClick { longClick(); true }
            }
            .onKeyEvent { e ->
                if (e.key !in confirmKeys) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount > 0 && !longFired) {
                            longFired = true
                            longClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val wasLong = longFired || (e.nativeKeyEvent.flags and AndroidKeyEvent.FLAG_CANCELED_LONG_PRESS) != 0
                        longFired = false
                        if (!wasLong) click()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { click() }, onLongPress = { longClick() })
            }
            .focusable(interactionSource = interaction),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        // Același focus ca la toate cardurile (FocusCard): contur alb, fără altă umbră.
        border = if (focused) BorderStroke(FocusBorderWidth, FocusBorder) else null,
    ) {
        content()
    }
}

/** Siglele de pe postere (pachetele aplicațiilor instalate), date de ecranul Calendar. */
val LocalCalendarIcons = staticCompositionLocalOf { CalendarIcons() }
internal val LocalCalendarNow = compositionLocalOf { Instant.now() }

/**
 * Sus sunt notele și ora primei apariții sau starea vizionării. Jos sunt sigla și episodul sau tipul lansării.
 */
@Composable
internal fun Poster(item: CalendarItem, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (item.posterUrl != null) {
            AsyncImage(model = item.posterUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
        }
        CalendarPosterHeader(item, Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(4.dp))
        // Jos, ca pe toate posterele: sigla (aplicația ta sau cinema RO / SUA) lângă episod sau tip.
        PosterBottomRow(Modifier.align(Alignment.BottomStart).padding(4.dp), label = item.posterLabel) {
            CalendarPlatformBadge(item, includeDefaultPlayer = true)
        }
    }
}

@Composable
internal fun CalendarPlatformBadge(item: CalendarItem, includeDefaultPlayer: Boolean = false) {
    val icons = LocalCalendarIcons.current
    if (item.cinemaRegions.isNotEmpty()) {
        CinemaBadges(item.cinemaRegions)
    } else {
        val pkg = item.watchAppId?.let { icons.apps[it] } ?: icons.player.takeIf { includeDefaultPlayer }
        pkg?.let { AppIconBadge(it) }
    }
}

@Composable
internal fun CalendarRatings(item: CalendarItem) {
    val episode = item.episode
    val movie = item.movie
    val tmdb = episode?.tmdbRating?.takeIf { episode.tmdbVotes > 0 }
        ?: movie?.tmdbRating?.takeIf { movie.tmdbVotes > 0 }
    PosterRatingChips(tmdb, episode?.imdb?.rating ?: movie?.imdb?.rating)
}

@Composable
private fun CalendarPosterHeader(item: CalendarItem, modifier: Modifier = Modifier) {
    val now = LocalCalendarNow.current
    val time = item.upcomingPosterTime(now)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(Modifier.weight(1f)) {
            CalendarRatings(item)
        }
        when {
            time.isNotEmpty() -> PosterLabel(time)
            !now.isBefore(item.releaseAt) && (item.watched || item.inProgress) ->
                ViewingStatusBadge(watched = item.watched)
        }
    }
}

/** Aceeași interacțiune cu posterele pe Zi, Săptămână și Următoare. */
@Composable
internal fun CalendarPosterCard(
    item: CalendarItem,
    restoreKey: String,
    onFocus: (CalendarItem) -> Unit,
    onOpen: (CalendarItem) -> Unit,
    onLongClick: (CalendarItem) -> Unit,
) {
    val spec = layoutSpec()
    PressableCard(
        onClick = {
            onFocus(item)
            onOpen(item)
        },
        onLongClick = {
            onFocus(item)
            onLongClick(item)
        },
        restoreKey = restoreKey,
        modifier = Modifier
            .size(spec.posterWidth, spec.posterHeight)
            .alpha(if (item.passed) 1f else 0.5f)
            .onFocusChanged { if (it.isFocused) onFocus(item) }
            .semantics { contentDescription = "${item.title}, ${item.subtitle}, ${item.time}, ${item.where}" },
    ) {
        Poster(item, Modifier.fillMaxSize())
    }
}

/** Vederea pe săptămână: o zi pe rând, postere în rând orizontal. */
@Composable
internal fun CalendarPosterRow(
    entries: List<CalendarItem>,
    rowKey: String,
    actions: CalendarItemActions,
    onFocus: (CalendarItem) -> Unit,
    onLongClick: (CalendarItem) -> Unit,
    state: LazyListState = rememberRestoringRowState(rowKey, entries) { it.key },
) {
    val spec = layoutSpec()
    DefaultScroll {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
            contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
        ) {
            items(entries, key = { it.key }) { item ->
                CalendarPosterCard(item, "$rowKey|${item.key}", onFocus, actions.open, onLongClick)
            }
        }
    }
}

@Composable
internal fun WeekList(
    days: List<LocalDate>,
    items: List<CalendarItem>,
    listState: LazyListState,
    noun: String,
    actions: CalendarItemActions,
    onFocus: (CalendarItem) -> Unit,
    skipEmpty: Boolean = false,
) {
    val spec = layoutSpec()
    val byDay = remember(items) { items.groupBy { it.date } }
    val shownDays = remember(days, byDay, skipEmpty) { if (skipEmpty) days.filter { it in byDay } else days }
    val keys = remember(shownDays, byDay) { shownDays.map { day -> "calendar-week|$day".takeIf { day in byDay } } }
    val slot = tvHomeRowHeight(spec)
    val scope = rememberCoroutineScope()
    val rows = remember(slot, listState, scope) { HomeRows(slot, listState, scope) }
    val animate by UiPrefs.rowAnimation.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<CalendarItem?>(null) }

    val returning = returningFocus()
    val returnRow = returning?.homeRow ?: returning?.cardKey?.substringBeforeLast('|')
    val returnIndex = keys.indexOf(returnRow).takeIf { returnRow != null }
    LaunchedEffect(returning, returnIndex) {
        if (returnIndex != null && returnIndex >= 0) {
            val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == returnIndex }
            if (!visible || (spec.isTv && (listState.firstVisibleItemIndex != returnIndex ||
                        listState.firstVisibleItemScrollOffset != 0))) listState.scrollToItem(returnIndex)
        }
    }

    FixedRowsScroll(spec.isTv) {
        CompositionLocalProvider(LocalHomeRows provides rows.takeIf { spec.isTv }) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(if (spec.isTv) 0.dp else 8.dp),
                contentPadding = if (spec.isTv) PaddingValues(bottom = slot) else PaddingValues(vertical = 8.dp),
                modifier = Modifier.fillMaxSize().clipToBounds()
                    .onPreviewKeyEvent { spec.isTv && rows.move(it, keys, animate) },
            ) {
                itemsIndexed(shownDays, key = { _, day -> "calendar-week|$day" }) { index, day ->
                    val entries = byDay[day].orEmpty()
                    val rowKey = "calendar-week|$day"
                    val content: @Composable () -> Unit = {
                        val fixedHeight = if (entries.isEmpty()) 40.dp else slot
                        Column(if (spec.isTv) Modifier.height(fixedHeight) else Modifier) {
                            val count = entries.sumOf { it.episodeMembers.size.coerceAtLeast(1) }
                            Box(
                                Modifier.fillMaxWidth().padding(horizontal = spec.screenPadding)
                                    .then(if (spec.isTv) Modifier.height(40.dp) else Modifier.padding(top = 8.dp)),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    dayLabel(day) + if (entries.isEmpty()) " · nimic" else " · $count $noun",
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (entries.isNotEmpty()) {
                                CalendarPosterRow(entries, rowKey, actions, onFocus, onLongClick = {
                                    if (it.episodeMembers.isEmpty()) actions.watch(it) else menu = it
                                })
                            }
                        }
                    }
                    if (entries.isEmpty()) content() else HomeRowFocus(rowKey, index, content)
                }
            }
        }
    }
    menu?.let { item -> CalendarItemMenu(item, actions) { menu = null } }
}
