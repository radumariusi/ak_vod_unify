package ro.ak.vodunify.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.theme.FocusBorder
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.OutlinedButton as M3OutlinedButton
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Butoanele și chip-urile aplicației, cu focusul din AK VOD Hub: la focus (D-pad) se umplu cu
 * albastru-deschis și text închis, fără mărire. Material3 simplu arată focusul aproape invizibil pe TV.
 * Aceleași semnături ca în Material3, deci funcționează la fel la atingere.
 */

@Composable
private fun rememberFocus(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return interaction to focused
}

/** Acțiunea principală. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    M3Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) c.secondary else c.onBackground,
            contentColor = if (focused) c.onSecondary else c.background,
            disabledContainerColor = c.surfaceVariant,
            disabledContentColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}

/** Acțiune secundară: fundal discret, fără contur gros. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    M3OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (focused) c.secondary else c.surfaceVariant.copy(alpha = 0.85f),
            contentColor = if (focused) c.onSecondary else c.onSurface,
            disabledContainerColor = c.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        border = if (focused) BorderStroke(2.dp, FocusBorder) else BorderStroke(1.dp, c.outline),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}

/** Stare activă (ex. „✓ În De văzut”): plin, dar mai discret decât [Button]. */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    M3Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (focused) c.secondary else c.secondaryContainer,
            contentColor = if (focused) c.onSecondary else c.onSecondaryContainer,
            disabledContainerColor = c.surfaceVariant,
            disabledContentColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}

@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    M3TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (focused) c.secondary else Color.Transparent,
            contentColor = if (focused) c.onSecondary else c.secondary,
            disabledContentColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content,
    )
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    M3IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (focused) c.secondary else Color.Transparent,
            contentColor = if (focused) c.onSecondary else c.onSurface,
        ),
        interactionSource = interaction,
        content = content,
    )
}

/** Opțiune selectabilă: selectat = plin bleumarin cu text deschis; la focus = albastru-deschis. */
@Composable
fun FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val (interaction, focused) = rememberFocus()
    val c = MaterialTheme.colorScheme
    val container = when {
        focused -> c.secondary
        selected -> c.onBackground
        else -> c.surfaceVariant.copy(alpha = 0.85f)
    }
    val content = when {
        focused -> c.onSecondary
        selected -> c.background
        else -> c.onSurface
    }
    M3FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = content,
            iconColor = content,
            selectedContainerColor = container,
            selectedLabelColor = content,
            selectedLeadingIconColor = content,
            disabledContainerColor = c.surfaceVariant.copy(alpha = 0.4f),
            disabledLabelColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = if (focused) c.secondary else c.outline,
            selectedBorderColor = if (focused) c.secondary else c.onBackground,
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp,
        ),
        interactionSource = interaction,
    )
}
