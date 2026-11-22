package ro.ak.vodunify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.theme.GlassActive
import ro.ak.vodunify.ui.theme.RowShape

/**
 * Text lung (descrierea din fișă): întreg dacă încape în [collapsedLines] rânduri; altfel tăiat și terminat cu
 * „… Arată mai mult” (sau „Show more” la descrierile în engleză), îngroșat. Textul e un buton: OK / atingere
 * îl deschide întreg (și „Arată mai puțin” îl strânge la loc).
 */
@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    collapsedLines: Int = 3,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    // Unde se taie textul ca „… Arată mai mult” să încapă în ultimul rând; null = încape întreg.
    var cut by remember(text) { mutableStateOf<Int?>(null) }
    var measured by remember(text) { mutableStateOf(false) }
    val romanian = remember(text) { text.any { it in "ăâîșțşţĂÂÎȘȚ" } }
    val more = if (romanian) "Arată mai mult" else "Show more"
    val less = if (romanian) "Arată mai puțin" else "Show less"
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Pe TV, când derulezi la descriere (primește focusul), ea se deschide întreagă; când pleci, se strânge la loc.
    val tv = layoutSpec().isTv
    var openedByFocus by remember(text) { mutableStateOf(false) }
    LaunchedEffect(focused, tv) {
        if (!tv) return@LaunchedEffect
        if (focused && !expanded && cut != null) {
            expanded = true
            openedByFocus = true
        } else if (!focused && openedByFocus) {
            expanded = false
            openedByFocus = false
        }
    }
    val c = cut
    val canToggle = c != null || expanded

    val shown = when {
        expanded -> buildAnnotatedString {
            append(text)
            append("  ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(less) }
        }
        c != null -> buildAnnotatedString {
            append(text.substring(0, c).trimEnd().trimEnd(',', '.', ';', ':'))
            append("… ")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(more) }
        }
        else -> buildAnnotatedString { append(text) }
    }
    Text(
        shown,
        style = style,
        color = if (focused) MaterialTheme.colorScheme.onSecondary else color,
        maxLines = if (expanded || measured) Int.MAX_VALUE else collapsedLines,
        onTextLayout = { layout ->
            if (!measured && !expanded) {
                if (layout.hasVisualOverflow) {
                    val end = layout.getLineEnd(collapsedLines - 1, visibleEnd = true)
                    cut = (end - more.length - 8).coerceAtLeast(0)
                }
                measured = true
            }
        },
        modifier = modifier
            .then(
                if (canToggle) {
                    Modifier
                        .background(if (focused) MaterialTheme.colorScheme.secondary else Color.Transparent, RowShape)
                        .clickable(interactionSource = interaction, indication = null) {
                            expanded = !expanded
                            openedByFocus = false
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                } else {
                    Modifier
                },
            ),
    )
}
