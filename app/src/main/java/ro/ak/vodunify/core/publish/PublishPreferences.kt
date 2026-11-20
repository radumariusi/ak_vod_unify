package ro.ak.vodunify.core.publish

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.publishStore: DataStore<Preferences> by preferencesDataStore(name = "publish_settings")

/** Profilurile de publicare (mod + destinații), salvate local. */
@Singleton
class PublishPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.publishStore
    private val activeKey = intPreferencesKey("active_profile")
    private fun modeKey(i: Int) = stringPreferencesKey("p${i}_mode")
    private fun destKey(i: Int) = stringSetPreferencesKey("p${i}_destinations")

    val settings: Flow<PublishSettings> = store.data
        .map { p ->
            PublishSettings(
                activeIndex = (p[activeKey] ?: 0).coerceIn(0, PublishSettings.PROFILE_COUNT - 1),
                profiles = (0 until PublishSettings.PROFILE_COUNT).map { i ->
                    PublishProfile(
                        index = i,
                        name = PublishSettings.defaultName(i),
                        mode = p[modeKey(i)]?.let { v -> PublishMode.entries.firstOrNull { it.name == v } } ?: PublishMode.ON_DEMAND,
                        destinations = p[destKey(i)] ?: PublishSettings.DEFAULT_DESTINATIONS,
                    )
                },
            )
        }
        .distinctUntilChanged()

    suspend fun current(): PublishSettings = settings.first()

    suspend fun setActive(index: Int) {
        store.edit { it[activeKey] = index.coerceIn(0, PublishSettings.PROFILE_COUNT - 1) }
    }

    suspend fun setMode(index: Int, mode: PublishMode) {
        store.edit { it[modeKey(index)] = mode.name }
    }

    suspend fun toggleDestination(index: Int, destination: String) {
        store.edit { p ->
            val current = p[destKey(index)] ?: PublishSettings.DEFAULT_DESTINATIONS
            p[destKey(index)] = if (destination in current) current - destination else current + destination
        }
    }
}
