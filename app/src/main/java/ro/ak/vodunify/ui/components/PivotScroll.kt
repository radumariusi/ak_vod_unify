@file:OptIn(ExperimentalFoundationApi::class)

package ro.ak.vodunify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.Job
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Derulare verticală „cu pivot”, ca pe TV (preluată din AK VOD Hub): rândul cu focus ajunge mereu la aceeași
 * înălțime ([pivot] de la marginea de sus), deci se vede întreg, cu titlul lui, și nu „sare” diferit de la un
 * poster la altul. Rândurile orizontale din interior păstrează derularea obișnuită (vezi [DefaultScroll]).
 */
@Composable
fun PivotScroll(pivot: Dp, enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val density = LocalDensity.current
    val pivotPx = with(density) { pivot.toPx() }
    // Toleranța: mărirea cardului cu focus (1.05) și conturul îi schimbă puțin marginile; fără ea, fiecare
    // pas stânga / dreapta în rând ar mișca rândul cu câțiva pixeli („țopăie”). E inegală: rândul poate sta
    // puțin mai jos de pivot, dar aproape deloc mai sus, altfel titlul rândului (deasupra posterelor) se taie.
    val belowPx = with(density) { 20.dp.toPx() }
    val abovePx = with(density) { 6.dp.toPx() }
    val default = LocalBringIntoViewSpec.current
    val spec = remember(pivotPx, belowPx, abovePx, default) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (size + pivotPx > containerSize) return default.calculateScrollDistance(offset, size, containerSize)
                val distance = offset - pivotPx
                val visible = offset >= 0f && offset + size <= containerSize
                return if (visible && distance >= -abovePx && distance <= belowPx) 0f else distance
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, LocalDefaultScroll provides default) { content() }
}

private val LocalDefaultScroll = staticCompositionLocalOf<BringIntoViewSpec?> { null }

/** Readuce derularea obișnuită pentru un rând orizontal aflat într-un [PivotScroll]. */
@Composable
fun DefaultScroll(content: @Composable () -> Unit) {
    val default = LocalDefaultScroll.current
    if (default == null) {
        content()
    } else {
        CompositionLocalProvider(LocalBringIntoViewSpec provides default) { content() }
    }
}

/** Focusul vertical se mută între elementele listei, chiar dacă următorul rând nu e compus încă. */
class HomeRows(
    val slotHeight: Dp,
    private val state: LazyListState,
    private val scope: CoroutineScope,
) {
    private val requesters = mutableMapOf<String, FocusRequester>()
    private var focused: String? = null
    private var moving: String? = null
    private var scroll: Job? = null

    fun register(key: String, requester: FocusRequester) { requesters[key] = requester }

    fun unregister(key: String, requester: FocusRequester) { requesters.remove(key, requester) }

    fun entered(key: String, index: Int) {
        focused = key
        if (moving != null || (state.firstVisibleItemIndex == index && state.firstVisibleItemScrollOffset == 0)) return
        scroll?.cancel()
        scroll = scope.launch { state.scrollToItem(index) }
    }

    fun move(event: KeyEvent, keys: List<String?>, animate: Boolean): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val step = when (event.key) {
            Key.DirectionUp -> -1
            Key.DirectionDown -> 1
            else -> return false
        }
        if (moving != null) return true
        val current = keys.indexOf(focused ?: return false)
        if (current < 0) return false
        val next = if (step > 0) {
            (current + 1 until keys.size).firstOrNull { keys[it] != null }
        } else {
            (current - 1 downTo 0).firstOrNull { keys[it] != null }
        }
        if (next == null) return step > 0
        val target = checkNotNull(keys[next])
        scroll?.cancel()
        moving = target
        scroll = scope.launch {
            try {
                if (animate) state.animateScrollToItem(next) else state.scrollToItem(next)
                repeat(3) {
                    withFrameNanos { }
                    requesters[target]?.requestFocus()
                    if (focused == target) return@launch
                }
            } finally {
                moving = null
            }
        }
        return true
    }
}

@Composable
fun HomeRowFocus(key: String, index: Int, content: @Composable () -> Unit) {
    val rows = LocalHomeRows.current
    if (rows == null) {
        content()
        return
    }
    val requester = remember { FocusRequester() }
    DisposableEffect(rows, key, requester) {
        rows.register(key, requester)
        onDispose { rows.unregister(key, requester) }
    }
    Box(
        Modifier.focusRequester(requester)
            .onFocusChanged { if (it.hasFocus) rows.entered(key, index) }
            .focusGroup(),
    ) { content() }
}

val LocalHomeRows = staticCompositionLocalOf<HomeRows?> { null }

/** Lista din Acasă (TV): nu se derulează singură după focus; rândurile se aduc sus prin [HomeRows]. */
@Composable
fun FixedRowsScroll(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val default = LocalBringIntoViewSpec.current
    val none = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides none, LocalDefaultScroll provides default) { content() }
}

/** Focusul din Detalii derulează doar cât e necesar pentru a vedea elementul. */
@Composable
fun KeepFocusedContentVisible(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val spec = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = offset + size
                if (offset <= 0f && end >= containerSize) return 0f
                if (offset < 0f) return offset
                return (end - containerSize).coerceAtLeast(0f)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** În Acasă pe TV, toate rândurile de carduri ocupă același spațiu. */
fun Modifier.peekRow(): Modifier = composed {
    LocalHomeRows.current?.let { height(it.slotHeight) } ?: this
}
