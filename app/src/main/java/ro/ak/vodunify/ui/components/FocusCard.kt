package ro.ak.vodunify.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Card care funcționează și cu D-pad (focus vizibil: scalare + contur) și cu touch (tap).
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, label = "cardScale")
    Surface(
        onClick = onClick,
        modifier = modifier.scale(scale),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (focused) BorderStroke(3.dp, Color.White) else null,
        tonalElevation = if (focused) 8.dp else 0.dp,
        interactionSource = interaction,
    ) {
        content()
    }
}
