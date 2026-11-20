package ro.ak.vodunify.core.simkl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import kotlinx.serialization.json.JsonObject
import ro.ak.vodunify.core.simkl.SimklMappers.Kind
import ro.ak.vodunify.core.simkl.SimklMappers.obj
import ro.ak.vodunify.core.simkl.SimklMappers.str
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Conectorul SIMKL: login cu cod (AUTH V2), istoric, playback, „Următorul episod”, watchlist, anime. */
@Singleton
class SimklConnector @Inject constructor(
    private val api: SimklApi,
    private val store: SimklAuthStore,
    private val library: SimklLibrary,
) : DeviceAuthConnector {
    override val id = SimklMappers.SOURCE
    override val displayName = "SIMKL"
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
        t?.let { ConnectorAccount(it.username ?: "cont SIMKL", null) }
    }

    override suspend fun isAuthenticated(): Boolean = isConfigured && store.current() != null

    // ---------- Autentificare ----------

    override suspend fun startDeviceAuth(): DeviceCode {
        val c = api.requestDeviceCode()
        return DeviceCode(
            deviceCode = c.deviceCode,
            userCode = c.userCode,
            // Linkul complet precompletează codul pe simkl.com/pin (și în codul QR).
            verificationUrl = c.verificationUriComplete ?: c.verificationUri,
            expiresAt = Instant.now().plusSeconds(c.expiresIn),
            intervalSeconds = c.interval.coerceAtLeast(5),
        )
    }

    override suspend fun pollDeviceAuth(code: DeviceCode): DeviceAuthPoll {
        // SIMKL nu trimite niciodată „refuzat”: ne oprim singuri la expirarea codului.
        if (Instant.now().isAfter(code.expiresAt)) return DeviceAuthPoll.Expired
        return when (val r = api.pollDeviceToken(code.deviceCode)) {
            is SimklPoll.Approved -> {
                store.save(r.token)
                library.clear()
                val name = runCatching {
                    api.get("users/settings").obj()?.get("user").obj()?.get("name").str()
                }.getOrNull()
                store.saveUser(name)
                DeviceAuthPoll.Approved(ConnectorAccount(name ?: "cont SIMKL", null))
            }
            SimklPoll.Pending -> DeviceAuthPoll.Pending
            SimklPoll.SlowDown -> DeviceAuthPoll.SlowDown
            SimklPoll.Expired -> DeviceAuthPoll.Expired
            is SimklPoll.Failed -> DeviceAuthPoll.Failed(r.message)
        }
    }

    override suspend fun signOut() {
        store.current()?.let { t -> runCatching { api.revoke(t.refreshToken ?: t.accessToken) } }
        store.clear()
        library.clear()
    }

    // ---------- Citire ----------

    private suspend fun entries(): List<Pair<Kind, JsonObject>> {
        val snap = library.snapshot()
        return Kind.entries.flatMap { k -> snap.items[k].orEmpty().values.map { k to it } }
    }

    override suspend fun readResume(): List<ResumePoint> =
        library.snapshot().playback.mapNotNull(SimklMappers::resume)

    override suspend fun readHistory(since: Instant?): List<WatchEvent> =
        entries().flatMap { (k, item) -> SimklMappers.history(item, k) }
            .filter { since == null || it.watchedAt >= since }

    override suspend fun readWatchedTitles(): List<WatchEvent> =
        entries().mapNotNull { (k, item) -> SimklMappers.watchedTitle(item, k) }

    override suspend fun readWatchlist(): List<WatchlistEntry> =
        entries().mapNotNull { (k, item) -> SimklMappers.watchlist(item, k) }.sortedByDescending { it.listedAt }

    override suspend fun readUpNext(limit: Int): List<ShowProgress> =
        entries().mapNotNull { (k, item) -> SimklMappers.progress(item, k) }
            .filter { it.next != null }
            .sortedByDescending { it.lastWatchedAt ?: Instant.EPOCH }
            .take(limit)

    override suspend fun showProgress(show: MediaRef): ShowProgress? {
        val target = show.asShow()
        return entries().asSequence()
            .filter { it.first != Kind.MOVIES }
            .mapNotNull { (k, item) -> SimklMappers.progress(item, k) }
            .firstOrNull { matches(it.show, target) }
    }

    private fun matches(a: MediaRef, b: MediaRef): Boolean =
        (a.ids.tmdb != null && a.ids.tmdb == b.ids.tmdb) ||
            (a.ids.imdb != null && a.ids.imdb == b.ids.imdb) ||
            (a.ids.simkl != null && a.ids.simkl == b.ids.simkl) ||
            (a.ids.tvdb != null && a.ids.tvdb == b.ids.tvdb)

    // ---------- Scriere ----------

    override suspend fun setWatched(media: MediaRef, watched: Boolean, watchedAt: Instant?) {
        val path = if (watched) "sync/history" else "sync/history/remove"
        api.postJson(path, SimklMappers.historyBody(media, if (watched) watchedAt ?: Instant.now() else null))
        library.invalidate()
    }

    override suspend fun setInWatchlist(media: MediaRef, inWatchlist: Boolean) {
        val target = if (media.type == MediaType.EPISODE) media.asShow() else media
        if (inWatchlist) {
            api.postJson("sync/add-to-list", SimklMappers.addToListBody(target, "plantowatch"))
        } else {
            // La SIMKL „scos din listă” = scos din bibliotecă (cu tot cu istoric). O facem
            // doar dacă titlul e chiar „Plan to watch”, ca să nu ștergem vizionări.
            val listed = readWatchlist().any { matches(it.media, target) }
            if (!listed) return
            api.postJson("sync/history/remove", SimklMappers.historyBody(target, null))
        }
        library.invalidate()
    }

    override suspend fun writeResume(point: ResumePoint) {
        val progress = point.progress ?: return // fără poziție cunoscută nu publicăm nimic
        api.postJson("scrobble/pause", SimklMappers.pauseBody(point.media, progress))
        library.invalidate()
    }
}
