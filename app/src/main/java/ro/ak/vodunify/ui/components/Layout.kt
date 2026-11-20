package ro.ak.vodunify.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dimensiuni adaptive: TV (10-foot UI) vs. telefon (touch). */
data class LayoutSpec(
    val isTv: Boolean,
    val isCompact: Boolean,
    val screenPadding: Dp,
    val cardWidth: Dp,
    val cardHeight: Dp,
    val heroHeight: Dp,
)

val LocalLayoutSpec = compositionLocalOf { layoutSpecFor(isTv = false, widthDp = 400) }

fun layoutSpecFor(isTv: Boolean, widthDp: Int): LayoutSpec {
    val compact = !isTv && widthDp < 600
    return when {
        isTv -> LayoutSpec(true, false, 48.dp, 196.dp, 110.dp, 320.dp)
        compact -> LayoutSpec(false, true, 16.dp, 140.dp, 80.dp, 220.dp)
        else -> LayoutSpec(false, false, 32.dp, 180.dp, 100.dp, 280.dp)
    }
}

@Composable
fun layoutSpec(): LayoutSpec = LocalLayoutSpec.current
