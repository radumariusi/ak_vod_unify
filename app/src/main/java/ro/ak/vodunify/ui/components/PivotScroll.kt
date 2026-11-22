@file:OptIn(ExperimentalFoundationApi::class)

package ro.ak.vodunify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Derulare verticală „cu pivot”, ca pe TV (preluată din AK VOD Hub): rândul cu focus ajunge mereu la aceeași
 * înălțime ([pivot] de la marginea de sus), deci se vede întreg, cu titlul lui, și nu „sare” diferit de la un
 * poster la altul. Rândurile orizontale din interior păstrează derularea obișnuită (vezi [DefaultScroll]).
 */
@Composable
fun PivotScroll(pivot: Dp, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val pivotPx = with(density) { pivot.toPx() }
    // Toleranța: mărirea cardului cu focus (1.05) și conturul îi schimbă puțin marginile; fără ea, fiecare
    // pas stânga / dreapta în rând ar mișca rândul cu câțiva pixeli („țopăie”). E inegală: rândul poate sta
    // puțin mai jos de pivot, dar aproape deloc mai sus, altfel titlul rândului (deasupra posterelor) se taie.
    val belowPx = with(density) { 20.dp.toPx() }
    val abovePx = with(density) { 6.dp.toPx() }
    val default = LocalBringIntoViewSpec.current
    val spec = remember(pivotPx, belowPx, abovePx, default) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (size + pivotPx > containerSize) return default.calculateScrollDistance(offset, size, containerSize)
                val distance = offset - pivotPx
                val visible = offset >= 0f && offset + size <= containerSize
                return if (visible && distance >= -abovePx && distance <= belowPx) 0f else distance
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalDefaultScroll provides default) { content() }
}

private val LocalDefaultScroll = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/** Readuce derularea obișnuită pentru un rând orizontal aflat într-un [PivotScroll]. */
@Composable
fun DefaultScroll(content: @Composable () -> Unit) {
    val default = LocalDefaultScroll.current
    if (default == null) {
        content()
    } else {
        CompositionLocalProvider(LocalBringIntoViewSpec provides default) { content() }
    }
}

/**
 * Acasă pe TV: rânduri cu poziții fixe. Fiecare rând ocupă exact [slotHeight] (titlu + postere + spațiu), oricât
 * de înalte îi sunt cardurile (16:9 sau postere), iar lista arată un singur rând și, dedesubt, titlul celui
 * următor. Rândul care primește focusul e adus sus (titlul lui mereu în același loc), pe loc sau animat ([animate]).
 */
class HomeRows(
    val slotHeight: Dp,
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val animate: Boolean,
) {
    /** Marginea de sus a listei, în coordonatele ecranului. */
    var listTop: Float = 0f

    fun bringToTop(rowTop: Float) {
        val delta = rowTop - listTop
        if (abs(delta) < 1f) return
        scope.launch {
            if (animate) state.animateScrollBy(delta) else state.scrollBy(delta)
        }
    }
}

val LocalHomeRows = staticCompositionLocalOf<HomeRows?> { null }

/** Lista din Acasă (TV): nu se derulează singură după focus; rândurile se aduc sus prin [HomeRows]. */
@Composable
fun FixedRowsScroll(content: @Composable () -> Unit) {
    val default = LocalBringIntoViewSpec.current
    val none = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides none, LocalDefaultScroll provides default) { content() }
}

/** Pe lista din Acasă: ține minte unde e lista pe ecran, ca rândurile să se poată alinia sus. */
fun Modifier.homeRowsList(rows: HomeRows): Modifier = onGloballyPositioned { rows.listTop = it.positionInRoot().y }

/**
 * Pe un rând (titlu + postere) din Acasă, pe TV: înălțime fixă și, când primește focusul, rândul urcă sus.
 * În afara Acasă (sau pe telefon) nu face nimic.
 */
fun Modifier.peekRow(): Modifier = composed {
    val rows = LocalHomeRows.current ?: return@composed this
    var top by remember { mutableStateOf<Float?>(null) }
    var pending by remember { mutableStateOf(false) }
    this
        .height(rows.slotHeight)
        .onFocusChanged { s ->
            if (s.hasFocus) {
                val t = top
                if (t != null) rows.bringToTop(t) else pending = true
            }
        }
        .onGloballyPositioned { c ->
            val t = c.positionInRoot().y
            top = t
            if (pending) {
                pending = false
                rows.bringToTop(t)
            }
        }
}
