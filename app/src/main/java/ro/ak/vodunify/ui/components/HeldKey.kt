package ro.ak.vodunify.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Pentru dialogurile deschise cu OK ținut apăsat (meniul titlului, „Alege unde vezi”): telecomanda continuă
 * să trimită OK cât timp butonul e ținut, iar ridicarea lui ar apăsa prima opțiune din dialog și l-ar închide.
 * Ignorăm OK-ul până la o apăsare nouă (fără repetare), deci dialogul rămâne deschis ca să alegi.
 */
fun Modifier.ignoreHeldOk(): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        when {
            !ok || armed -> false
            e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0 -> {
                armed = true
                false
            }
            else -> true // repetările și ridicarea OK-ului care a deschis dialogul
        }
    }
}
