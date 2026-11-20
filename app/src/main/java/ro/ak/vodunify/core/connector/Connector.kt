package ro.ak.vodunify.core.connector

import android.content.Intent
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

    suspend fun readHistory(since: Instant? = null): List<WatchEvent> =
        throw UnsupportedCapabilityException(id, Capability.HISTORY_READ)

    suspend fun readResume(): List<ResumePoint> =
        throw UnsupportedCapabilityException(id, Capability.RESUME_READ)

    suspend fun writeResume(point: ResumePoint): Unit =
        throw UnsupportedCapabilityException(id, Capability.RESUME_WRITE)

    fun deepLink(media: MediaRef): Intent? =
        throw UnsupportedCapabilityException(id, Capability.DEEP_LINK)
}

fun Connector.supports(capability: Capability) = capability in capabilities

enum class ConnectorKind { TRACKER, PLAYER }

class UnsupportedCapabilityException(connectorId: String, capability: Capability) :
    UnsupportedOperationException("Conectorul '$connectorId' nu suportă $capability")
