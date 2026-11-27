package ro.ak.vodunify.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import ro.ak.vodunify.ui.components.clearReturningFocus

@Composable
internal fun TabBackToStart(scrollToStart: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val clearFocus = clearReturningFocus()
    val focusTab = LocalTabTopFocus.current
    BackHandler {
        clearFocus()
        scope.launch {
            scrollToStart()
            focusTab()
        }
    }
}
