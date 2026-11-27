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

    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    /** Comenzile ADB pentru televizoarele fără ecranele de setări (ex. TCL). */
    fun adbOverlayCommand(context: Context) = "adb shell appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow"

    fun adbBatteryCommand(context: Context) = "adb shell dumpsys deviceidle whitelist +${context.packageName}"

    /** Comenzi suplimentare pentru TV-uri agresive (TCL Safety Guard): rulare permanentă în fundal. */
    fun adbKeepAliveCommands(context: Context): List<String> = listOf(
        "adb shell cmd appops set ${context.packageName} RUN_IN_BACKGROUND allow",
        "adb shell cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow",
        "adb shell am set-standby-bucket ${context.packageName} active",
        "adb shell dumpsys deviceidle whitelist +${BuildConfig.MAIN_APP_ID}",
        "adb shell cmd appops set ${BuildConfig.MAIN_APP_ID} RUN_ANY_IN_BACKGROUND allow",
    )

    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val me = component(context)
        return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    fun screenReaderComponent(context: Context) = ComponentName(context, ScreenReaderService::class.java)

    /** Serviciul de accesibilitate (citirea titlului de pe ecran) e pornit în setări. */
    fun isScreenReaderEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = screenReaderComponent(context)
        return flat.split(':').mapNotNull { ComponentName.unflattenFromString(it) }.any { it == me }
    }

    /**
     * Comenzile ADB care pornesc citirea ecranului. Păstrează celelalte servicii de accesibilitate
     * deja pornite (le adăugăm pe al nostru la listă).
     */
    fun adbScreenReaderCommands(context: Context): List<String> {
        val me = screenReaderComponent(context).flattenToString()
        val existing = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && it != me }
        val value = (existing + me).joinToString(":")
        return listOf(
            "adb shell appops set ${context.packageName} ACCESS_RESTRICTED_SETTINGS allow",
            "adb shell settings put secure enabled_accessibility_services $value",
            "adb shell settings put secure accessibility_enabled 1",
        )
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
