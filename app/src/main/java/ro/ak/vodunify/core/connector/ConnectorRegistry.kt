package ro.ak.vodunify.core.connector

import javax.inject.Inject
import javax.inject.Singleton

/** Toți conectorii înregistrați prin Hilt (@IntoSet). */
@Singleton
class ConnectorRegistry @Inject constructor(
    connectors: Set<@JvmSuppressWildcards Connector>,
) {
    val all: List<Connector> = connectors.sortedWith(compareBy({ it.kind }, { it.displayName }))

    fun byId(id: String): Connector? = all.firstOrNull { it.id == id }

    fun withCapability(capability: Capability): List<Connector> = all.filter { it.supports(capability) }
}
