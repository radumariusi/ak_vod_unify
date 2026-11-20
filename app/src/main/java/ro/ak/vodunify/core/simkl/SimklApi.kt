package ro.ak.vodunify.core.simkl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.ak.vodunify.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

open class SimklException(message: String) : IOException(message)

class SimklNotConfiguredException : SimklException("Lipsește cheia SIMKL (secretul SIMKL_CLIENT_ID)")

class SimklAuthRequiredException : SimklException("Contul SIMKL nu e conectat. Conectează-l din Setări.")

class SimklHttpException(val code: Int, val error: String?, message: String) : SimklException(message)

/** Rezultatul unei interogări a codului de dispozitiv. */
sealed interface SimklPoll {
    data class Approved(val token: SimklToken) : SimklPoll
    data object Pending : SimklPoll
    data object SlowDown : SimklPoll
    data object Expired : SimklPoll
    data class Failed(val message: String) : SimklPoll
}

/**
 * Client SIMKL API cu AUTH V2 (OAuth 2.0, device flow RFC 8628, fără client secret).
 *
 * Reguli din documentația SIMKL (api.simkl.org):
 * - fiecare cerere are `client_id`, `app-name`, `app-version` în URL și un User-Agent;
 * - tokenul de acces ține 7 zile, cel de refresh 180 de zile (se prelungește la folosire);
 * - maximum 1 POST/secundă; `400 RATE_LIMIT` = blocare de 20 s pe scrieri (nu e 429).
 */
