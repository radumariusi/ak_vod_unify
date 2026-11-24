package ro.ak.vodunify.core.playback

import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.catalog.CatalogRepository
import ro.ak.vodunify.core.catalog.MarketAvailability
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.prefs.CatalogSettings
import javax.inject.Inject
import javax.inject.Singleton

data class PosterBadgeRequest(
    val kind: TitleKind,
    val titleId: Long?,
    val stage: PosterStage?,
    val activity: PosterActivity?,
    val confirmedService: ServiceBadge? = null,
) {
    fun resolvedStage(total: Int?): PosterStage = stage ?: when {
        total != null && total > 0 && (activity?.watchedEpisodes ?: 0) >= total -> PosterStage.WATCHED
        else -> activity?.stage ?: PosterStage.UNWATCHED
    }

    fun lastPackage(stage: PosterStage): String? = when (stage) {
        PosterStage.WATCHED -> activity?.lastWatchedPackage
        PosterStage.IN_PROGRESS -> activity?.lastPlaybackPackage
        PosterStage.UNWATCHED -> null
    }
}

@Singleton
class PosterBadges @Inject constructor(
    private val catalog: CatalogRepository,
    private val resolver: TargetResolver,
) {
    private data class CachedAvailability(val value: List<MarketAvailability>, val at: Long)
    private data class Apps(
        val installed: Map<TargetApp, InstalledApp>,
        val labels: Map<String, String>,
        val at: Long,
        val registryVersion: Int,
    )

    private val cache = LruCache<String, CachedAvailability>(600)
    private val episodeTotals = LruCache<Long, Int>(300)
    private val titleLocks = Array(16) { Mutex() }
    private val gate = Semaphore(4)
    private val appsLock = Mutex()
    @Volatile private var apps: Apps? = null

    private fun Apps.isCurrent() =
        System.currentTimeMillis() - at < APPS_TTL_MS && registryVersion == AppRegistry.version.value

    private suspend fun installed() = appsLock.withLock {
        apps?.takeIf { it.isCurrent() } ?: withContext(Dispatchers.IO) {
            Apps(resolver.installedApps(), resolver.launchableApps().toMap(), System.currentTimeMillis(), AppRegistry.version.value)
        }.also { apps = it }
    }

    private fun key(request: PosterBadgeRequest) = request.titleId?.let { "${request.kind.path}:$it" }

    fun peek(request: PosterBadgeRequest, playback: PlaybackSettings, prefs: PosterBadgeSettings): List<PosterBadge>? {
        val stage = request.resolvedStage(request.titleId?.takeIf { request.kind == TitleKind.TV }?.let(::peekEpisodeTotal))
        val policy = prefs.policy(stage)
        val installed = apps?.takeIf { it.isCurrent() }
        if (installed == null) {
            return request.confirmedService?.let {
                PosterBadgeRules.select(policy, prefs.serviceOrder, listOf(it), null, null)
            }
        }
        val last = lastBadge(request.lastPackage(stage), installed)
        val cached = key(request)?.let(cache::get)
        if (cached == null && request.titleId != null && request.confirmedService == null && !canSkipAvailability(policy, last)) return null
        return select(policy, last, playback, prefs, installed, cached?.value.orEmpty(), request.confirmedService)
    }

    suspend fun badges(request: PosterBadgeRequest, playback: PlaybackSettings, prefs: PosterBadgeSettings): List<PosterBadge> {
        val id = request.titleId
        val key = key(request)
        val lock = key?.let { titleLocks[(it.hashCode() and Int.MAX_VALUE) % titleLocks.size] }
        val total = if (id != null && request.stage == null && request.activity?.watchedEpisodes != null && request.kind == TitleKind.TV) {
            episodeTotal(id)
        } else null
        val stage = request.resolvedStage(total)
        val policy = prefs.policy(stage)
        if (policy.mode == PosterBadgeMode.NONE) return emptyList()
        val installed = installed()
        val last = lastBadge(request.lastPackage(stage), installed)
        if (id == null || key == null || lock == null || !catalog.isConfigured || canSkipAvailability(policy, last)) {
            return select(policy, last, playback, prefs, installed, emptyList(), request.confirmedService)
        }
        val availability = lock.withLock {
            val previous = cache.get(key)
            if (previous != null && System.currentTimeMillis() - previous.at < AVAILABILITY_TTL_MS) {
                return@withLock previous.value
            }
            cachedRequest { catalog.watchAvailability(request.kind, id) }?.also {
                cache.put(key, CachedAvailability(it, System.currentTimeMillis()))
            } ?: previous?.value.orEmpty()
        }
        return select(policy, last, playback, prefs, installed, availability, request.confirmedService)
    }

    fun peekEpisodeTotal(id: Long): Int? = episodeTotals.get(id)

    suspend fun episodeTotal(id: Long): Int? {
        if (!catalog.isConfigured) return null
        val key = "${TitleKind.TV.path}:$id"
        return titleLocks[(key.hashCode() and Int.MAX_VALUE) % titleLocks.size].withLock {
            peekEpisodeTotal(id) ?: cachedRequest {
                catalog.basic(TitleKind.TV, id, CatalogSettings()).episodeCount?.also { episodeTotals.put(id, it) }
            }
        }
    }

    private suspend fun <T> cachedRequest(load: suspend () -> T): T? = try {
        gate.withPermit { load() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun canSkipAvailability(policy: PosterBadgePolicy, last: PosterBadge?): Boolean =
        policy.mode == PosterBadgeMode.NONE ||
            (policy.mode == PosterBadgeMode.FIRST && policy.priorities.firstOrNull() == BadgePriority.LAST_APP && last != null)

    private fun lastBadge(pkg: String?, apps: Apps): PosterBadge? {
        if (pkg.isNullOrBlank()) return null
        val app = apps.installed.values.firstOrNull { it.packageName == pkg }
        val target = app?.app ?: TargetApp.byPackage(pkg)
        val service = target?.let { a -> StreamingService.active.firstOrNull { it.appId == a.id } }
        val label = app?.label ?: apps.labels[pkg] ?: target?.displayName ?: pkg
        return PosterBadge(pkg, label, service?.id)
    }

    private fun select(
        policy: PosterBadgePolicy,
        last: PosterBadge?,
        playback: PlaybackSettings,
        prefs: PosterBadgeSettings,
        apps: Apps,
        availability: List<MarketAvailability>,
        confirmedService: ServiceBadge?,
    ): List<PosterBadge> {
        val services = availability.flatMap { region ->
            (region.flatrate + region.free).mapNotNull { provider ->
                val service = StreamingService.fromProvider(provider.id, provider.name) ?: return@mapNotNull null
                val app = TargetApp.byId(service.appId)?.let(apps.installed::get) ?: return@mapNotNull null
                ServiceBadge(PosterBadge(app.packageName, "${service.displayName} (${region.market.code})", service.id), region.market)
            }.distinctBy { it.serviceId }
        }
        val default = playback.defaultPlayerApp?.takeIf { it.isPlayer && it in apps.installed }
        val playerApp = default ?: TargetApp.players.firstOrNull { it in apps.installed }
        val player = playerApp?.let(apps.installed::get)?.let { PosterBadge(it.packageName, it.label) }
        return PosterBadgeRules.select(policy, prefs.serviceOrder, listOfNotNull(confirmedService) + services, last, player)
    }

    private companion object {
        const val APPS_TTL_MS = 60_000L
        const val AVAILABILITY_TTL_MS = 15 * 60_000L
    }
}
