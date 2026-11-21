package ro.ak.vodunify.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextStyle
import ro.ak.vodunify.ui.theme.FocusBorder
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ro.ak.vodunify.ui.components.readable

/**
 * Card de setări cu titlu (aspectul comun al secțiunilor). [collapsible]: cardul stă închis (doar titlul și
 * [summary]) și se deschide la OK, ca să nu derulezi prin liste lungi.
 */
@Composable
fun SettingsCard(title: String, collapsible: Boolean = false, summary: String? = null, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().widthIn(max = 900.dp),
    ) {
        if (collapsible) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Expandable(title, summary, titleStyle = MaterialTheme.typography.titleMedium) {
                    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
                }
            }
        } else {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }
}

/**
 * Listă pliabilă: un rând cu titlul (și [summary], ex. câte sunt) care se deschide / închide la OK.
 * Pornește închisă; merge la fel cu telecomanda și cu degetul.
 */
@Composable
fun Expandable(
    title: String,
    summary: String? = null,
    initiallyOpen: Boolean = false,
    titleStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = { open = !open },
            shape = MaterialTheme.shapes.small,
            color = if (focused) c.secondary else c.surfaceVariant.copy(alpha = 0.6f),
            contentColor = if (focused) c.onSecondary else c.onSurface,
            border = if (focused) BorderStroke(2.dp, FocusBorder) else null,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = titleStyle, fontWeight = FontWeight.SemiBold)
                    summary?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (focused) c.onSecondary else c.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = if (open) "Închide" else "Deschide")
            }
        }
        if (open) content()
    }
}

@Composable
fun SettingHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.readable())
}
