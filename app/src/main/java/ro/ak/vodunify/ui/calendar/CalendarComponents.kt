package ro.ak.vodunify.ui.calendar

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
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
import java.time.DayOfWeek
import java.time.LocalDate
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
        border = if (focused) BorderStroke(3.dp, Color.White) else null,
        tonalElevation = if (focused) 8.dp else 0.dp,
    ) {
        content()
    }
}

@Composable
private fun Poster(item: CalendarItem, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (item.posterUrl != null) {
            AsyncImage(model = item.posterUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
        }
        if (item.watched) {
            Text(
                "✓ Văzut",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.TopStart).background(Color(0xCC1B5E20)).padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Vederea pe zi: rânduri mari, cu acțiunile titlului alături. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayList(
    items: List<CalendarItem>,
    listState: LazyListState,
    onOpen: (CalendarItem) -> Unit,
    onWatch: (CalendarItem) -> Unit,
    onFollow: (CalendarItem) -> Unit,
    onHide: (CalendarItem) -> Unit,
    onWatched: (CalendarItem) -> Unit,
) {
    val spec = layoutSpec()
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.key }) { item ->
            Column(Modifier.alpha(if (item.passed) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PressableCard(onClick = { onOpen(item) }, onLongClick = { onWatch(item) }, focusScale = 1.02f, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Poster(item, Modifier.width(if (spec.isCompact) 64.dp else 80.dp).height(if (spec.isCompact) 96.dp else 120.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${item.time} · ${item.where}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            if (item.details.isNotEmpty()) {
                                Text(
                                    item.details.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = { onWatch(item) }) { Text("▶ Alege unde vezi") }
                    OutlinedButton(onClick = { onWatched(item) }) { Text(if (item.watched) "✓ Văzut" else "Marchează văzut") }
                    OutlinedButton(onClick = { onFollow(item) }) { Text(if (item.followed) "★ Urmărești" else "Urmăresc") }
                    if (!item.followed) OutlinedButton(onClick = { onHide(item) }) { Text("Ascunde") }
                }
            }
        }
    }
}

/** Vederea pe săptămână: o zi pe rând, postere în rând orizontal. */
@Composable
fun WeekList(
    days: List<LocalDate>,
    items: List<CalendarItem>,
    listState: LazyListState,
    noun: String,
    onOpen: (CalendarItem) -> Unit,
    onWatch: (CalendarItem) -> Unit,
) {
    val spec = layoutSpec()
    val byDay = items.groupBy { it.date }
    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        days.forEach { day ->
            val entries = byDay[day].orEmpty()
            item(key = "day-$day") {
                Text(
                    dayLabel(day) + if (entries.isEmpty()) " · nimic" else " · ${entries.size} $noun",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = spec.screenPadding).padding(top = 8.dp),
                )
            }
            if (entries.isNotEmpty()) {
                item(key = "row-$day") {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
                    ) {
                        items(entries, key = { it.key }) { item ->
                            PressableCard(
                                onClick = { onOpen(item) },
                                onLongClick = { onWatch(item) },
                                modifier = Modifier.width(spec.posterWidth + 10.dp).alpha(if (item.passed) 1f else 0.5f),
                            ) {
                                Column {
                                    Poster(item, Modifier.fillMaxWidth().height(spec.posterHeight))
                                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            (if (item.isMovie) item.subtitle else item.subtitle.substringBefore(" · ")) + " · " + item.time,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            item.where,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
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

private val weekDays = listOf("Lun", "Mar", "Mie", "Joi", "Vin", "Sâm", "Dum")

/**
 * Vederea pe lună: săptămâni pe rânduri, luni prima zi. Fiecare zi arată primele titluri
 * (apăsare = pagina titlului, apăsare lungă = Alege unde vezi); numărul zilei deschide ziua.
 */
@Composable
fun MonthGrid(
    from: LocalDate,
    to: LocalDate,
    items: List<CalendarItem>,
    listState: LazyListState,
    onOpen: (CalendarItem) -> Unit,
    onWatch: (CalendarItem) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
) {
    val spec = layoutSpec()
    val perDay = if (spec.isCompact) 2 else 3
    val byDay = items.groupBy { it.date }
    val start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val end = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val weeks = generateSequence(start) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList()
    val today = RomaniaTime.today()
    val gap = if (spec.isCompact) 3.dp else 6.dp

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(gap),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "weekdays") {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                weekDays.forEach { Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)) }
            }
        }
        weeks.forEach { monday ->
            item(key = "week-$monday") {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    (0L..6L).map { monday.plusDays(it) }.forEach { day ->
                        val inMonth = !day.isBefore(from) && !day.isAfter(to)
                        val dayItems = if (inMonth) byDay[day].orEmpty() else emptyList()
                        Column(
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.weight(1f).heightIn(min = if (spec.isCompact) 120.dp else 190.dp).alpha(if (inMonth) 1f else 0.35f),
                        ) {
                            PressableCard(onClick = { onOpenDay(day) }, onLongClick = { onOpenDay(day) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    day.dayOfMonth.toString() + (if (day == today && !spec.isCompact) " · azi" else "") +
                                        (if (dayItems.isNotEmpty()) " (${dayItems.size})" else ""),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                )
                            }
                            dayItems.take(perDay).forEach { item ->
                                PressableCard(
                                    onClick = { onOpen(item) },
                                    onLongClick = { onWatch(item) },
                                    modifier = Modifier.fillMaxWidth().alpha(if (item.passed) 1f else 0.5f),
                                ) {
                                    Text(
                                        (if (item.watched) "✓ " else "") + item.shortLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                                    )
                                }
                            }
                            if (dayItems.size > perDay) {
                                Text(
                                    "+${dayItems.size - perDay}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
