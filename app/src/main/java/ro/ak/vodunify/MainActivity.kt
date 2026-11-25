package ro.ak.vodunify

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dagger.hilt.android.AndroidEntryPoint
import ro.ak.vodunify.core.device.DeviceInfo
import ro.ak.vodunify.core.scrobble.ScrobblePuller
import ro.ak.vodunify.ui.components.LocalLayoutSpec
import ro.ak.vodunify.ui.components.layoutSpecFor
import ro.ak.vodunify.ui.navigation.AppNavigation
import ro.ak.vodunify.ui.theme.VodUnifyTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var scrobblePuller: ScrobblePuller

    override fun onResume() {
        super.onResume()
        scrobblePuller.pullInForegroundAsync()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isTv = DeviceInfo.isTv(this)
        setContent {
            val config = LocalConfiguration.current
            val spec = remember(isTv, config.screenWidthDp) { layoutSpecFor(isTv, config.screenWidthDp) }
            VodUnifyTheme {
                CompositionLocalProvider(LocalLayoutSpec provides spec) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .safeDrawingPadding(),
                    ) {
                        AppNavigation()
                    }
                }
            }
        }
    }
}
