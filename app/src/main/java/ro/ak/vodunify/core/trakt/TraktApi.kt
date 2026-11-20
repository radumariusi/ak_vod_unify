package ro.ak.vodunify.core.trakt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ro.ak.vodunify.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

open class TraktException(message: String) : IOException(message)

/** Aplicația a fost compilată fără TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET. */
class TraktNotConfiguredException : TraktException("Lipsește cheia Trakt (secretul TRAKT_CLIENT_ID)")

/** Nu există sesiune sau Trakt a revocat-o: trebuie reconectat contul. */
class TraktAuthRequiredException : TraktException("Contul Trakt nu e conectat. Conectează-l din Setări.")

class TraktHttpException(val code: Int, message: String) : TraktException(message)

data class TraktPage<T>(val items: T, val pageCount: Int)

/**
 * Client HTTP Trakt API v2: antete obligatorii, token Bearer, reîmprospătare automată
 * a tokenului (înainte de expirare și la 401) și reîncercare la 429 (rate limit).
 */
@Singleton
class TraktApi @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val store: TraktAuthStore,
) {
    private val clientId = BuildConfig.TRAKT_CLIENT_ID
    private val clientSecret = BuildConfig.TRAKT_CLIENT_SECRET
    private val refreshMutex = Mutex()

    /**
     * Aplicațiile Trakt noi sunt „public clients” (PKCE) și nu mai primesc client secret.
     * E suficient Client ID; secretul se trimite doar dacă există (aplicații Trakt vechi).
     */
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** Verificatorul PKCE pentru codul de dispozitiv cerut acum. */
    @Volatile private var pendingVerifier: String? = null

    private fun JsonObjectBuilder.secret() {
        if (clientSecret.isNotBlank()) put("client_secret", clientSecret)
    }

    // ---------- Autentificare (device code) ----------

    suspend fun requestDeviceCode(): TraktDeviceCode {
        val verifier = Pkce.newVerifier()
        pendingVerifier = verifier
        val body = buildJsonObject {
            put("client_id", clientId)
            put("code_challenge", Pkce.challenge(verifier))
            put("code_challenge_method", "S256")
        }
        val r = send(post("oauth/device/code", body), auth = false)
        if (r.code != 200) throw httpError(r.code, r.body)
        return json.decodeFromString(TraktDeviceCode.serializer(), r.body)
    }

    /** Întoarce codul HTTP (200 = aprobat, 400 = în așteptare etc.) și tokenul la succes. */
    suspend fun pollDeviceToken(deviceCode: String): Pair<Int, TraktToken?> {
        val body = buildJsonObject {
            put("code", deviceCode)
            put("client_id", clientId)
            secret()
            pendingVerifier?.let { put("code_verifier", it) }
        }
        val r = send(post("oauth/device/token", body), auth = false, retryOn429 = false)
        val token = if (r.code == 200) json.decodeFromString(TraktToken.serializer(), r.body) else null
        return r.code to token
    }

    suspend fun revoke(accessToken: String) {
        val body = buildJsonObject {
            put("token", accessToken)
            put("client_id", clientId)
            secret()
        }
        send(post("oauth/revoke", body), auth = false)
    }

    private suspend fun refresh(used: TraktTokens): TraktTokens = refreshMutex.withLock {
        val current = store.current() ?: throw TraktAuthRequiredException()
        // Alt apel a reîmprospătat deja tokenul cât am așteptat.
        if (current.accessToken != used.accessToken) return@withLock current
        val body = buildJsonObject {
            put("refresh_token", current.refreshToken)
            put("client_id", clientId)
            secret()
            put("redirect_uri", REDIRECT_URI)
            put("grant_type", "refresh_token")
        }
        val r = send(post("oauth/token", body), auth = false)
        when (r.code) {
            200 -> {
                store.save(json.decodeFromString(TraktToken.serializer(), r.body))
                store.current() ?: throw TraktAuthRequiredException()
            }
            400, 401, 403 -> {
                // Refresh token invalid/revocat: sesiunea s-a încheiat.
                store.clear()
                throw TraktAuthRequiredException()
            }
            else -> throw httpError(r.code, r.body)
        }
    }

    private suspend fun validTokens(): TraktTokens {
        val t = store.current() ?: throw TraktAuthRequiredException()
        return if (t.expiresAt - System.currentTimeMillis() < REFRESH_MARGIN_MS) refresh(t) else t
    }

    // ---------- Cereri generice ----------

    suspend fun <T> get(path: String, serializer: KSerializer<T>, params: Map<String, String?> = emptyMap()): T =
        getPage(path, serializer, params).items

    suspend fun <T> getPage(path: String, serializer: KSerializer<T>, params: Map<String, String?> = emptyMap()): TraktPage<T> {
        val r = send(Request.Builder().url(url(path, params)).get(), auth = true)
        if (r.code !in 200..299) throw httpError(r.code, r.body)
        return TraktPage(json.decodeFromString(serializer, r.body), r.pageCount)
    }

    suspend fun postJson(path: String, body: JsonObject): String {
        val r = send(post(path, body), auth = true)
        if (r.code !in 200..299) throw httpError(r.code, r.body)
        return r.body
    }

    private class Raw(val code: Int, val body: String, val pageCount: Int, val retryAfter: Long?)

    private fun url(path: String, params: Map<String, String?>): HttpUrl =
        BASE_URL.toHttpUrl().newBuilder().apply {
            addPathSegments(path)
            params.forEach { (k, v) -> if (!v.isNullOrBlank()) addQueryParameter(k, v) }
        }.build()

    private fun post(path: String, body: JsonObject): Request.Builder =
        Request.Builder().url(url(path, emptyMap())).post(body.toString().toRequestBody(JSON_TYPE))

    private suspend fun send(builder: Request.Builder, auth: Boolean, retryOn429: Boolean = true): Raw {
        if (!isConfigured) throw TraktNotConfiguredException()
        var tokens = if (auth) validTokens() else null
        var refreshed = false
        var attempt = 0
        while (true) {
            val request = builder
                .header("Content-Type", "application/json")
                .header("trakt-api-version", "2")
                .header("trakt-api-key", clientId)
                .header("User-Agent", "AKVODUnify/${BuildConfig.VERSION_NAME}")
                .apply { tokens?.let { header("Authorization", "Bearer ${it.accessToken}") } ?: removeHeader("Authorization") }
                .build()
            val raw = execute(request)
            when {
                raw.code == 401 && tokens != null && !refreshed -> {
                    tokens = refresh(tokens)
                    refreshed = true
                }
                raw.code == 429 && retryOn429 && attempt < 2 -> {
                    attempt++
                    delay(TimeUnit.SECONDS.toMillis((raw.retryAfter ?: 2L).coerceIn(1L, 30L)))
                }
                else -> return raw
            }
        }
    }

    private suspend fun execute(request: Request): Raw = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            Raw(
                code = resp.code,
                body = resp.body?.string().orEmpty(),
                pageCount = resp.header("X-Pagination-Page-Count")?.toIntOrNull() ?: 1,
                retryAfter = resp.header("Retry-After")?.toLongOrNull(),
            )
        }
    }

    private fun httpError(code: Int, body: String): TraktException = when (code) {
        401 -> TraktAuthRequiredException()
        420 -> TraktHttpException(code, "Ai atins limita contului Trakt (ex. listă plină pe contul gratuit).")
        429 -> TraktHttpException(code, "Prea multe cereri către Trakt. Încearcă din nou peste un minut.")
        in 500..599 -> TraktHttpException(code, "Trakt nu răspunde momentan (eroare $code).")
        else -> TraktHttpException(code, "Trakt a răspuns cu eroarea $code${body.take(120).let { if (it.isBlank()) "" else ": $it" }}")
    }

    companion object {
        const val BASE_URL = "https://api.trakt.tv/"
        const val REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"
        private const val REFRESH_MARGIN_MS = 10 * 60 * 1000L
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
