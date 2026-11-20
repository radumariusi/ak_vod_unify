package ro.ak.vodunify.core.playback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import ro.ak.vodunify.core.device.DeviceInfo
import javax.inject.Inject
import javax.inject.Singleton

/** O aplicație-țintă găsită pe dispozitiv. */
data class InstalledApp(val app: TargetApp, val packageName: String, val label: String)

/** Ținta finală validată: intentul acceptat de aplicația instalată și cât de precis e. */
data class ResolvedLaunch(
    val installed: InstalledApp,
    val precision: LinkPrecision,
    val intent: Intent,
)

/**
 * Detectează aplicațiile instalate (PackageManager + `<queries>` din manifest) și alege cea mai
 * precisă țintă pe care aplicația o acceptă cu adevărat: episod → titlu/serial → căutare → pornire.
 */
@Singleton
class TargetResolver @Inject constructor(@ApplicationContext private val context: Context) {
    private val pm: PackageManager get() = context.packageManager
    private val isTv: Boolean by lazy { DeviceInfo.isTv(context) }

    /** Aplicațiile cunoscute instalate acum. Rapid (doar interogări locale); apelează din nou la revenire. */
    fun installedApps(): Map<TargetApp, InstalledApp> {
        val launchables by lazy { launcherActivities() }
        return TargetApp.entries.mapNotNull { app ->
            val byPackage = app.packages.firstOrNull { launchIntent(it) != null }
            val pkg = byPackage ?: app.labelHints.takeIf { it.isNotEmpty() }?.let { hints ->
                launchables.firstOrNull { (p, label) ->
                    p != context.packageName && hints.any { h -> label.lowercase().replace(" ", "").contains(h.replace(" ", "")) }
                }?.first
            }
            pkg?.let { app to InstalledApp(app, it, labelOf(it) ?: app.displayName) }
        }.toMap()
    }

    /** Cea mai precisă țintă validată; null doar dacă aplicația nu mai poate fi pornită deloc. */
    fun resolve(installed: InstalledApp, request: PlaybackRequest): ResolvedLaunch? {
        for (c in LinkBuilder.candidates(installed.app, request)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(c.uri))
                .setPackage(installed.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (accepts(intent)) return ResolvedLaunch(installed, c.precision, intent)
        }
        return launchIntent(installed.packageName)?.let { ResolvedLaunch(installed, LinkPrecision.APP, it) }
    }

    /** Pornește ținta; dacă linkul e refuzat la execuție, cade pe simpla pornire a aplicației. */
    fun launch(target: ResolvedLaunch): Boolean {
        if (tryStart(target.intent)) return true
        return launchIntent(target.installed.packageName)?.let(::tryStart) ?: false
    }

    /** Link către magazin pentru o aplicație neinstalată (null dacă nu există magazin). */
    fun storeIntent(app: TargetApp): Intent? {
        val pkg = app.packages.firstOrNull() ?: return null
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (accepts(market)) return market
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return web.takeIf { accepts(it) }
    }

    fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun launchIntent(pkg: String): Intent? {
        val leanback = runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
        val normal = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
        return (if (isTv) leanback ?: normal else normal ?: leanback)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun accepts(intent: Intent): Boolean = query(intent).isNotEmpty()

    private fun query(intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
    }.getOrDefault(emptyList())

    /** (pachet, nume) pentru toate aplicațiile din launcher (telefon + TV). */
    private fun launcherActivities(): List<Pair<String, String>> =
        listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { cat -> query(Intent(Intent.ACTION_MAIN).addCategory(cat)) }
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinctBy { it.first }

    private fun labelOf(pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
