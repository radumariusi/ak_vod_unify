package ro.ak.vodunify.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ro.ak.vodunify.core.calendar.format.weekLabel
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.core.prefs.UiPrefs
import ro.ak.vodunify.ui.components.FixedRowsScroll
import ro.ak.vodunify.ui.components.CinemaBadges
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.returningFocus
import ro.ak.vodunify.ui.theme.Accent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields

internal fun monthMonday(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun monthDayKey(date: LocalDate): String = "calendar-day|$date"
internal fun monthWeekKey(monday: LocalDate): String = "calendar-month-week|$monday"

internal fun monthWeekWindow(current: Int, target: Int, visibleRows: Int, totalRows: Int): Int {
    val start = when {
        target < current -> target
        target >= current + visibleRows -> target - visibleRows + 1
        else -> current
    }
    return start.coerceIn(0, (totalRows - visibleRows).coerceAtLeast(0))
}

internal fun monthWeekNumber(monday: LocalDate): Int = monday.get(WeekFields.ISO.weekOfWeekBasedYear())

private data class MonthWeek(val monday: LocalDate, val from: LocalDate, val to: LocalDate) {
    val key get() = monthWeekKey(monday)
    fun day(column: Int): LocalDate = monday.plusDays(column.toLong()).coerceIn(from, to)
}

private data class MonthFocus(val date: LocalDate, val week: Boolean = false) {
    val monday get() = monthMonday(date)
    val key get() = if (week) monthWeekKey(monday) else monthDayKey(date)

    companion object {
        fun fromKey(key: String?): MonthFocus? {
            val date = key?.substringAfter('|', "")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return when (key.substringBefore('|')) {
                "calendar-day" -> MonthFocus(date)
                "calendar-month-week" -> MonthFocus(date, week = true)
                else -> null
            }
        }
    }
}

private val shortWeekdays = listOf("Lun.", "Mar.", "Mie.", "Joi", "Vin.", "Sâm.", "Dum.")

@Composable
internal fun MonthCalendar(
    from: LocalDate,
    to: LocalDate,
    items: List<CalendarItem>,
    listState: LazyListState,
    restoreKey: String?,
    selectedDate: String?,
    onSelectDate: (LocalDate) -> Unit,
    onFocusRestored: () -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    onOpenWeek: (LocalDate) -> Unit,
    logoLanguage: String?,
    loadLogo: suspend (CalendarItem) -> String?,
    modifier: Modifier = Modifier,
) {
    val spec = layoutSpec()
    val rows by UiPrefs.calendarMonthRows.collectAsStateWithLifecycle()
    val animate by UiPrefs.rowAnimation.collectAsStateWithLifecycle()
    val now = LocalCalendarNow.current
    val today = RomaniaTime.today()
    val weeks = remember(from, to) {
        generateSequence(monthMonday(from)) { it.plusWeeks(1) }.takeWhile { it <= to }
            .map { MonthWeek(it, maxOf(it, from), minOf(it.plusDays(6), to)) }.toList()
    }
    val titlesByDay = remember(items, now) { items.groupBy { it.date }.mapValues { monthPreview(it.value, now) } }
    val selectedDay = remember(selectedDate, from, to) {
        selectedDate?.let(LocalDate::parse)?.takeIf { it in from..to } ?: from
    }
    var focus by remember(from) { mutableStateOf<MonthFocus?>(null) }
    val requesters = remember(weeks) {
        buildMap {
            weeks.forEach { week ->
                put(week.key, FocusRequester())
                for (column in 0..6) {
                    val date = week.monday.plusDays(column.toLong())
                    if (date in week.from..week.to) put(monthDayKey(date), FocusRequester())
                }
            }
        }
    }
    val restored = returningFocus()
    val target = MonthFocus.fromKey(restoreKey ?: restored?.cardKey)
    val scope = rememberCoroutineScope()
    var moving by remember(from) { mutableStateOf<Job?>(null) }

    LaunchedEffect(target, from, rows) {
        val index = weeks.indexOfFirst { it.monday == monthMonday(target?.date ?: selectedDay) }
        if (index < 0) return@LaunchedEffect
        val start = monthWeekWindow(listState.firstVisibleItemIndex, index, rows, weeks.size)
        if (listState.firstVisibleItemIndex != start || listState.firstVisibleItemScrollOffset != 0) listState.scrollToItem(start)
        if (spec.isTv && target != null && restoreKey != null) {
            repeat(4) {
                withFrameNanos { }
                if (focus == target) return@LaunchedEffect
                runCatching { requesters[target.key]?.requestFocus() }
            }
        } else if (target != null && restoreKey != null) {
            onFocusRestored()
        }
    }

    val gap = if (spec.isCompact) 4.dp else 8.dp
    val weekWidth = if (spec.isCompact) 26.dp else 42.dp
    val verticalPadding = 6.dp
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = spec.screenPadding)) {
        val cellWidth = ((maxWidth - weekWidth - gap * 7) / 7).coerceAtLeast(0.dp)
        val cellPadding = if (spec.isCompact) 3.dp else 6.dp
        val stackedHeading = cellWidth < 67.dp
        val headingHeight = if (stackedHeading) 31.dp else 24.dp
        val posterGap = 3.dp
        val insideWidth = (cellWidth - cellPadding * 2).coerceAtLeast(0.dp)
        val slots = if (insideWidth >= 76.dp) 3 else 2
        val baseHeight = cellPadding * 2 + headingHeight + 3.dp
        val preferredPosterWidth = ((insideWidth - posterGap * (slots - 1)) / slots).coerceIn(0.dp, 50.dp)
        val gridGaps = gap * (rows - 1)
        val available = (maxHeight - verticalPadding * 2 - gridGaps).coerceAtLeast(0.dp)
        val reservedLogos = minOf(
            if (spec.isTv) 105.dp else 94.dp, available * 0.28f,
            (available - (baseHeight - 3.dp) * rows).coerceAtLeast(0.dp),
        )
        val weekHeight = minOf(baseHeight + preferredPosterWidth * 1.5f, (available - reservedLogos) / rows)
        val gridHeight = with(LocalDensity.current) { (weekHeight.roundToPx() * rows + gap.roundToPx() * (rows - 1)).toDp() }
        val posterWidth = minOf(preferredPosterWidth, ((weekHeight - baseHeight) / 1.5f).coerceAtLeast(0.dp))

        Column(Modifier.fillMaxSize().padding(vertical = verticalPadding)) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 3.dp), contentAlignment = Alignment.CenterStart) {
                val monday = focus?.takeIf { it.week }?.monday
                val titles = if (monday == null) titlesByDay[selectedDay].orEmpty() else emptyList()
                MonthTitleLogos(titles, logoLanguage, loadLogo)
                if (monday != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Săptămâna ${monthWeekNumber(monday)}", style = MaterialTheme.typography.labelLarge)
                        Text(weekLabel(monday, monday.plusDays(6)), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            FixedRowsScroll(spec.isTv) {
                LazyColumn(
                    state = listState,
                    userScrollEnabled = !spec.isTv,
                    flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Start),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxWidth().height(gridHeight).clipToBounds()
                        .onPreviewKeyEvent { event ->
                            if (!spec.isTv || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val step = when (event.key) {
                                Key.DirectionUp -> -1
                                Key.DirectionDown -> 1
                                else -> return@onPreviewKeyEvent false
                            }
                            if (moving?.isActive == true) return@onPreviewKeyEvent true
                            val current = focus ?: return@onPreviewKeyEvent false
                            val nextIndex = weeks.indexOfFirst { it.monday == current.monday } + step
                            val week = weeks.getOrNull(nextIndex) ?: return@onPreviewKeyEvent (step > 0)
                            val next = if (current.week) MonthFocus(week.monday, week = true)
                                else MonthFocus(week.day(current.date.dayOfWeek.value - 1))
                            val requester = requesters.getValue(next.key)
                            val start = monthWeekWindow(listState.firstVisibleItemIndex, nextIndex, rows, weeks.size)
                            if (start == listState.firstVisibleItemIndex) {
                                requester.requestFocus()
                            } else {
                                moving = scope.launch {
                                    if (animate) listState.animateScrollToItem(start) else listState.scrollToItem(start)
                                    repeat(4) {
                                        withFrameNanos { }
                                        runCatching { requester.requestFocus() }
                                        if (focus == next) return@launch
                                    }
                                }
                            }
                            true
                        },
                ) {
                    items(weeks, key = { it.key }) { week ->
                        Row(Modifier.fillMaxWidth().height(weekHeight), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            val number = monthWeekNumber(week.monday)
                            val openWeek = { onOpenWeek(week.monday) }
                            val weekFocus = MonthFocus(week.monday, week = true)
                            PressableCard(
                                onClick = openWeek, onLongClick = openWeek, focusScale = 1f, restoreKey = week.key,
                                modifier = Modifier.width(weekWidth).height(weekHeight)
                                    .focusRequester(requesters.getValue(week.key))
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focus = weekFocus
                                            if (restoreKey == week.key) onFocusRestored()
                                        } else if (focus == weekFocus) {
                                            focus = null
                                        }
                                    }
                                    .semantics { contentDescription = "Săptămâna $number" },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("$number", style = MaterialTheme.typography.titleSmall)
                                }
                            }

                            for (column in 0..6) {
                                val day = week.monday.plusDays(column.toLong())
                                Box(Modifier.weight(1f).height(weekHeight)) {
                                    if (day in from..to) {
                                        val cellKey = monthDayKey(day)
                                        MonthDayCell(
                                            day, titlesByDay[day].orEmpty(), day == today, !spec.isTv && day == selectedDay,
                                            cellPadding, headingHeight, stackedHeading, slots, posterWidth, posterGap,
                                            modifier = Modifier.focusRequester(requesters.getValue(cellKey)).onFocusChanged {
                                                if (it.isFocused) {
                                                    focus = MonthFocus(day)
                                                    onSelectDate(day)
                                                    if (restoreKey == cellKey) onFocusRestored()
                                                }
                                            },
                                            onClick = {
                                                if (spec.isTv || selectedDay == day) onOpenDay(day) else onSelectDate(day)
                                            },
                                            onOpen = {
                                                onSelectDate(day)
                                                onOpenDay(day)
                                            },
                                        )
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
private fun MonthDayCell(
    day: LocalDate,
    items: List<CalendarItem>,
    isToday: Boolean,
    selected: Boolean,
    padding: Dp,
    headingHeight: Dp,
    stackedHeading: Boolean,
    slots: Int,
    posterWidth: Dp,
    posterGap: Dp,
    modifier: Modifier,
    onClick: () -> Unit,
    onOpen: () -> Unit,
) {
    val weekday = shortWeekdays[day.dayOfWeek.value - 1]
    val shown = if (posterWidth > 0.dp) slots else 0
    val remaining = (items.size - shown).coerceAtLeast(0)
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dayColor = if (isToday) Accent else MaterialTheme.colorScheme.onSurface
    PressableCard(
        onClick = onClick, onLongClick = onOpen, focusScale = 1f, restoreKey = monthDayKey(day),
        modifier = modifier.fillMaxSize()
            .then(if (isToday || selected) Modifier.border(BorderStroke(2.dp, Accent), RoundedCornerShape(10.dp)) else Modifier)
            .semantics { contentDescription = "${day.dayOfMonth} ($weekday): ${items.size} titluri" },
    ) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth().height(headingHeight), verticalAlignment = Alignment.CenterVertically) {
                    if (stackedHeading) {
                        Text(
                            "${day.dayOfMonth}\n($weekday)", fontSize = 11.sp, lineHeight = 13.sp,
                            fontWeight = FontWeight.Bold, color = dayColor, modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            "${day.dayOfMonth} ($weekday)", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, color = dayColor, modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        if (remaining > 0) Text("+$remaining", style = MaterialTheme.typography.labelSmall, color = badgeColor)
                    }
                }
                if (shown > 0 && items.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(posterGap)) {
                        items.take(shown).forEach { item ->
                            Box(
                                Modifier.width(posterWidth).aspectRatio(2f / 3f)
                                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
                                    .alpha(if (item.passed) 1f else 0.5f),
                            ) {
                                if (item.posterUrl == null) {
                                    Text(item.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                } else {
                                    AsyncImage(item.posterUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                }
                                if (item.cinemaRegions.size > 1 && posterWidth > 4.dp) {
                                    CinemaBadges(
                                        item.cinemaRegions,
                                        Modifier.align(Alignment.BottomStart).padding(1.dp),
                                        availableWidth = posterWidth - 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (stackedHeading && remaining > 0) {
                Text(
                    "+$remaining", fontSize = 9.sp, lineHeight = 10.sp, color = badgeColor,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}
