package ro.ak.vodunify.core.playback

import android.app.SearchManager
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
    fun installedApps(includeServicesWithoutLauncher: Boolean = false): Map<TargetApp, InstalledApp> {
        AppRegistry.init(context)
        val launchables by lazy { launcherActivities().filter { it.first != context.packageName } }
        fun officialPackageExists(app: TargetApp, pkg: String): Boolean =
            includeServicesWithoutLauncher && app.role == AppRole.OFFICIAL && runCatching { pm.getApplicationInfo(pkg, 0).enabled }.getOrDefault(false)
        val result = LinkedHashMap<TargetApp, InstalledApp>()
        val claimed = HashSet<String>()
        fun take(app: TargetApp, pkg: String) {
            result[app] = InstalledApp(app, pkg, labelOf(pkg) ?: app.displayName)
            claimed += pkg
        }
        val active = TargetApp.active
        // 1. Aplicația aleasă de tine pentru o intrare (Setări → Aplicații și servicii).
        active.forEach { app ->
            AppRegistry.packageFor(app)?.takeIf { launchIntent(it) != null || officialPackageExists(app, it) }?.let { take(app, it) }
        }
        // 2. Pachetele cunoscute.
        active.filter { it !in result }.forEach { app ->
            val packages = app.packages.filterNot { it in claimed }
            val pkg = packages.firstOrNull { launchIntent(it) != null } ?: packages.firstOrNull { officialPackageExists(app, it) }
            pkg?.let { take(app, it) }
        }
        // 3. Numele din launcher: fiecare aplicație instalată merge la intrarea care i se potrivește cel mai bine
        //    (ex. „Nuvio RS” la Nuvio RS, nu la Nuvio).
        if (active.any { it !in result }) {
            fun byLabel(label: String): TargetApp? =
                active.maxByOrNull { it.labelScore(label) }?.takeIf { it.matchesLabel(label) }
            launchables.filter { it.first !in claimed }.forEach { (pkg, label) ->
                val best = TargetApp.byPackage(pkg) ?: byLabel(label)
                    ?: labelOf(pkg)?.takeIf { it != label }?.let(::byLabel)
                if (best != null && best in active && best !in result) take(best, pkg)
            }
        }
        return result
    }

    /** Toate aplicațiile din launcher (pachet, nume), ca să alegi una când adaugi o aplicație. */
    fun launchableApps(): List<Pair<String, String>> =
        launcherActivities().filter { it.first != context.packageName }.sortedBy { it.second.lowercase() }

    /** Cea mai precisă țintă validată; null doar dacă aplicația nu mai poate fi pornită deloc. */
    fun resolve(installed: InstalledApp, request: PlaybackRequest): ResolvedLaunch? {
        for (c in LinkBuilder.candidates(installed.app, request)) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(c.uri))
                .setPackage(installed.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (accepts(intent)) return ResolvedLaunch(installed, c.precision, intent)
        }
        // Căutarea în aplicație (ex. AK VoD Hub, multe playere IPTV): titlul ajunge direct în ecranul de căutare.
        val search = Intent(Intent.ACTION_SEARCH)
            .setPackage(installed.packageName)
            .putExtra(SearchManager.QUERY, request.title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (installed.app.role != AppRole.OFFICIAL && accepts(search)) return ResolvedLaunch(installed, LinkPrecision.SEARCH, search)
        return launchIntent(installed.packageName)?.let { ResolvedLaunch(installed, LinkPrecision.APP, it) }
    }

    /** Pornește ținta; dacă linkul e refuzat la execuție, cade pe simpla pornire a aplicației. */
    fun launch(target: ResolvedLaunch): Boolean {
        prepareScrobbler(target.installed.packageName)
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

    fun launchApp(packageName: String): Boolean {
        prepareScrobbler(packageName)
        return launchIntent(packageName)?.let(::tryStart) ?: false
    }

    /**
     * Apple TV își arată titlul către AK Scrobbler doar dacă modul cititor de ecran e deja pornit
     * când se deschide. Îi cerem Scrobbler-ului să-l pornească acum (doar pentru Apple TV; în rest
     * modul rămâne oprit, altfel Netflix, Peacock & co. citesc cu voce butoanele).
     */
    private fun prepareScrobbler(packageName: String) {
        if (!packageName.startsWith("com.apple.atve.")) return
        for (auth in listOf("ro.ak.vodscrobbler.diag", "ro.ak.vodscrobbler.debug.diag")) {
            val uri = Uri.parse("content://$auth/arm_reader?pkg=${Uri.encode(packageName)}")
            val armed = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c -> c.moveToFirst() && c.getString(0) == "1" }
            }.getOrNull() ?: continue
            // Timp ca sistemul să anunțe noua stare înainte să pornească Apple TV.
            if (armed) runCatching { Thread.sleep(ARM_SETTLE_MS) }
            return
        }
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

    private companion object {
        const val ARM_SETTLE_MS = 400L
    }
}
