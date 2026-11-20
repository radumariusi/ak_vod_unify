package ro.ak.vodunify.core.prefs

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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.catalogStore: DataStore<Preferences> by preferencesDataStore(name = "catalog_settings")

@Singleton
class CatalogPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.catalogStore
    private val languageKey = stringPreferencesKey("language")
    private val marketKey = stringPreferencesKey("market")

    val settings: Flow<CatalogSettings> = store.data
        .map { prefs ->
            CatalogSettings(
                language = prefs[languageKey]?.let { v -> ContentLanguage.entries.firstOrNull { it.name == v } }
                    ?: ContentLanguage.ROMANIAN,
                market = prefs[marketKey]?.let { v -> Market.entries.firstOrNull { it.name == v } } ?: Market.RO,
            )
        }
        .distinctUntilChanged()

    suspend fun setLanguage(language: ContentLanguage) {
        store.edit { it[languageKey] = language.name }
    }

    suspend fun setMarket(market: Market) {
        store.edit { it[marketKey] = market.name }
    }
}
