package ro.ak.vodunify.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.core.calendar.format.dayLabel
import ro.ak.vodunify.ui.components.ChoiceDialog
import ro.ak.vodunify.ui.components.ChoiceOption
import ro.ak.vodunify.ui.components.TitleHero
import kotlinx.coroutines.delay

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
    var episodeAction by remember(item.key) { mutableStateOf<((CalendarItem) -> Unit)?>(null) }
    val selectedAction = episodeAction
    if (selectedAction != null) {
        ChoiceDialog(
            title = "Alege episodul",
            options = item.episodeMembers.map { episode ->
                ChoiceOption(episode.posterLabel, if (episode.watched) "Văzut ✓" else episode.episode?.episodeTitle)
            },
            selected = null,
            onPick = { selectedAction(item.episodeMembers[it]) },
            onDismiss = onDismiss,
        )
        return
    }
    val entries = buildList<Pair<ChoiceOption, (CalendarItem) -> Unit>> {
        add(ChoiceOption("Deschide pagina titlului") to actions.open)
        add(ChoiceOption("Alege unde vezi") to actions.watch)
        val watchedLabel = when {
            item.episodeMembers.isNotEmpty() -> "Schimbă starea unui episod"
            item.watched -> "Văzut ✓ (anulează)"
            else -> "Marchează văzut"
        }
        add(ChoiceOption(watchedLabel) to actions.watched)
        add(ChoiceOption("Adaugă în Lista mea") to actions.list)
        add(ChoiceOption(if (item.followed) "Nu mai urmări" else "Urmăresc", "Apare mereu în calendar, oricare ar fi filtrele") to actions.follow)
        if (!item.followed) add(ChoiceOption("Ascunde din calendar") to actions.hide)
    }
    ChoiceDialog(
        title = item.title,
        options = entries.map { it.first },
        selected = null,
        onPick = { i ->
            val action = entries[i].second
            if (item.episodeMembers.isNotEmpty() && (action == actions.watch || action == actions.watched)) {
                episodeAction = action
            } else {
                action(item)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
        dismissOnPick = false,
    )
}

/** Azi și Zi: posterele pot fi parcurse orizontal, în același rând. */
@Composable
internal fun DayView(
    items: List<CalendarItem>,
    rowState: LazyListState,
    actions: CalendarItemActions,
    onFocus: (CalendarItem) -> Unit,
) {
    var menu by remember { mutableStateOf<CalendarItem?>(null) }
    CalendarPosterRow(items, "calendar-poster", actions, onFocus, onLongClick = { menu = it }, state = rowState)
    menu?.let { item -> CalendarItemMenu(item, actions) { menu = null } }
}

/** Sigla și informațiile lansării folosesc același hero și aceeași sursă de sigle ca în Acasă. */
@Composable
internal fun CalendarHero(
    item: CalendarItem,
    height: Dp,
    logoLanguage: String?,
    showDate: Boolean,
    loadLogo: suspend (CalendarItem) -> String?,
) {
    var logo by remember(item.isMovie, item.tmdbId, logoLanguage) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.isMovie, item.tmdbId, logoLanguage) {
        if (logoLanguage == null) return@LaunchedEffect
        delay(120)
        logo = loadLogo(item).orEmpty()
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        TitleHero(
            title = item.title,
            logoUrl = logo?.ifEmpty { null },
            pendingLogo = logo == null,
            heroHeight = height,
        ) { descriptionLines ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarRatings(item)
                CalendarPlatformBadge(item)
                Text(
                    listOf(item.subtitle, item.time, item.where).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = if (showDate) listOf(dayLabel(item.date)) + item.details else item.details
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.overview.isNotBlank()) {
                Text(
                    item.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = when {
                        height < 165.dp -> 1
                        height < 190.dp -> minOf(2, descriptionLines)
                        else -> descriptionLines
                    },
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
