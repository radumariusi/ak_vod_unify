package ro.ak.vodunify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    restoreKey: String? = null,
    onFocused: (() -> Unit)? = null,
    focusScale: Float = CARD_FOCUS_SCALE,
    /** Apăsare lungă (ex. meniul rapid al titlului); null = doar apăsare. */
    onLongClick: (() -> Unit)? = null,
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
    val click: () -> Unit = {
        if (restoreKey != null && isTv) restore.remember(restoreKey)
        onClick()
    }
    val base = modifier
        .scale(scale)
        .focusRequester(requester)
        .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
    val border = if (focused) BorderStroke(FocusBorderWidth, FocusBorder) else null
    if (onLongClick == null) {
        Surface(
            onClick = click,
            modifier = base,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = border,
            interactionSource = interaction,
        ) {
            content()
        }
        return
    }
    // Cu apăsare lungă: tap / ținut apăsat pe telefon, OK / OK ținut apăsat pe telecomandă.
    val currentClick by rememberUpdatedState(click)
    val currentLong by rememberUpdatedState(onLongClick)
    var longFired by remember { mutableStateOf(false) }
    Surface(
        modifier = base
            .onPreviewKeyEvent { e ->
                if (e.key != Key.DirectionCenter && e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount > 0 && !longFired) {
                            longFired = true
                            currentLong()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (!longFired) currentClick()
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { currentClick() },
                onLongClick = { currentLong() },
            ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = border,
    ) {
        content()
    }
}
