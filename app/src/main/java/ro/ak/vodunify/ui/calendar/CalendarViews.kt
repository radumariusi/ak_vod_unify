package ro.ak.vodunify.ui.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import ro.ak.vodunify.core.calendar.time.RomaniaTime
import ro.ak.vodunify.ui.components.ChoiceDialog
import ro.ak.vodunify.ui.components.ChoiceOption
import ro.ak.vodunify.ui.components.TitleBackdrop
import ro.ak.vodunify.ui.components.layoutSpec
import ro.ak.vodunify.ui.components.rowSpacing
import ro.ak.vodunify.ui.theme.Accent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** Ce poți face cu un titlu din calendar (OK ținut apăsat / apăsare lungă). */
class CalendarItemActions(
    val open: (CalendarItem) -> Unit,
    val watch: (CalendarItem) -> Unit,
    val watched: (CalendarItem) -> Unit,
    val list: (CalendarItem) -> Unit,
    val follow: (CalendarItem) -> Unit,
    val hide: (CalendarItem) -> Unit,
)

/** Meniul unui titlu din calendar, ca meniul rapid din Acasă: o listă, nu un șir de butoane. */
@Composable
fun CalendarItemMenu(item: CalendarItem, actions: CalendarItemActions, onDismiss: () -> Unit) {
    val entries = buildList<Pair<ChoiceOption, (CalendarItem) -> Unit>> {
        add(ChoiceOption("Deschide pagina titlului") to actions.open)
        add(ChoiceOption("Alege unde vezi") to actions.watch)
        add(ChoiceOption(if (item.watched) "Văzut ✓ (anulează)" else "Marchează văzut") to actions.watched)
        add(ChoiceOption("Adaugă în Lista mea") to actions.list)
        add(ChoiceOption(if (item.followed) "Nu mai urmări" else "Urmăresc", "Apare mereu în calendar, oricare ar fi filtrele") to actions.follow)
        if (!item.followed) add(ChoiceOption("Ascunde din calendar") to actions.hide)
    }
    ChoiceDialog(
        title = item.title,
        options = entries.map { it.first },
        selected = null,
        onPick = { i -> entries[i].second(item) },
        onDismiss = onDismiss,
    )
}

/**
 * Vederea pe zi, ca Acasă: sus, pe fundalul panoramic al titlului cu focus, numele, episodul, ora, unde îl vezi și
 * descrierea; dedesubt, posterele zilei (aceleași ca peste tot). OK = pagina titlului, OK ținut = meniul titlului.
 */
@Composable
fun DayView(items: List<CalendarItem>, gridState: LazyGridState, actions: CalendarItemActions) {
    val spec = layoutSpec()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<CalendarItem?>(null) }
    val shown = items.firstOrNull { it.key == focusedKey } ?: items.firstOrNull()
    Box(Modifier.fillMaxSize()) {
        TitleBackdrop(shown?.backdropUrl, Modifier.fillMaxWidth().height(if (spec.isTv) 230.dp else 200.dp))
        Column(Modifier.fillMaxSize()) {
            DayInfo(shown, Modifier.fillMaxWidth().height(if (spec.isTv) 150.dp else 140.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(spec.posterWidth + 8.dp),
                state = gridState,
                contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(rowSpacing()),
                verticalArrangement = Arrangement.spacedBy(rowSpacing()),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.key }) { item ->
                    PressableCard(
                        onClick = { actions.open(item) },
                        onLongClick = { menu = item },
                        modifier = Modifier
                            .size(spec.posterWidth, spec.posterHeight)
                            .alpha(if (item.passed) 1f else 0.5f)
                            .onFocusChanged { if (it.hasFocus) focusedKey = item.key }
                            .semantics { contentDescription = "${item.title}, ${item.subtitle}, ${item.time}, ${item.where}" },
                    ) {
                        Poster(item, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
    menu?.let { item -> CalendarItemMenu(item, actions) { menu = null } }
}

/** Panoul de sus din vederea pe zi: titlul cu focus, ca hero-ul din Acasă. */
@Composable
private fun DayInfo(item: CalendarItem?, modifier: Modifier) {
    val spec = layoutSpec()
    Column(
        modifier
            .padding(horizontal = spec.screenPadding, vertical = 8.dp)
            .widthIn(max = 640.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
    ) {
        if (item == null) return@Column
        Text(
            item.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(item.subtitle, item.time, item.where).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.details.isNotEmpty()) {
            Text(
                item.details.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.overview.isNotBlank()) {
            Text(
                item.overview,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Vederea pe lună, ca un calendar cu imagini: o zi = o celulă cu numărul zilei și posterele mici ale titlurilor
 * (cel mult trei, suprapuse), plus câte sunt. Azi e marcat cu roșu. Un singur focus pe zi: OK deschide ziua.
 */
@Composable
fun MonthCalendar(
    from: LocalDate,
    to: LocalDate,
    items: List<CalendarItem>,
    listState: LazyListState,
    onOpenDay: (LocalDate) -> Unit,
) {
    val spec = layoutSpec()
    val byDay = remember(items) { items.groupBy { it.date } }
    val start = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val end = to.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    val weeks = remember(start, end) { generateSequence(start) { it.plusWeeks(1) }.takeWhile { !it.isAfter(end) }.toList() }
    val today = RomaniaTime.today()
    val gap = if (spec.isCompact) 4.dp else 8.dp
    val cellHeight = if (spec.isCompact) 96.dp else 118.dp
    val ro = remember { Locale("ro", "RO") }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(gap),
        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "weekdays") {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                DayOfWeek.entries.forEach { d ->
                    Text(
                        d.getDisplayName(TextStyle.SHORT, ro).replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
            }
        }
        weeks.forEach { monday ->
            item(key = "week-$monday") {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    (0L..6L).map { monday.plusDays(it) }.forEach { day ->
                        val inMonth = !day.isBefore(from) && !day.isAfter(to)
                        val dayItems = if (inMonth) byDay[day].orEmpty() else emptyList()
                        Box(Modifier.weight(1f).height(cellHeight)) {
                            if (inMonth) {
                                MonthCell(day, dayItems, isToday = day == today, onOpen = { onOpenDay(day) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(day: LocalDate, items: List<CalendarItem>, isToday: Boolean, onOpen: () -> Unit) {
    PressableCard(
        onClick = onOpen,
        onLongClick = onOpen,
        focusScale = 1.04f,
        modifier = Modifier
            .fillMaxSize()
            .then(if (isToday) Modifier.border(BorderStroke(2.dp, Accent), RoundedCornerShape(10.dp)) else Modifier)
            .semantics { contentDescription = "${day.dayOfMonth}: ${items.size} titluri" },
    ) {
        Column(Modifier.fillMaxSize().padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.dayOfMonth.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isToday) Accent else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (items.isNotEmpty()) {
                    Text(
                        items.size.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Posterele mici ale zilei, suprapuse ușor (primele trei).
            if (items.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    items.take(3).forEachIndexed { i, item ->
                        val posterWidth = 44.dp
                        Box(
                            Modifier
                                .offset(x = (posterWidth * 0.62f) * i)
                                .width(posterWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .alpha(if (item.passed) 1f else 0.5f),
                        ) {
                            if (item.posterUrl != null) {
                                AsyncImage(
                                    model = item.posterUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
