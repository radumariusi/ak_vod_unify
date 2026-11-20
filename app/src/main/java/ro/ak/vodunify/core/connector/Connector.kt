package ro.ak.vodunify.core.connector

import android.content.Intent
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Contractul comun pentru toate sursele (Trakt, SIMKL, WeTrakr) și țintele de redare
 * (Netflix, Nuvio, Stremio, DebridStream). Metodele pentru capabilități nedeclarate
 * aruncă [UnsupportedCapabilityException]; verifică întâi [supports].
 */
interface Connector {
    /** Identificator stabil, ex. "trakt". */
    val id: String
    val displayName: String
    val kind: ConnectorKind
    val capabilities: Set<Capability>

    /** True dacă are cheile API compilate în BuildConfig. */
    val isConfigured: Boolean

    suspend fun isAuthenticated(): Boolean = false

    /** Istoricul complet de vizionări, opțional doar după [since]. */
    suspend fun readHistory(since: Instant? = null): List<WatchEvent> =
        throw UnsupportedCapabilityException(id, Capability.HISTORY_READ)

    /** Filmele și serialele văzute (o intrare pe titlu, cu ultima vizionare). */
    suspend fun readWatchedTitles(): List<WatchEvent> =
        throw UnsupportedCapabilityException(id, Capability.HISTORY_READ)

    suspend fun setWatched(media: MediaRef, watched: Boolean, watchedAt: Instant? = null): Unit =
        throw UnsupportedCapabilityException(id, Capability.HISTORY_WRITE)

    suspend fun readResume(): List<ResumePoint> =
        throw UnsupportedCapabilityException(id, Capability.RESUME_READ)

    suspend fun writeResume(point: ResumePoint): Unit =
        throw UnsupportedCapabilityException(id, Capability.RESUME_WRITE)

    suspend fun readWatchlist(): List<WatchlistEntry> =
        throw UnsupportedCapabilityException(id, Capability.WATCHLIST_READ)

    suspend fun setInWatchlist(media: MediaRef, inWatchlist: Boolean): Unit =
        throw UnsupportedCapabilityException(id, Capability.WATCHLIST_WRITE)

    /** Serialele începute, cu următorul episod de văzut (cele mai recente primele). */
    suspend fun readUpNext(limit: Int = 30): List<ShowProgress> =
        throw UnsupportedCapabilityException(id, Capability.UP_NEXT_READ)

    suspend fun showProgress(show: MediaRef): ShowProgress? =
        throw UnsupportedCapabilityException(id, Capability.UP_NEXT_READ)

    fun deepLink(media: MediaRef): Intent? =
        throw UnsupportedCapabilityException(id, Capability.DEEP_LINK)
}

/** Conector cu autentificare prin cod afișat pe ecran (Trakt, SIMKL). */
interface DeviceAuthConnector : Connector {
    /** Contul conectat, sau null. */
    val account: Flow<ConnectorAccount?>

    suspend fun startDeviceAuth(): DeviceCode
    suspend fun pollDeviceAuth(code: DeviceCode): DeviceAuthPoll
    suspend fun signOut()
}

fun Connector.supports(capability: Capability) = capability in capabilities

enum class ConnectorKind { TRACKER, PLAYER }

class UnsupportedCapabilityException(connectorId: String, capability: Capability) :
    UnsupportedOperationException("Conectorul '$connectorId' nu suportă $capability")
