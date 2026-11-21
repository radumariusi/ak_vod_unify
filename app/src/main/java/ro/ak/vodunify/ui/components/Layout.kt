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
    /** Postere verticale (2:3) pentru filme/seriale. */
    val posterWidth: Dp,
    val posterHeight: Dp,
)

val LocalLayoutSpec = compositionLocalOf { layoutSpecFor(isTv = false, widthDp = 400) }

fun layoutSpecFor(isTv: Boolean, widthDp: Int): LayoutSpec {
    val compact = !isTv && widthDp < 600
    return when {
        isTv -> LayoutSpec(true, false, 40.dp, 230.dp, 129.dp, 250.dp, 112.dp, 168.dp)
        compact -> LayoutSpec(false, true, 16.dp, 200.dp, 112.dp, 260.dp, 110.dp, 165.dp)
        else -> LayoutSpec(false, false, 32.dp, 220.dp, 124.dp, 320.dp, 130.dp, 195.dp)
    }
}

@Composable
fun layoutSpec(): LayoutSpec = LocalLayoutSpec.current
