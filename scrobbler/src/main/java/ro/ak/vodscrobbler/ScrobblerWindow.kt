package ro.ak.vodscrobbler

import android.app.Activity
import android.view.Gravity
import android.view.WindowManager
import ro.ak.vodimport.dp

/** Android ține fereastra de dedesubt vizibilă când activitatea are o temă flotantă. */
internal fun Activity.sizeScrobblerWindow() {
    val config = resources.configuration
    val screenWidth = config.screenWidthDp.takeIf { it > 0 } ?: 480
    val screenHeight = config.screenHeightDp.takeIf { it > 0 } ?: 520
    val margin = dp(16)
    window.attributes = window.attributes.apply {
        gravity = Gravity.TOP or Gravity.END
        x = margin
        y = margin
        this.width = dp((screenWidth - 32).coerceIn(1, 432))
        this.height = dp((screenHeight * 0.76f).toInt().coerceIn(1, 476))
    }
    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
}
