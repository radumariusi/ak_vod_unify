@file:OptIn(ExperimentalFoundationApi::class)

package ro.ak.vodunify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
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