@Singleton
class SimklApi @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val store: SimklAuthStore,
) {
    private val clientId = BuildConfig.SIMKL_CLIENT_ID
    private val refreshMutex = Mutex()
    private val writeMutex = Mutex()
    @Volatile private var lastPostAt = 0L

    val isConfigured: Boolean get() = clientId.isNotBlank()

    // ---------- Autentificare ----------

    suspend fun requestDeviceCode(): SimklDeviceCode {
        val r = send(form("oauth2/device", "client_id" to clientId, "scope" to SCOPE), auth = false)
        if (r.code !in 200..299) throw httpError(r)
        return json.decodeFromString(SimklDeviceCode.serializer(), r.body)
    }

    suspend fun pollDeviceToken(deviceCode: String): SimklPoll {
        val r = send(
            form(
                "oauth2/token",
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to clientId,
                "device_code" to deviceCode,
            ),
            auth = false,
        )
        if (r.code in 200..299) return SimklPoll.Approved(json.decodeFromString(SimklToken.serializer(), r.body))
        return when (oauthError(r.body)) {
            "authorization_pending" -> SimklPoll.Pending
            "slow_down" -> SimklPoll.SlowDown
            "expired_token" -> SimklPoll.Expired
            "invalid_client" -> SimklPoll.Failed(
                "SIMKL nu recunoaște aplicația. Verifică dacă SIMKL_CLIENT_ID e de la o aplicație OAuth 2.0 (V2).",
            )
            "invalid_grant" -> SimklPoll.Expired
            else -> SimklPoll.Failed("SIMKL a răspuns cu eroarea ${r.code}")
        }
    }

    suspend fun revoke(token: String) {
        send(form("oauth2/revoke", "token" to token, "client_id" to clientId), auth = false)
    }

    private suspend fun refresh(used: SimklTokens): SimklTokens = refreshMutex.withLock {
        val current = store.current() ?: throw SimklAuthRequiredException()
        if (current.accessToken != used.accessToken) return@withLock current
        val refreshToken = current.refreshToken ?: run {
            store.clear()
            throw SimklAuthRequiredException()
        }
        val r = send(
            form("oauth2/token", "grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refreshToken),
            auth = false,
        )
        when {
            r.code in 200..299 -> {
                store.save(json.decodeFromString(SimklToken.serializer(), r.body))
                store.current() ?: throw SimklAuthRequiredException()
            }
            r.code in listOf(400, 401) -> {
                store.clear()
                throw SimklAuthRequiredException()
            }
            else -> throw httpError(r)
        }
    }

    private suspend fun validTokens(): SimklTokens {
        val t = store.current() ?: throw SimklAuthRequiredException()
        val expiring = t.expiresAt > 0 && t.expiresAt - System.currentTimeMillis() < REFRESH_MARGIN_MS
        return if (expiring && t.refreshToken != null) refresh(t) else t
    }

    // ---------- Cereri ----------

    suspend fun get(path: String, params: Map<String, String?> = emptyMap()): JsonElement {
        val r = send(Request.Builder().url(url(path, params)).get(), auth = true)
        if (r.code !in 200..299) throw httpError(r)
        return parse(r.body)
    }

    /** POST JSON cu respectarea limitei de 1 scriere/secundă și a blocării de 20 s. */
    suspend fun postJson(path: String, body: JsonObject, params: Map<String, String?> = emptyMap()): JsonElement =
        writeMutex.withLock { postLocked(path, body, params) }

    private suspend fun postLocked(path: String, body: JsonObject, params: Map<String, String?>): JsonElement {
        var attempt = 0
        while (true) {
            val wait = POST_SPACING_MS - (System.currentTimeMillis() - lastPostAt)
            if (wait > 0) delay(wait)
            val request = Request.Builder().url(url(path, params)).post(body.toString().toRequestBody(JSON_TYPE))
            val r = send(request, auth = true)
            lastPostAt = System.currentTimeMillis()
            if (r.code in 200..299) return parse(r.body)
            if (r.code == 400 && apiError(r.body).equals("RATE_LIMIT", ignoreCase = true) && attempt < 1) {
                attempt++
                delay(WRITE_LOCK_MS)
                continue
            }
            throw httpError(r)
        }
    }

    private class Raw(val code: Int, val body: String, val retryAfter: Long?)

    private fun url(path: String, params: Map<String, String?>): HttpUrl =
        BASE_URL.toHttpUrl().newBuilder().apply {
            addPathSegments(path)
            addQueryParameter("client_id", clientId)
            addQueryParameter("app-name", APP_NAME)
            addQueryParameter("app-version", BuildConfig.VERSION_NAME)
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()

    private fun form(path: String, vararg fields: Pair<String, String>): Request.Builder {
        val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return Request.Builder().url(BASE_URL + path).post(body)
    }

    private suspend fun send(builder: Request.Builder, auth: Boolean): Raw {
        if (!isConfigured) throw SimklNotConfiguredException()
        var tokens = if (auth) validTokens() else null
        var refreshed = false
        var attempt = 0
        while (true) {
            val request = builder
                .header("User-Agent", "AKVODUnify/${BuildConfig.VERSION_NAME}")
                .header("simkl-api-key", clientId)
                .header("Accept", "application/json")
                .apply { tokens?.let { header("Authorization", "Bearer ${it.accessToken}") } ?: removeHeader("Authorization") }
                .build()
            val raw = execute(request)
            when {
                raw.code == 401 && tokens != null && !refreshed && tokens.refreshToken != null -> {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                // 429 pe secundă: reîncercăm; 429 pe zi (Retry-After mare): renunțăm.
                raw.code == 429 && attempt < 2 && (raw.retryAfter ?: 1L) <= 30L -> {
                    attempt++
                    delay(((raw.retryAfter ?: 1L).coerceIn(1L, 30L)) * 1000)
                }
                else -> return raw
            }
        }
    }

    private suspend fun execute(request: Request): Raw = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            Raw(resp.code, resp.body?.string().orEmpty(), resp.header("Retry-After")?.toLongOrNull())
        }
    }

    private fun parse(body: String): JsonElement =
        if (body.isBlank()) JsonNull else json.parseToJsonElement(body)

    private fun oauthError(body: String): String? =
        runCatching { json.decodeFromString(SimklOAuthError.serializer(), body).error }.getOrNull()

    private fun apiError(body: String): String? =
        runCatching { json.decodeFromString(SimklOAuthError.serializer(), body).error }.getOrNull()

    private fun httpError(r: Raw): SimklException {
        val error = apiError(r.body)
        return when {
            r.code == 401 -> SimklAuthRequiredException()
            r.code == 412 -> SimklHttpException(r.code, error, "SIMKL a respins cheia aplicației (client_id). Verifică SIMKL_CLIENT_ID.")
            r.code == 429 -> SimklHttpException(
                r.code,
                error,
                if (error == "user_limit_exceeded") "Ai atins limita zilnică de cereri SIMKL. Se resetează la miezul nopții (ora New York)."
                else "Prea multe cereri către SIMKL. Încearcă din nou peste un minut.",
            )
            r.code in 500..599 -> SimklHttpException(r.code, error, "SIMKL nu răspunde momentan (eroare ${r.code}).")
            else -> SimklHttpException(r.code, error, "SIMKL a răspuns cu eroarea ${r.code}${error?.let { ": $it" } ?: ""}")
        }
    }

    companion object {
        const val BASE_URL = "https://api.simkl.com/"
        const val APP_NAME = "akvodunify"
        const val SCOPE = "media:read media:write"
        private const val REFRESH_MARGIN_MS = 12 * 60 * 60 * 1000L
        private const val POST_SPACING_MS = 1100L
        private const val WRITE_LOCK_MS = 21_000L
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
