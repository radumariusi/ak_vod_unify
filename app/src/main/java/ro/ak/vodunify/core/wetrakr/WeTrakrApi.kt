package ro.ak.vodunify.core.wetrakr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.ak.vodunify.BuildConfig
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.obj
import ro.ak.vodunify.core.wetrakr.WeTrakrMappers.str
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

open class WeTrakrException(message: String) : IOException(message)

class WeTrakrAuthRequiredException : WeTrakrException("Contul WeTrakr nu e conectat. Conectează-l din Setări.")

class WeTrakrHttpException(val code: Int, val error: String?, message: String) : WeTrakrException(message)

data class WeTrakrSession(val token: String, val username: String?)

/** Tokenul WeTrakr, în `noBackupFilesDir`. */
@Singleton
class WeTrakrAuthStore @Inject constructor(@ApplicationContext context: Context) {
    private val store: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(context.noBackupFilesDir, "wetrakr_auth.preferences_pb")
    }
    private val tokenKey = stringPreferencesKey("token")
    private val userKey = stringPreferencesKey("username")

    val session: Flow<WeTrakrSession?> = store.data.map { p ->
        p[tokenKey]?.takeIf { it.isNotBlank() }?.let { WeTrakrSession(it, p[userKey]) }
    }.distinctUntilChanged()

    suspend fun current(): WeTrakrSession? = session.first()

    suspend fun save(token: String, username: String?) {
        store.edit {
            it[tokenKey] = token
            if (username.isNullOrBlank()) it.remove(userKey) else it[userKey] = username
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}

sealed interface WeTrakrPoll {
    data class Approved(val token: String, val username: String?) : WeTrakrPoll
    data object Pending : WeTrakrPoll
    data object SlowDown : WeTrakrPoll
    data object Expired : WeTrakrPoll
    data object Denied : WeTrakrPoll
    data class Failed(val message: String) : WeTrakrPoll
}

/**
 * Client WeTrakr. WeTrakr nu are încă un API public pentru dezvoltatori („în dezvoltare”
 * pe wetrakr.com); folosim protocolul din add-on-ul lor oficial open-source (MIT)
 * github.com/wetrakr/wetrakr-kodi:
 *
 * - login: `POST /oauth/device/code` → cod pe wetrakr.com/activate, apoi `POST /oauth/device/token`;
 * - citire: `GET /integrations/kodi/snapshot`, `/changes`, `/next-episodes` (Bearer token);
 * - scriere: evenimente `POST /webhooks/kodi/{token}` (playing / paused / scrobble).
 *
 * Când WeTrakr publică API-ul oficial, se schimbă doar acest fișier.
 */
@Singleton
class WeTrakrApi @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val store: WeTrakrAuthStore,
) {
    // ---------- Autentificare ----------

    suspend fun requestDeviceCode(): JsonObject {
        val r = execute(post("oauth/device/code", JsonObject(emptyMap())))
        if (r.code !in 200..299) throw httpError(r)
        return parse(r.body).obj() ?: throw WeTrakrException("Răspuns neașteptat de la WeTrakr")
    }

    suspend fun pollDeviceToken(deviceCode: String): WeTrakrPoll {
        val r = execute(post("oauth/device/token", buildJsonObject { put("device_code", deviceCode) }))
        val body = parse(r.body).obj()
        val token = body?.get("access_token").str()
        if (r.code in 200..299 && token != null) return WeTrakrPoll.Approved(token, body?.get("username").str())
        return when (body?.get("error").str()) {
            "authorization_pending", null -> if (r.code in 200..499) WeTrakrPoll.Pending else WeTrakrPoll.Failed("WeTrakr a răspuns cu eroarea ${r.code}")
            "slow_down" -> WeTrakrPoll.SlowDown
            "expired_token" -> WeTrakrPoll.Expired
            "access_denied" -> WeTrakrPoll.Denied
            else -> WeTrakrPoll.Failed("WeTrakr: ${body?.get("error").str()}")
        }
    }

    // ---------- Sincronizare ----------

    suspend fun integration(path: String, params: Map<String, String?> = emptyMap()): JsonObject {
        val session = store.current() ?: throw WeTrakrAuthRequiredException()
        val url = (BASE_URL + "integrations/kodi/" + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()
        val r = execute(
            Request.Builder().url(url).get()
                .header("Authorization", "Bearer ${session.token}")
                .header("X-Client-Name", CLIENT_NAME)
                .header("X-Client-Version", BuildConfig.VERSION_NAME),
        )
        if (r.code == 401) throw WeTrakrAuthRequiredException()
        if (r.code !in 200..299) throw httpError(r)
        return parse(r.body).obj() ?: JsonObject(emptyMap())
    }

    /** Eveniment de redare (playing / paused / scrobble). Întoarce false dacă tokenul nu mai e valid. */
    suspend fun sendEvent(payload: JsonObject) {
        val session = store.current() ?: throw WeTrakrAuthRequiredException()
        val r = execute(post("webhooks/kodi/${session.token}", payload))
        if (r.code == 401 || r.code == 403) throw WeTrakrAuthRequiredException()
        if (r.code !in 200..299) throw httpError(r)
    }

    // ---------- HTTP ----------

    private class Raw(val code: Int, val body: String)

    private fun post(path: String, body: JsonObject): Request.Builder =
        Request.Builder().url(BASE_URL + path).post(body.toString().toRequestBody(JSON_TYPE))

    private suspend fun execute(builder: Request.Builder): Raw = withContext(Dispatchers.IO) {
        val request = builder
            .header("User-Agent", "$CLIENT_NAME/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { Raw(it.code, it.body?.string().orEmpty()) }
    }

    private fun parse(body: String): JsonElement =
        if (body.isBlank()) JsonNull else runCatching { json.parseToJsonElement(body) }.getOrDefault(JsonNull)

    private fun httpError(r: Raw): WeTrakrException {
        val o = parse(r.body).obj()
        val error = o?.get("error").str()
        val message = o?.get("message").str()
        return when {
            r.code == 401 -> WeTrakrAuthRequiredException()
            r.code == 429 -> WeTrakrHttpException(r.code, error, "Ai atins limita zilnică WeTrakr. Încearcă mâine.")
            r.code in 500..599 -> WeTrakrHttpException(r.code, error, "WeTrakr nu răspunde momentan (eroare ${r.code}).")
            else -> WeTrakrHttpException(r.code, error, "WeTrakr a răspuns cu eroarea ${r.code}${(message ?: error)?.let { ": $it" } ?: ""}")
        }
    }

    companion object {
        const val BASE_URL = "https://api.wetrakr.com/"
        const val ACTIVATE_URL = "https://wetrakr.com/activate"
        const val CLIENT_NAME = "AKVODUnify"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
