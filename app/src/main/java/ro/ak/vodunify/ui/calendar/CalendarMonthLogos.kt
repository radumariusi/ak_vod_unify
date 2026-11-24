package ro.ak.vodunify.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import ro.ak.vodunify.ui.components.layoutSpec
import kotlin.math.sqrt

internal data class MonthLogoGrid(val columns: Int, val width: Int, val height: Int, val gap: Int)

/** Caută configurația cu cele mai mari sigle care încap simultan în zona disponibilă. */
internal fun monthLogoGrid(count: Int, width: Int, height: Int, maxLogoHeight: Int, preferredGap: Int): MonthLogoGrid? {
    if (count <= 0 || width <= 0 || height <= 0 || maxLogoHeight <= 0) return null
    val gap = minOf(preferredGap, (minOf(width, height) / (2 * sqrt(count.toDouble()))).toInt())
    var best: MonthLogoGrid? = null
    for (columns in 1..minOf(count, width)) {
        val rows = (count + columns - 1) / columns
        val roomWidth = (width - gap * (columns - 1)) / columns
        val roomHeight = (height - gap * (rows - 1)) / rows
        val logoHeight = minOf(maxLogoHeight, roomHeight, (roomWidth / 2.6f).toInt())
        if (logoHeight > (best?.height ?: 0)) {
            best = MonthLogoGrid(columns, (logoHeight * 2.6f).toInt(), logoHeight, gap)
        }
    }
    return best
}

@Composable
internal fun MonthTitleLogos(
    titles: List<CalendarItem>,
    logoLanguage: String?,
    loadLogo: suspend (CalendarItem) -> String?,
) {
    val logos = remember(logoLanguage) { mutableStateMapOf<Pair<Boolean, Long>, String?>() }
    if (titles.isEmpty()) return
    val spec = layoutSpec()
    val ids = titles.map { it.isMovie to it.tmdbId }
    LaunchedEffect(ids, logoLanguage) {
        if (logoLanguage == null) return@LaunchedEffect
        delay(120)
        val requests = Semaphore(4)
        coroutineScope {
            titles.forEach { item ->
                val id = item.isMovie to item.tmdbId
                if (!logos.containsKey(id)) {
                    launch { requests.withPermit { logos[id] = loadLogo(item) } }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        val grid = with(density) {
            monthLogoGrid(
                titles.size, maxWidth.roundToPx(), maxHeight.roundToPx(),
                (if (spec.isTv) 53.dp else 40.dp).roundToPx(),
                (if (spec.isCompact) 4.dp else 8.dp).roundToPx(),
            )
        } ?: return@BoxWithConstraints
        val width = with(density) { grid.width.toDp() }
        val height = with(density) { grid.height.toDp() }
        val gap = with(density) { grid.gap.toDp() }
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            titles.chunked(grid.columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item ->
                        val id = item.isMovie to item.tmdbId
                        key(id) {
                            val url = logos[id]
                            var failed by remember(url) { mutableStateOf(false) }
                            Box(Modifier.size(width, height), contentAlignment = Alignment.CenterStart) {
                                if (url != null && !failed) {
                                    AsyncImage(
                                        model = url, contentDescription = item.title, contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterStart, modifier = Modifier.fillMaxSize(),
                                        onError = { failed = true },
                                    )
                                } else {
                                    val fontSize = (height.value / 2.25f).coerceIn(8f, if (spec.isTv) 18f else 14f)
                                    Text(
                                        item.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                                        fontSize = fontSize.sp, lineHeight = (fontSize * 1.12f).sp, maxLines = 2,
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
