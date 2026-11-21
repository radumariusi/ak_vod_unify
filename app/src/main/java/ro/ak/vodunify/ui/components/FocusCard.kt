package ro.ak.vodunify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.theme.FocusBorder

/** Mărirea cardurilor la focus: destul cât să se vadă, fără să iasă din spațiul dintre carduri (ca în AK VOD Hub). */
const val CARD_FOCUS_SCALE = 1.05f

/** Grosimea conturului de focus: vizibil de la 3 m pe orice poster sau fundal. */
val FocusBorderWidth = 3.dp

/**
 * Card care funcționează și cu D-pad (focus vizibil: mărire + contur alb) și cu touch (tap).
 * [restoreKey]: cheia cardului; la întoarcerea dintr-o fișă deschisă de pe acest card, focusul revine aici.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    restoreKey: String? = null,
    onFocused: (() -> Unit)? = null,
    focusScale: Float = CARD_FOCUS_SCALE,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) focusScale else 1f, label = "cardScale")
    val restore = LocalFocusRestore.current
    val isTv = layoutSpec().isTv
    val requester = remember { FocusRequester() }
    if (restoreKey != null && restore.pending == restoreKey && restore.current == restore.origin) {
        LaunchedEffect(restoreKey) {
            withFrameNanos { } // după prima așezare a cardului
            if (runCatching { requester.requestFocus() }.isSuccess) restore.pending = null
        }
    }
    Surface(
        onClick = {
            if (restoreKey != null && isTv) restore.remember(restoreKey)
            onClick()
        },
        modifier = modifier
            .scale(scale)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (focused) BorderStroke(FocusBorderWidth, FocusBorder) else null,
        interactionSource = interaction,
    ) {
        content()
    }
}
