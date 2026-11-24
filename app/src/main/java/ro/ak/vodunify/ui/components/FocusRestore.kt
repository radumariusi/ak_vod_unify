package ro.ak.vodunify.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.delay

/**
 * Întoarcerea din fișă: cardul de pe care ai deschis-o primește din nou focusul (ca în AK VOD Hub),
 * în loc să pornești D-pad-ul de la începutul ecranului.
 */
class FocusRestore {
    data class Position(val entry: String, val cardKey: String, val homeRow: String?)

    private val positions = mutableMapOf<String, Position>()
    private var activeEntry: String? = null

    var pending: Position? by mutableStateOf(null)
        private set

    fun remember(entry: String, cardKey: String, homeRow: String?) {
        positions[entry] = Position(entry, cardKey, homeRow)
    }

    fun show(entry: String?, backStack: Set<String>) {
        positions.keys.retainAll(backStack)
        if (entry == activeEntry) return
        activeEntry = entry
        pending = entry?.let(positions::get)
    }

    fun consume(position: Position) {
        if (pending != position || positions[position.entry] != position) return
        positions.remove(position.entry)
        pending = null
    }
}

val LocalFocusRestore = staticCompositionLocalOf { FocusRestore() }
internal val LocalHomeRowKey = staticCompositionLocalOf<String?> { null }

@Composable
internal fun focusEntryId(): String? = (LocalLifecycleOwner.current as? NavBackStackEntry)?.id

@Composable
internal fun returningFocus(): FocusRestore.Position? =
    LocalFocusRestore.current.pending?.takeIf { it.entry == focusEntryId() }

/** Remontează cardul când rândul s-a reordonat sau posterul se află în afara ecranului. */
@Composable
internal fun <T> rememberRestoringRowState(rowKey: String, items: List<T>, key: (T) -> String): LazyListState {
    val state = rememberLazyListState()
    val homeRow = LocalHomeRowKey.current
    val position = returningFocus()?.takeIf { it.homeRow == homeRow }
    val prefix = "$rowKey|"
    val target = position?.cardKey?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
    val index = if (target == null) -1 else items.indexOfFirst { key(it) == target }
    LaunchedEffect(position, index) {
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.index == index }) state.scrollToItem(index)
    }
    return state
}

/**
 * După întoarcerea pe ecranul de origine, dacă acel card a dispărut, renunțăm după câteva secunde,
 * ca să nu „furăm” focusul mai târziu.
 */
@Composable
fun ExpireFocusRestore(restore: FocusRestore) {
    val position = restore.pending ?: return
    LaunchedEffect(position) {
        delay(5_000)
        restore.consume(position)
    }
}
