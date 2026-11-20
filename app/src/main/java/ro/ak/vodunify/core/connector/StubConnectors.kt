package ro.ak.vodunify.core.connector

import ro.ak.vodunify.BuildConfig

/*
 * Conectori schelet pentru etapa 1. Implementările reale vin în etapele următoare:
 * Trakt (etapa 3), SIMKL + WeTrakr (etapa 5), deep link-uri (etapa 4).
 */

class TraktConnector : Connector {
    override val id = "trakt"
    override val displayName = "Trakt"
    override val kind = ConnectorKind.TRACKER
    override val capabilities = setOf(Capability.HISTORY_READ, Capability.RESUME_READ, Capability.RESUME_WRITE)
    override val isConfigured get() = BuildConfig.TRAKT_CLIENT_ID.isNotBlank() && BuildConfig.TRAKT_CLIENT_SECRET.isNotBlank()
}

class SimklConnector : Connector {
    override val id = "simkl"
    override val displayName = "SIMKL"
    override val kind = ConnectorKind.TRACKER
    override val capabilities = setOf(Capability.HISTORY_READ, Capability.RESUME_READ, Capability.RESUME_WRITE)
    override val isConfigured get() = BuildConfig.SIMKL_CLIENT_ID.isNotBlank() && BuildConfig.SIMKL_CLIENT_SECRET.isNotBlank()
}

class WeTrakrConnector : Connector {
    override val id = "wetrakr"
    override val displayName = "WeTrakr"
    override val kind = ConnectorKind.TRACKER
    override val capabilities = setOf(Capability.HISTORY_READ, Capability.RESUME_READ)
    override val isConfigured get() = BuildConfig.WETRAKR_API_KEY.isNotBlank()
}

/** Țintă de redare: aplicație externă deschisă prin deep link (implementare în etapa 4). */
class PlayerTargetConnector(
    override val id: String,
    override val displayName: String,
    val packageName: String,
) : Connector {
    override val kind = ConnectorKind.PLAYER
    override val capabilities = setOf(Capability.DEEP_LINK)
    override val isConfigured = true
    override fun deepLink(media: MediaRef) = null
}
