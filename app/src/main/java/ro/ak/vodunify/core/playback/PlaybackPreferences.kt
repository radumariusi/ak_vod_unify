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
    /** Piața conturilor tale de streaming (unde poți reda efectiv). */
    val playbackMarket: Market = Market.RO,
    /** Serviciu → aplicația preferată (id [TargetApp]). Lipsă = aplicația oficială. */
    val preferredApps: Map<String, String> = emptyMap(),
) {
    fun preferredApp(service: StreamingService): TargetApp =
        preferredApps[service.id]?.let(TargetApp::byId) ?: TargetApp.byId(service.appId)!!
}

@Singleton
class PlaybackPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.playbackStore
    private val marketKey = stringPreferencesKey("playback_market")
    private fun prefKey(service: StreamingService) = stringPreferencesKey("preferred_${service.id}")

    val settings: Flow<PlaybackSettings> = store.data
        .map { p ->
            PlaybackSettings(
                playbackMarket = p[marketKey]?.let { v -> Market.entries.firstOrNull { it.name == v } } ?: Market.RO,
                preferredApps = StreamingService.entries.mapNotNull { s -> p[prefKey(s)]?.let { s.id to it } }.toMap(),
            )
        }
        .distinctUntilChanged()

    suspend fun setPlaybackMarket(market: Market) {
        store.edit { it[marketKey] = market.name }
    }

    suspend fun setPreferredApp(service: StreamingService, app: TargetApp) {
        store.edit {
            if (app.id == service.appId) it.remove(prefKey(service)) else it[prefKey(service)] = app.id
        }
    }
}
