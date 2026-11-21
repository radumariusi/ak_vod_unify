package ro.ak.vodunify.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.theme.FocusBorder
import androidx.compose.ui.window.Dialog

/**
 * Casetă de text care merge cu telecomanda: pe TV e un câmp de alegere obișnuit (treci peste el fără să apară
 * tastatura și fără să-ți „prindă” focusul); abia la OK se deschide o fereastră cu caseta și tastatura.
 * Pe telefon e caseta obișnuită.
 * [onDone]: apăsat pe „Gata” / „Caută” pe tastatură (valoarea e deja trimisă prin [onValueChange]).
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val spec = layoutSpec()
    if (!spec.isTv) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            label = { Text(label) },
            placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onDone() }),
            modifier = modifier,
        )
        return
    }

    var editing by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Surface(
        onClick = { editing = true },
        shape = MaterialTheme.shapes.small,
        color = if (focused) c.secondary else c.surfaceVariant.copy(alpha = 0.85f),
        contentColor = if (focused) c.onSecondary else c.onSurface,
        border = if (focused) BorderStroke(2.dp, FocusBorder) else BorderStroke(1.dp, c.outline),
        interactionSource = interaction,
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (focused) c.onSecondary else c.onSurfaceVariant,
                )
                val shown = when {
                    value.isEmpty() -> placeholder.ifEmpty { "Apasă OK ca să scrii" }
                    secret -> "•".repeat(value.length.coerceAtMost(16))
                    else -> value
                }
                Text(shown, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.Edit, contentDescription = null)
        }
    }
    if (editing) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val close = {
            keyboard?.hide()
            editing = false
        }
        Dialog(onDismissRequest = close) {
            LaunchedEffect(Unit) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
            Surface(shape = MaterialTheme.shapes.large, color = c.surface, modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = imeAction),
                        keyboardActions = KeyboardActions(onAny = {
                            close()
                            onDone()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            close()
                            onDone()
                        }) { Text("Gata") }
                        OutlinedButton(onClick = { onValueChange("") }) { Text("Șterge") }
                    }
                }
            }
        }
    }
}
