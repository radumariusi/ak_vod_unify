package ro.ak.vodunify.core.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.BuildConfig
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_url") val verificationUrl: String = "https://www.google.com/device",
    @SerialName("expires_in") val expiresIn: Long = 1800,
    val interval: Long = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * Conectarea la Google Drive pe TV (OAuth pentru dispozitive cu tastatură limitată): aplicația arată un
 * cod, tu îl introduci pe telefon la google.com/device. Acces doar la fișierele create de aplicație
 * (drive.file). Tokenul nu intră în backup.
 */
@Singleton
class GoogleAuth @Inject constructor(
    @ApplicationContext context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** Exclus din backup (vezi [BackupRepository.EXCLUDED_PREFS]). */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_CLIENT_ID.isNotBlank() && BuildConfig.GOOGLE_CLIENT_SECRET.isNotBlank()

    private val _connected = MutableStateFlow(prefs.getString(KEY_REFRESH, null) != null)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt: Long = 0

    suspend fun startDeviceFlow(): DeviceCode = post(
        DEVICE_URL,
        FormBody.Builder().add("client_id", BuildConfig.GOOGLE_CLIENT_ID).add("scope", SCOPE).build(),
    ).let { json.decodeFromString(DeviceCode.serializer(), it.second) }

    /** Așteaptă până confirmi codul pe telefon. Aruncă excepție la refuz sau expirare. */
    suspend fun awaitAuthorization(code: DeviceCode) {
        var interval = code.interval.coerceAtLeast(5)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            val (_, body) = post(
                TOKEN_URL,
                FormBody.Builder()
                    .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
                    .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
                    .add("device_code", code.deviceCode)
                    .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                    .build(),
                allowError = true,
            )
            val r = json.decodeFromString(TokenResponse.serializer(), body)
            when (r.error) {
                null -> {
                    val refresh = r.refreshToken ?: throw IOException("Google nu a trimis refresh token")
                    prefs.edit().putString(KEY_REFRESH, refresh).apply()
                    accessToken = r.accessToken
                    accessExpiresAt = System.currentTimeMillis() + (r.expiresIn - 60) * 1000
                    _connected.value = true
                    return
                }
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                "access_denied" -> throw IOException("Ai refuzat accesul")
                "expired_token" -> throw IOException("Codul a expirat, încearcă din nou")
                else -> throw IOException(r.errorDescription ?: r.error)
            }
        }
        throw IOException("Codul a expirat, încearcă din nou")
    }

    /** Un access token valid (reîmprospătat din refresh token când expiră). */
    suspend fun token(): String {
        accessToken?.takeIf { System.currentTimeMillis() < accessExpiresAt }?.let { return it }
        val refresh = prefs.getString(KEY_REFRESH, null) ?: throw IOException("Nu ești conectat la Google Drive")
        val (_, body) = post(
            TOKEN_URL,
            FormBody.Builder()
                .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
                .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
                .add("refresh_token", refresh)
                .add("grant_type", "refresh_token")
                .build(),
            allowError = true,
        )
        val r = json.decodeFromString(TokenResponse.serializer(), body)
        if (r.error == "invalid_grant") {
            disconnect()
            throw IOException("Accesul la Google Drive a expirat sau a fost revocat. Conectează-te din nou.")
        }
        val access = r.accessToken ?: throw IOException(r.errorDescription ?: r.error ?: "Eroare Google")
        accessToken = access
        accessExpiresAt = System.currentTimeMillis() + (r.expiresIn - 60) * 1000
        return access
    }

    fun disconnect() {
        prefs.edit().remove(KEY_REFRESH).apply()
        accessToken = null
        _connected.value = false
    }

    private suspend fun post(url: String, body: FormBody, allowError: Boolean = false): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !allowError) throw IOException("Google ${resp.code}: ${text.take(200)}")
            resp.code to text
        }
    }

    companion object {
        const val PREFS_NAME = "google_drive_auth"
        private const val DEVICE_URL = "https://oauth2.googleapis.com/device/code"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val KEY_REFRESH = "refresh_token"
    }
}
