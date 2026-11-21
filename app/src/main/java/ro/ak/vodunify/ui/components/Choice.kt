package ro.ak.vodunify.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.theme.FocusBorder
import androidx.compose.ui.window.Dialog

/** O opțiune dintr-o listă de alegere. [enabled] = false: afișată (ex. „neinstalat”), dar nu se poate alege. */
data class ChoiceOption(
    val label: String,
    val detail: String? = null,
    val enabled: Boolean = true,
    /** Titlu de grup afișat deasupra opțiunii (ex. „Servicii”, „Playere”). */
    val heading: String? = null,
)

/**
 * Lista de alegere (ca în AK VOD Hub): o fereastră cu opțiunile una sub alta, focusul pe cea curentă.
 * Înlocuiește șirurile de butoane: merge la fel cu D-pad și cu atingere.
 */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<ChoiceOption>,
    selected: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    footer: String? = null,
    /** False: alegerea nu închide singură lista (o închide cine o primește, ex. după pornirea aplicației). */
    dismissOnPick: Boolean = true,
) {
    val focus = remember { FocusRequester() }
    val firstEnabled = options.indexOfFirst { it.enabled }.coerceAtLeast(0)
    val initial = selected?.takeIf { it in options.indices && options[it].enabled } ?: firstEnabled
    Dialog(onDismissRequest = onDismiss) {
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                LazyColumn(
                    state = rememberLazyListState(initialFirstVisibleItemIndex = initial),
                    modifier = Modifier.heightIn(max = 420.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(options) { index, option ->
                        Column {
                            option.heading?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { heading() }.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                            ChoiceRow(
                                option = option,
                                selected = index == selected,
                                modifier = if (index == initial) Modifier.focusRequester(focus) else Modifier,
                                onClick = {
                                    onPick(index)
                                    if (dismissOnPick) onDismiss()
                                },
                            )
                        }
                    }
                }
                footer?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(option: ChoiceOption, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = option.enabled,
        shape = MaterialTheme.shapes.small,
        color = when {
            focused -> c.secondary
            selected -> c.surfaceVariant
            else -> Color.Transparent
        },
        contentColor = when {
            focused -> c.onSecondary
            option.enabled -> c.onSurface
            else -> c.onSurfaceVariant
        },
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(option.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                option.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (focused) c.onSecondary else c.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Check, contentDescription = "Selectat", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Câmp de alegere (drop-down): eticheta, valoarea curentă și ▾. La OK / atingere deschide lista.
 * [label] null = doar valoarea (ex. în bara de filtre).
 */
@Composable
fun <T> Dropdown(
    label: String?,
    value: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    dialogTitle: String = label ?: "Alege",
    optionDetail: (T) -> String? = { null },
    optionEnabled: (T) -> Boolean = { true },
    fillWidth: Boolean = label != null,
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = { open = true },
        shape = MaterialTheme.shapes.small,
        color = if (focused) c.secondary else c.surfaceVariant.copy(alpha = 0.85f),
        contentColor = if (focused) c.onSecondary else c.onSurface,
        border = if (focused) BorderStroke(2.dp, FocusBorder) else BorderStroke(1.dp, c.outline),
        interactionSource = interaction,
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth().widthIn(max = 640.dp) else Modifier)
            .semantics { role = Role.DropdownList },
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (label != null) {
                Text(
                    label,
                    color = if (focused) c.onSecondary else c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = true),
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(optionLabel(value), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
    if (open) {
        ChoiceDialog(
            title = dialogTitle,
            options = options.map { ChoiceOption(optionLabel(it), optionDetail(it), optionEnabled(it)) },
            selected = options.indexOf(value).takeIf { it >= 0 },
            onPick = { onSelect(options[it]) },
            onDismiss = { open = false },
        )
    }
}

/** Comutator pornit / oprit ca listă cu două opțiuni (aceeași formă ca restul setărilor). */
@Composable
fun OnOffDropdown(label: String, value: Boolean, onSelect: (Boolean) -> Unit, on: String = "Pornit", off: String = "Oprit") {
    Dropdown(label, value, listOf(true, false), { if (it) on else off }, onSelect)
}
