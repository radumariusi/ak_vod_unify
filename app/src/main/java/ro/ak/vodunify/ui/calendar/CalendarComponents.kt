package ro.ak.vodunify.ui.calendar

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import ro.ak.vodunify.ui.components.PosterLabel
import ro.ak.vodunify.ui.components.PosterBottomRow
import ro.ak.vodunify.ui.components.FocusBorderWidth
import ro.ak.vodunify.ui.theme.FocusBorder
import ro.ak.vodunify.ui.components.AppIconBadge
import ro.ak.vodunify.ui.components.CinemaBadge
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
import ro.ak.vodunify.ui.components.OutlinedButton
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
        // Același focus ca la toate cardurile (FocusCard): contur alb, fără altă umbră.
        border = if (focused) BorderStroke(FocusBorderWidth, FocusBorder) else null,
    ) {
        content()
    }
}

/** Siglele de pe postere (pachetele aplicațiilor instalate), date de ecranul Calendar. */
val LocalCalendarIcons = staticCompositionLocalOf { CalendarIcons() }

/**
 * Posterul din calendar, ca restul posterelor din aplicație: fără titlu scris. Sus, sigla platformei tale pe care
 * îl găsești (sau a playerului standard, dacă nu e pe platformele tale) ori sigla de cinema US / RO; jos, episodul
 * și ora României (la filme, ora sau tipul lansării). „✓” = văzut.
 */
@Composable
internal fun Poster(item: CalendarItem, modifier: Modifier) {
    val icons = LocalCalendarIcons.current
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
        if (item.posterUrl != null) {
            AsyncImage(model = item.posterUrl, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(item.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
        }
        if (item.watched) {
            PosterLabel("✓", Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        // Ora, separat, sus în dreapta: jos, lângă siglă și episod, nu încăpea întreagă.
        if (item.posterTime.isNotBlank()) {
            PosterLabel(item.posterTime, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        // Jos, ca pe toate posterele: sigla (aplicația ta sau cinema RO / SUA) lângă episod / oră.
        PosterBottomRow(Modifier.align(Alignment.BottomStart).padding(4.dp), label = item.posterLabel) {
            val cinema = item.cinema
            if (cinema != null) {
                CinemaBadge(cinema)
            } else {
                val pkg = item.watchAppId?.let { icons.apps[it] } ?: icons.player
                pkg?.let { AppIconBadge(it) }
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
    /** „Următoare”: zilele fără nimic nu se afișează. */
    skipEmpty: Boolean = false,
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
            if (skipEmpty && entries.isEmpty()) return@forEach
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
                        horizontalArrangement = Arrangement.spacedBy(ro.ak.vodunify.ui.components.rowSpacing()),
                        contentPadding = PaddingValues(horizontal = spec.screenPadding, vertical = 8.dp),
                    ) {
                        items(entries, key = { it.key }) { item ->
                            PressableCard(
                                onClick = { onOpen(item) },
                                onLongClick = { onWatch(item) },
                                modifier = Modifier
                                    .size(spec.posterWidth, spec.posterHeight)
                                    .alpha(if (item.passed) 1f else 0.5f)
                                    .semantics { contentDescription = "${item.title}, ${item.subtitle}, ${item.time}, ${item.where}" },
                            ) {
                                Poster(item, Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}
