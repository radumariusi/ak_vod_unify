package ro.ak.vodunify.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ro.ak.vodunify.core.connector.Connector
import ro.ak.vodunify.core.connector.PlayerTargetConnector
import ro.ak.vodunify.core.simkl.SimklConnector
import ro.ak.vodunify.core.trakt.TraktConnector

/**
 * Adaugi un conector nou = încă un @Provides @IntoSet aici.
 * Pachetele aplicațiilor-țintă sunt provizorii și se verifică în etapa 4 (deep links).
 */
@Module
@InstallIn(SingletonComponent::class)
object ConnectorModule {
    @Provides @IntoSet fun trakt(connector: TraktConnector): Connector = connector
    @Provides @IntoSet fun simkl(connector: SimklConnector): Connector = connector
    // WeTrakr: pus deoparte până publică API-ul oficial (codul e în core/wetrakr, gata de activat).
    // @Provides @IntoSet fun wetrakr(connector: WeTrakrConnector): Connector = connector

    @Provides @IntoSet fun netflix(): Connector = PlayerTargetConnector("netflix", "Netflix", "com.netflix.ninja")
    @Provides @IntoSet fun nuvio(): Connector = PlayerTargetConnector("nuvio", "Nuvio", "com.nuvio.app")
    @Provides @IntoSet fun stremio(): Connector = PlayerTargetConnector("stremio", "Stremio", "com.stremio.one")
    @Provides @IntoSet fun debridStream(): Connector = PlayerTargetConnector("debridstream", "DebridStream", "com.debridstream.tv")
}
