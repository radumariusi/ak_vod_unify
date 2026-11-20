package ro.ak.vodunify.core.simkl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SimklTokens(
    val accessToken: String,
    val refreshToken: String?,
    /** Expirarea tokenului de acces, în milisecunde epoch (0 = necunoscută). */
    val expiresAt: Long,
    val username: String?,
)

/** Tokenurile SIMKL, în `noBackupFilesDir` (nu ajung în backup și nu se copiază pe alt dispozitiv). */
@Singleton
class SimklAuthStore @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "simkl_auth.preferences_pb")
    }

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresKey = longPreferencesKey("expires_at")
    private val userKey = stringPreferencesKey("username")

    val tokens: Flow<SimklTokens?> = store.data.map { p ->
        val access = p[accessKey]
        if (access.isNullOrBlank()) null else SimklTokens(access, p[refreshKey], p[expiresKey] ?: 0L, p[userKey])
    }.distinctUntilChanged()

    suspend fun current(): SimklTokens? = tokens.first()

    suspend fun save(token: SimklToken, nowMillis: Long = System.currentTimeMillis()) {
        store.edit {
            it[accessKey] = token.accessToken
            // Refresh tokenul SIMKL nu se rotește; dacă răspunsul nu-l repetă, îl păstrăm pe cel vechi.
            token.refreshToken?.takeIf { r -> r.isNotBlank() }?.let { r -> it[refreshKey] = r }
            it[expiresKey] = token.expiresIn?.let { s -> nowMillis + s * 1000 } ?: 0L
        }
    }

    suspend fun saveUser(name: String?) {
        store.edit { if (name.isNullOrBlank()) it.remove(userKey) else it[userKey] = name }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
