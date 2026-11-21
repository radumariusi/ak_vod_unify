package ro.ak.vodunify.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay

/**
 * Întoarcerea din fișă: cardul de pe care ai deschis-o primește din nou focusul (ca în AK VOD Hub),
 * în loc să pornești D-pad-ul de la începutul ecranului.
 */
class FocusRestore {
    /** Cheia cardului care trebuie să primească focusul când reapare pe ecran. */
    var pending: String? by mutableStateOf(null)

    /** Ecranul (ruta) de pe care s-a deschis fișa. */
    var origin: String? by mutableStateOf(null)

    /** Ecranul curent (îl ține la zi navigarea). */
    var current: String? by mutableStateOf(null)

    fun remember(key: String) {
        pending = key
        origin = current
    }
}

val LocalFocusRestore = staticCompositionLocalOf { FocusRestore() }

/**
 * După întoarcerea pe ecranul de origine, dacă cardul nu mai apare (listă schimbată), renunțăm după 2 s,
 * ca să nu „furăm” focusul mai târziu.
 */
@Composable
fun ExpireFocusRestore(restore: FocusRestore) {
    val key = restore.pending
    val back = key != null && restore.current == restore.origin
    LaunchedEffect(key, back) {
        if (back) {
            delay(2_000)
            if (restore.pending == key) restore.pending = null
        }
    }
}
