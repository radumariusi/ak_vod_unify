package ro.ak.vodunify.core.trakt

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

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    /** Momentul expirării, în milisecunde epoch. */
    val expiresAt: Long,
    val username: String?,
    val displayName: String?,
)

/**
 * Tokenurile Trakt, salvate în spațiul privat al aplicației, în `noBackupFilesDir`
 * (nu ajung în backup-ul Google și nu se copiază pe alt dispozitiv).
 */
@Singleton
class TraktAuthStore @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "trakt_auth.preferences_pb")
    }

    private val accessKey = stringPreferencesKey("access_token")
    private val refreshKey = stringPreferencesKey("refresh_token")
    private val expiresKey = longPreferencesKey("expires_at")
    private val userKey = stringPreferencesKey("username")
    private val nameKey = stringPreferencesKey("display_name")

    val tokens: Flow<TraktTokens?> = store.data.map { p ->
        val access = p[accessKey]
        val refresh = p[refreshKey]
        if (access.isNullOrBlank() || refresh.isNullOrBlank()) {
            null
        } else {
            TraktTokens(access, refresh, p[expiresKey] ?: 0L, p[userKey], p[nameKey])
        }
    }.distinctUntilChanged()

    suspend fun current(): TraktTokens? = tokens.first()

    suspend fun save(token: TraktToken, nowMillis: Long = System.currentTimeMillis()) {
        val createdMillis = token.createdAt?.let { it * 1000 } ?: nowMillis
        store.edit {
            it[accessKey] = token.accessToken
            it[refreshKey] = token.refreshToken
            it[expiresKey] = createdMillis + token.expiresIn * 1000
        }
    }

    suspend fun saveUser(user: TraktUser) {
        store.edit {
            it[userKey] = user.username
            if (user.name.isNullOrBlank()) it.remove(nameKey) else it[nameKey] = user.name
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
