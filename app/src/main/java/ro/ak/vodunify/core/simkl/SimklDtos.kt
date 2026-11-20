package ro.ak.vodunify.core.simkl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Răspunsul POST /oauth2/device (AUTH V2, RFC 8628). */
@Serializable
data class SimklDeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "https://simkl.com/pin",
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 900,
    val interval: Int = 5,
)

/** Răspunsul POST /oauth2/token. */
@Serializable
data class SimklToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
)

@Serializable
data class SimklOAuthError(
    val error: String? = null,
    @SerialName("error_description") val description: String? = null,
    val message: String? = null,
)

@Serializable
data class SimklUserSettings(val user: SimklUser? = null, val account: SimklAccount? = null)

@Serializable
data class SimklUser(val name: String? = null)

@Serializable
data class SimklAccount(val id: Long? = null, val type: String? = null)
