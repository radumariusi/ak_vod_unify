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
 * Pe TV, în Acasă: se vede doar rândul cu focus (cu titlul lui) și, sub el, titlul rândului următor; nici
 * începutul posterelor de dedesubt, nici partea de jos a rândului de deasupra. Rândurile își raportează
 * marginile când au focusul; lista se taie acolo.
 */
class RowPeek {
    /** Marginea de sus a rândului cu focus, în coordonatele ecranului; null = niciun rând cu focus. */
    var focusedTop by mutableStateOf<Float?>(null)

    /** Marginea de jos a rândului cu focus, în coordonatele ecranului; null = niciun rând cu focus. */
    var focusedBottom by mutableStateOf<Float?>(null)
}

val LocalRowPeek = staticCompositionLocalOf<RowPeek?> { null }

/** Pe un rând (titlu + postere): îi spune listei unde începe și unde se termină cât timp are focusul. */
fun Modifier.peekRow(): Modifier = composed {
    val peek = LocalRowPeek.current ?: return@composed this
    var focused by remember { mutableStateOf(false) }
    var top by remember { mutableStateOf<Float?>(null) }
    var bottom by remember { mutableStateOf<Float?>(null) }
    this
        .onFocusChanged { s ->
            focused = s.hasFocus
            if (s.hasFocus) {
                top?.let { peek.focusedTop = it }
                bottom?.let { peek.focusedBottom = it }
            }
        }
        .onGloballyPositioned { c ->
            val t = c.positionInRoot().y
            val b = t + c.size.height
            top = t
            bottom = b
            if (focused) {
                peek.focusedTop = t
                peek.focusedBottom = b
            }
        }
}

/**
 * Pe lista din Acasă: desenează doar rândul cu focus și, sub el, titlul rândului următor ([titleHeight] = cât
 * ocupă un titlu de rând). Fără rând cu focus (ex. focus în meniu), desenează tot.
 */
fun Modifier.clipBelowNextTitle(peek: RowPeek, titleHeight: Dp): Modifier = composed {
    var top by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val titlePx = with(density) { titleHeight.toPx() }
    this
        .onGloballyPositioned { top = it.positionInRoot().y }
        .drawWithContent {
            val b = peek.focusedBottom
            if (b == null) {
                drawContent()
            } else {
                val rowTop = peek.focusedTop?.let { (it - top).coerceAtLeast(0f) } ?: 0f
                clipRect(top = rowTop, bottom = (b - top + titlePx).coerceAtMost(size.height)) {
                    this@drawWithContent.drawContent()
                }
            }
        }
}
