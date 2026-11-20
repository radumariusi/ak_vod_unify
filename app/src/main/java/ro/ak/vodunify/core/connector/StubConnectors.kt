package ro.ak.vodunify.core.connector

/*
 * Conectori schelet. Trackerii reali sunt în core/trakt, core/simkl, core/wetrakr;
 * deep link-urile vin în etapa 4.
 */

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
