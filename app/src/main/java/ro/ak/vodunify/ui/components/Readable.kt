package ro.ak.vodunify.ui.components

import ro.ak.vodunify.ui.theme.GlassIdle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Text care se poate citi cu telecomanda: pe TV primește focus (cu un contur discret), deci sus / jos trece
 * prin el și pagina se derulează până la el, în loc să sară de la un buton la altul peste textul lung.
 * Pe telefon nu schimbă nimic.
 */
fun Modifier.readable(): Modifier = composed {
    if (!layoutSpec().isTv) return@composed this
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    val c = MaterialTheme.colorScheme
    this
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .background(if (focused) GlassIdle else Color.Transparent, shape)
        .border(if (focused) 2.dp else 0.dp, if (focused) c.secondary else Color.Transparent, shape)
        .padding(horizontal = 4.dp, vertical = 2.dp)
}
