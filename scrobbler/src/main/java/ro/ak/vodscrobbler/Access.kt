package ro.ak.vodscrobbler

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings

/** Verificări pentru ecranul de stare. */
object Access {
    fun component(context: Context) = ComponentName(context, MediaListenerService::class.java)

    /** Comanda ADB care dă accesul, pentru televizoarele care nu au setarea în meniu. */
    fun adbCommand(context: Context): String =
        "adb shell cmd notification allow_listener ${component(context).flattenToString()}"

    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = component(context)
        return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    fun isMainAppInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(BuildConfig.MAIN_APP_ID, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
