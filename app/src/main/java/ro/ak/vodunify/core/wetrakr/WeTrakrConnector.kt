package ro.ak.vodunify.core.wetrakr

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
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.int
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.str
import java.net.URLEncoder
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NEACTIVAT momentan (vezi di/ConnectorModule): așteptăm API-ul public WeTrakr.
 *
 * Conectorul WeTrakr (experimental): login cu cod pe wetrakr.com/activate, istoric,
 * progres, „Următorul episod”, „De văzut” (doar citire: protocolul nu are scriere de listă).
 * Nu are nevoie de cheie API.
 */
@Singleton
class WeTrakrConnector @Inject constructor(
    private val api: WeTrakrApi,
    private val store: WeTrakrAuthStore,
    private val library: WeTrakrLibrary,
) : DeviceAuthConnector {
    override val id = WeTrakrMappers.SOURCE
    override val displayName = "WeTrakr"
    override val kind = ConnectorKind.TRACKER
    override val capabilities = setOf(
        Capability.HISTORY_READ,
        Capability.HISTORY_WRITE,
        Capability.RESUME_READ,
        Capability.RESUME_WRITE,
        Capability.WATCHLIST_READ,
        Capability.UP_NEXT_READ,
    )
    override val isConfigured = true

    override val account: Flow<ConnectorAccount?> = store.session.map { s ->
        s?.let { ConnectorAccount(it.username ?: "cont WeTrakr", null) }
    }

    override suspend fun isAuthenticated(): Boolean = store.current() != null

    // ---------- Autentificare ----------

    override suspend fun startDeviceAuth(): DeviceCode {
        val c = api.requestDeviceCode()
        val userCode = c["user_code"].str() ?: throw WeTrakrException("WeTrakr nu a trimis un cod")
        val deviceCode = c["device_code"].str() ?: throw WeTrakrException("WeTrakr nu a trimis un cod")
        val base = c["verification_url"].str() ?: WeTrakrApi.ACTIVATE_URL
        val sep = if ('?' in base) '&' else '?'
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            // Ca în add-on: codul în link precompletează pagina de activare.
            verificationUrl = "$base${sep}code=${URLEncoder.encode(userCode, "UTF-8")}",
            expiresAt = Instant.now().plusSeconds((c["expires_in"].int() ?: 600).toLong()),
            intervalSeconds = (c["interval"].int() ?: 5).coerceAtLeast(3),
        )
    }

    override suspend fun pollDeviceAuth(code: DeviceCode): DeviceAuthPoll {
        if (Instant.now().isAfter(code.expiresAt)) return DeviceAuthPoll.Expired
        return when (val r = api.pollDeviceToken(code.deviceCode)) {
            is WeTrakrPoll.Approved -> {
                store.save(r.token, r.username)
                library.clear()
                DeviceAuthPoll.Approved(ConnectorAccount(r.username ?: "cont WeTrakr", null))
            }
            WeTrakrPoll.Pending -> DeviceAuthPoll.Pending
            WeTrakrPoll.SlowDown -> DeviceAuthPoll.SlowDown
            WeTrakrPoll.Expired -> DeviceAuthPoll.Expired
            WeTrakrPoll.Denied -> DeviceAuthPoll.Denied
            is WeTrakrPoll.Failed -> DeviceAuthPoll.Failed(r.message)
        }
    }

    override suspend fun signOut() {
        store.clear()
        library.clear()
    }

    // ---------- Citire ----------

    private suspend fun snapshot(): WeTrakrSnapshot = try {
        library.snapshot()
    } catch (e: WeTrakrAuthRequiredException) {
        // Token regenerat sau conexiune ștearsă în WeTrakr: cere reconectare.
        store.clear()
        library.clear()
        throw e
    }

    override suspend fun readResume(): List<ResumePoint> =
        snapshot().inProgress.mapNotNull(WeTrakrMappers::resume)

    override suspend fun readHistory(since: Instant?): List<WatchEvent> =
        snapshot().rows.values.mapNotNull(WeTrakrMappers::history).filter { since == null || it.watchedAt >= since }

    override suspend fun readWatchedTitles(): List<WatchEvent> =
        snapshot().rows.values.mapNotNull(WeTrakrMappers::watchedTitle)
            .groupBy { it.media.titleKey }
            .map { (_, v) -> v.maxBy { it.watchedAt } }

    override suspend fun readWatchlist(): List<WatchlistEntry> {
        val snap = snapshot()
        return snap.rows.mapNotNull { (key, row) ->
            WeTrakrMappers.watchlist(row, Instant.ofEpochMilli(snap.plannedAt[key] ?: 0L))
        }.sortedByDescending { it.listedAt }
    }

    override suspend fun readUpNext(limit: Int): List<ShowProgress> =
        snapshot().nextEpisodes.mapNotNull(WeTrakrMappers::upNext).take(limit)

    override suspend fun showProgress(show: MediaRef): ShowProgress? {
        val target = show.asShow()
        return readUpNext(100).firstOrNull {
            (it.show.ids.tmdb != null && it.show.ids.tmdb == target.ids.tmdb) ||
                (it.show.ids.imdb != null && it.show.ids.imdb == target.ids.imdb) ||
                (it.show.ids.tvdb != null && it.show.ids.tvdb == target.ids.tvdb)
        }
    }

    // ---------- Scriere ----------

    /** Marcat văzut = eveniment „scrobble” la 100%. „Nevăzut” nu există în protocol: îl ținem doar local. */
    override suspend fun setWatched(media: MediaRef, watched: Boolean, watchedAt: Instant?) {
        if (!watched || media.type == MediaType.SHOW) return
        api.sendEvent(WeTrakrMappers.event("scrobble", media, 1f))
        library.invalidate()
    }

    override suspend fun writeResume(point: ResumePoint) {
        val progress = point.progress ?: return
        api.sendEvent(WeTrakrMappers.event("paused", point.media, progress))
        library.invalidate()
    }
}
