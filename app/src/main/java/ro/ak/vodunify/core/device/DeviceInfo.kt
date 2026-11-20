package ro.ak.vodunify.core.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceInfo {
    fun isTv(context: Context): Boolean {
        val uiMode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        return uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
