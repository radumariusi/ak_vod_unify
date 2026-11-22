package ro.ak.vodunify.core.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.TitleKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Sigla de pe poster: aplicația (pachetul instalat) în care îl vezi. [player] = nu e pe platformele tale. */
data class PosterBadge(val packageName: String, val label: String, val player: Boolean)

/**
 * Sigla de pe fiecare poster: platforma ta pe care se găsește titlul (în biblioteca setată, RO sau US), altfel
 * playerul standard. Disponibilitatea vine din TMDb / JustWatch (păstrată 7 zile), aplicațiile din sistem.
 */
@Singleton
class PosterBadges @Inject constructor(
    private val catalog: CatalogRepository,
    private val playback: PlaybackPreferences,
    private val resolver: TargetResolver,
) {
    private val cache = ConcurrentHashMap<String, Result>()
    private val gate = Semaphore(4)
    private val appsLock = Mutex()
    @Volatile private var apps: Map<TargetApp, InstalledApp> = emptyMap()
    @Volatile private var appsAt = 0L
    @Volatile private var appsVersion = -1

    private data class Result(val badge: PosterBadge?, val settingsKey: String, val appsAt: Long)

    private suspend fun installed(): Map<TargetApp, InstalledApp> = appsLock.withLock {
        val now = System.currentTimeMillis()
        val version = AppRegistry.version.value
        if (now - appsAt > APPS_TTL_MS || version != appsVersion) {
            appsVersion = version
            apps = withContext(Dispatchers.IO) { runCatching { resolver.installedApps() }.getOrDefault(apps) }
            appsAt = now
        }
        apps
    }

    /** Sigla deja aflată (fără așteptare), ca posterul s-o arate din primul cadru. */
    fun peek(kind: TitleKind, id: Long): PosterBadge? = cache["${kind.path}:$id"]?.badge

    suspend fun badge(kind: TitleKind, id: Long): PosterBadge? {
        if (!catalog.isConfigured) return null
        val settings = playback.settings.first()
        val installed = installed()
        val key = "${kind.path}:$id"
        val settingsKey = settings.hashCode().toString()
        cache[key]?.takeIf { it.settingsKey == settingsKey && it.appsAt == appsAt }?.let { return it.badge }
        val availability = gate.withPermit { runCatching { catalog.watchAvailability(kind, id) }.getOrNull() } ?: return null
        val options = WatchOptionsBuilder.build(availability, settings, installed.keys)
        val service = options.services.firstOrNull()
        val badge = if (service != null) {
            // Sigla platformei (aplicația oficială), chiar dacă îl deschizi într-un player ales în Setări.
            val official = TargetApp.byId(service.service.appId)?.let { installed[it] } ?: service.app?.let { installed[it] }
            official?.let { PosterBadge(it.packageName, service.service.displayName, player = false) }
        } else {
            val player = settings.defaultPlayerApp?.let { installed[it] } ?: options.players.firstOrNull()?.let { installed[it] }
            player?.let { PosterBadge(it.packageName, it.label, player = true) }
        }
        cache[key] = Result(badge, settingsKey, appsAt)
        return badge
    }

    private companion object {
        const val APPS_TTL_MS = 60_000L
    }
}
