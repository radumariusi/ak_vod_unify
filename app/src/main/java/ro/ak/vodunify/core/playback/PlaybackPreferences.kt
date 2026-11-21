package ro.ak.vodunify.core.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ro.ak.vodunify.core.prefs.Market
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackStore: DataStore<Preferences> by preferencesDataStore(name = "playback_settings")

data class PlaybackSettings(
    /** Piața principală: regiunea Calendarului și serviciile necunoscute (fără aplicație în registru). */
    val playbackMarket: Market = Market.RO,
    /** Serviciu → aplicația preferată (id [TargetApp]). Lipsă = aplicația oficială. */
    val preferredApps: Map<String, String> = emptyMap(),
    /** Serviciu → biblioteca folosită (RO direct sau US prin VPN). Lipsă = [StreamingService.defaultMarket]. */
    val serviceMarkets: Map<String, Market> = emptyMap(),
    /** Playerul standard (id [TargetApp]) pentru titlurile fără serviciu instalat. Null = primul instalat. */
    val defaultPlayer: String? = null,
) {
    fun preferredApp(service: StreamingService): TargetApp =
        preferredApps[service.id]?.let(TargetApp::byId) ?: TargetApp.byId(service.appId)!!

    fun marketOf(service: StreamingService): Market = serviceMarkets[service.id] ?: service.defaultMarket

    val defaultPlayerApp: TargetApp? get() = defaultPlayer?.let(TargetApp::byId)
}

@Singleton
class PlaybackPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.playbackStore
    private val marketKey = stringPreferencesKey("playback_market")
    private val defaultPlayerKey = stringPreferencesKey("default_player")
    private fun prefKey(service: StreamingService) = stringPreferencesKey("preferred_${service.id}")
    private fun marketKey(service: StreamingService) = stringPreferencesKey("market_${service.id}")

    val settings: Flow<PlaybackSettings> = store.data
        .map { p ->
            PlaybackSettings(
                playbackMarket = p[marketKey]?.let(::market) ?: Market.RO,
                preferredApps = StreamingService.entries.mapNotNull { s -> p[prefKey(s)]?.let { s.id to it } }.toMap(),
                serviceMarkets = StreamingService.entries.mapNotNull { s -> p[marketKey(s)]?.let(::market)?.let { s.id to it } }.toMap(),
                defaultPlayer = p[defaultPlayerKey],
            )
        }
        .distinctUntilChanged()

    private fun market(v: String): Market? = Market.entries.firstOrNull { it.name == v }

    suspend fun setPlaybackMarket(market: Market) {
        store.edit { it[marketKey] = market.name }
    }

    suspend fun setPreferredApp(service: StreamingService, app: TargetApp) {
        store.edit {
            if (app.id == service.appId) it.remove(prefKey(service)) else it[prefKey(service)] = app.id
        }
    }

    suspend fun setServiceMarket(service: StreamingService, market: Market) {
        store.edit {
            if (market == service.defaultMarket) it.remove(marketKey(service)) else it[marketKey(service)] = market.name
        }
    }

    suspend fun setDefaultPlayer(app: TargetApp?) {
        store.edit { if (app == null) it.remove(defaultPlayerKey) else it[defaultPlayerKey] = app.id }
    }
}
