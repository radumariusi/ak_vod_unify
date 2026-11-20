package ro.ak.vodunify.core.trakt

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.ListSerializer
import ro.ak.vodunify.core.connector.Capability
import ro.ak.vodunify.core.connector.ConnectorAccount
import ro.ak.vodunify.core.connector.ConnectorKind
import ro.ak.vodunify.core.connector.DeviceAuthConnector
import ro.ak.vodunify.core.connector.DeviceAuthPoll
import ro.ak.vodunify.core.connector.DeviceCode
import ro.ak.vodunify.core.connector.MediaRef
import ro.ak.vodunify.core.connector.MediaType
import ro.ak.vodunify.core.connector.ResumePoint
import ro.ak.vodunify.core.connector.ShowProgress
import ro.ak.vodunify.core.connector.WatchEvent
import ro.ak.vodunify.core.connector.WatchlistEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Conectorul Trakt: login cu cod, istoric, playback, „Următorul episod”, watchlist. */
@Singleton
class TraktConnector @Inject constructor(
    private val api: TraktApi,
    private val store: TraktAuthStore,
) : DeviceAuthConnector {
    override val id = TraktMappers.SOURCE
    override val displayName = "Trakt"
    override val kind = ConnectorKind.TRACKER
    override val capabilities = setOf(
        Capability.HISTORY_READ,
        Capability.HISTORY_WRITE,
        Capability.RESUME_READ,
        Capability.RESUME_WRITE,
        Capability.WATCHLIST_READ,
        Capability.WATCHLIST_WRITE,
        Capability.UP_NEXT_READ,
    )
    override val isConfigured: Boolean get() = api.isConfigured

    override val account: Flow<ConnectorAccount?> = store.tokens.map { t ->
        t?.let { ConnectorAccount(it.username ?: "cont Trakt", it.displayName) }
    }

    override suspend fun isAuthenticated(): Boolean = isConfigured && store.current() != null

    // ---------- Autentificare ----------

    override suspend fun startDeviceAuth(): DeviceCode {
        val c = api.requestDeviceCode()
        return DeviceCode(
            deviceCode = c.deviceCode,
            userCode = c.userCode,
            verificationUrl = c.verificationUrl,
            expiresAt = Instant.now().plusSeconds(c.expiresIn),
            intervalSeconds = c.interval.coerceAtLeast(1),
        )
    }

    override suspend fun pollDeviceAuth(code: DeviceCode): DeviceAuthPoll {
        if (Instant.now().isAfter(code.expiresAt)) return DeviceAuthPoll.Expired
        val (status, token) = api.pollDeviceToken(code.deviceCode)
        return when (status) {
            200 -> {
                store.save(requireNotNull(token))
                val user = runCatching { api.get("users/settings", TraktUserSettings.serializer()).user }.getOrNull()
                user?.let { store.saveUser(it) }
                DeviceAuthPoll.Approved(ConnectorAccount(user?.username ?: "cont Trakt", user?.name))
            }
            400 -> DeviceAuthPoll.Pending
            429 -> DeviceAuthPoll.SlowDown
            404 -> DeviceAuthPoll.Failed("Cod invalid. Cere un cod nou.")
            409 -> DeviceAuthPoll.Failed("Codul a fost deja folosit. Cere un cod nou.")
            410 -> DeviceAuthPoll.Expired
            418 -> DeviceAuthPoll.Denied
            else -> DeviceAuthPoll.Failed("Trakt a răspuns cu eroarea $status")
        }
    }

    override suspend fun signOut() {
        store.current()?.let { t -> runCatching { api.revoke(t.accessToken) } }
        store.clear()
        traktIdCache.clear()
    }

    // ---------- Citire ----------

    override suspend fun readResume(): List<ResumePoint> =
        api.get("sync/playback", ListSerializer(TraktPlayback.serializer()), mapOf("limit" to "100"))
            .mapNotNull(TraktMappers::resume)

    override suspend fun readHistory(since: Instant?): List<WatchEvent> {
        val out = mutableListOf<WatchEvent>()
        var page = 1
        while (page <= MAX_HISTORY_PAGES) {
            val result = api.getPage(
                "sync/history",
                ListSerializer(TraktHistoryItem.serializer()),
                mapOf("page" to page.toString(), "limit" to "100", "start_at" to since?.toString()),
            )
            out += result.items.mapNotNull(TraktMappers::history)
            if (page >= result.pageCount || result.items.isEmpty()) break
            page++
        }
        return out
    }

    override suspend fun readWatchedTitles(): List<WatchEvent> = coroutineScope {
        val movies = async {
            api.get("sync/watched/movies", ListSerializer(TraktWatchedItem.serializer()))
        }
        val shows = async {
            api.get("sync/watched/shows", ListSerializer(TraktWatchedItem.serializer()), mapOf("extended" to "noseasons"))
        }
        (movies.await() + shows.await()).mapNotNull(TraktMappers::watched)
    }

    override suspend fun readWatchlist(): List<WatchlistEntry> =
        api.get("sync/watchlist", ListSerializer(TraktListItem.serializer()), mapOf("sort_by" to "added", "sort_how" to "desc"))
            .mapNotNull(TraktMappers::watchlist)

    override suspend fun readUpNext(limit: Int): List<ShowProgress> = coroutineScope {
        val shows = api.get("sync/watched/shows", ListSerializer(TraktWatchedItem.serializer()), mapOf("extended" to "noseasons"))
            .filter { it.show != null }
            .sortedByDescending { TraktMappers.instant(it.lastWatchedAt) ?: Instant.EPOCH }
            .take(limit)
        val gate = Semaphore(4)
        shows.mapNotNull { it.show }.map { show ->
            async {
                gate.withPermit {
                    val ref = TraktMappers.show(show)
                    runCatching { progressFor(ref, show.ids.trakt?.toString()) }.getOrNull()
                }
            }
        }.awaitAll().filterNotNull().filter { it.next != null }
    }

    override suspend fun showProgress(show: MediaRef): ShowProgress? {
        val traktId = resolveTraktId(show.asShow()) ?: return null
        return progressFor(show.asShow(), traktId)
    }

    private suspend fun progressFor(show: MediaRef, traktId: String?): ShowProgress? {
        val idPath = traktId ?: resolveTraktId(show) ?: return null
        val p = api.get(
            "shows/$idPath/progress/watched",
            TraktShowProgress.serializer(),
            mapOf("hidden" to "false", "specials" to "false", "count_specials" to "false"),
        )
        return TraktMappers.progress(show, p)
    }

    // ---------- Scriere ----------

    override suspend fun setWatched(media: MediaRef, watched: Boolean, watchedAt: Instant?) {
        val path = if (watched) "sync/history" else "sync/history/remove"
        api.postJson(path, TraktMappers.syncBody(media, if (watched) watchedAt ?: Instant.now() else null))
    }

    override suspend fun setInWatchlist(media: MediaRef, inWatchlist: Boolean) {
        val target = if (media.type == MediaType.EPISODE) media.asShow() else media
        api.postJson(if (inWatchlist) "sync/watchlist" else "sync/watchlist/remove", TraktMappers.syncBody(target))
    }

    override suspend fun writeResume(point: ResumePoint) {
        val progress = point.progress ?: return // fără poziție cunoscută nu publicăm nimic
        api.postJson("scrobble/pause", TraktMappers.scrobbleBody(point.media, progress))
        delay(POST_SPACING_MS)
    }

    // ---------- Id-uri ----------

    private val traktIdCache = ConcurrentHashMap<String, String>()

    /** Trakt acceptă în URL id Trakt, slug sau IMDb; din TMDb căutăm prin /search/tmdb. */
    private suspend fun resolveTraktId(media: MediaRef): String? {
        media.ids.trakt?.let { return it.toString() }
        media.ids.imdb?.let { return it }
        val tmdb = media.ids.tmdb ?: return null
        val type = if (media.type == MediaType.MOVIE) "movie" else "show"
        val key = "$type:$tmdb"
        traktIdCache[key]?.let { return it }
        val found = api.get("search/tmdb/$tmdb", ListSerializer(TraktSearchResult.serializer()), mapOf("type" to type))
            .firstOrNull { it.type == type }
            ?.let { (it.movie?.ids ?: it.show?.ids)?.trakt?.toString() }
        found?.let { traktIdCache[key] = it }
        return found
    }

    private companion object {
        /** 10 000 de intrări la prima sincronizare; apoi doar ce e nou. */
        const val MAX_HISTORY_PAGES = 100
        const val POST_SPACING_MS = 1000L
    }
}
