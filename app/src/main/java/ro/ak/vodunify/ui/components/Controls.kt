package ro.ak.vodunify.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import ro.ak.vodunify.ui.theme.FocusFill
import ro.ak.vodunify.ui.theme.GlassActive
import ro.ak.vodunify.ui.theme.GlassIdle
import ro.ak.vodunify.ui.theme.OnFocusFill
import ro.ak.vodunify.ui.theme.PillShape
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.FilterChip as M3FilterChip
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.TextButton as M3TextButton

/*
 * Butoanele și chip-urile aplicației, în aceeași identitate ca meniul principal: capsule din „sticlă” albă
 * transparentă cu text alb; cu focus (D-pad) se umplu cu alb și textul devine bleumarin închis.
 * Aceleași semnături ca în Material3, deci funcționează la fel la atingere.
 */

@Composable
private fun rememberFocus(): Pair<MutableInteractionSource, Boolean> {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    return interaction to focused
}

/** Culorile comune: [idle] în repaus, alb plin la focus. */
@Composable
private fun pillColors(focused: Boolean, idle: Color, idleContent: Color = Color.White) = ButtonDefaults.buttonColors(
    containerColor = if (focused) FocusFill else idle,
    contentColor = if (focused) OnFocusFill else idleContent,
    disabledContainerColor = GlassIdle.copy(alpha = 0.06f),
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
)

/** Textul butoanelor: îngroșat, ca filele din meniul principal. */
@Composable
private fun SemiBold(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontWeight = FontWeight.SemiBold), content = content)
}

/** Acțiunea principală: sticlă mai deschisă. */
@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    M3Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        colors = pillColors(focused, GlassActive),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = { val row = this; SemiBold { row.content() } },
    )
}

/** Acțiune secundară: sticlă discretă, fără contur. */
@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    active: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    M3Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        colors = pillColors(focused, if (active) GlassActive else GlassIdle),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = { val row = this; SemiBold { row.content() } },
    )
}

/** Stare activă (ex. „✓ În Lista mea”): ca fila curentă din meniu. */
@Composable
fun FilledTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier, enabled, contentPadding, content)

/** Acțiune discretă: doar text alb în repaus, alb plin la focus. */
@Composable
fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    M3TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (focused) FocusFill else Color.Transparent,
            contentColor = if (focused) OnFocusFill else Color.White,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = { val row = this; SemiBold { row.content() } },
    )
}

/** Buton rotund cu iconiță (ca norul / rotița din meniul principal). */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    M3IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (focused) FocusFill else GlassIdle,
            contentColor = if (focused) OnFocusFill else Color.White,
        ),
        interactionSource = interaction,
        content = content,
    )
}

/** Opțiune selectabilă: aleasă = sticlă deschisă și text îngroșat; în repaus = sticlă discretă; focus = alb. */
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
        focused -> FocusFill
        selected -> GlassActive
        else -> GlassIdle
    }
    val content = if (focused) OnFocusFill else Color.White
    M3FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            CompositionLocalProvider(
                LocalTextStyle provides LocalTextStyle.current.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
                content = label,
            )
        },
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        shape = PillShape,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = container,
            labelColor = content,
            iconColor = content,
            selectedContainerColor = container,
            selectedLabelColor = content,
            selectedLeadingIconColor = content,
            disabledContainerColor = GlassIdle.copy(alpha = 0.06f),
            disabledLabelColor = c.onSurfaceVariant.copy(alpha = 0.6f),
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = Color.Transparent,
            selectedBorderColor = Color.Transparent,
            borderWidth = 0.dp,
            selectedBorderWidth = 0.dp,
        ),
        interactionSource = interaction,
    )
}

/**
 * Rând de listă selectabil (sezoane, categorii): transparent în repaus, sticlă deschisă când e cel ales,
 * alb plin la focus. [content] primește starea de focus, ca textul să-și schimbe culoarea.
 */
@Composable
fun PillRow(
    onClick: () -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val (interaction, focused) = rememberFocus()
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ro.ak.vodunify.ui.theme.RowShape,
        color = when {
            focused -> FocusFill
            active -> GlassActive
            else -> Color.Transparent
        },
        contentColor = if (focused) OnFocusFill else Color.White,
        interactionSource = interaction,
    ) {
        content(focused)
    }
}
