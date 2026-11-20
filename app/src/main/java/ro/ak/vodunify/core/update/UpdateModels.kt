package ro.ak.vodunify.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
internal data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String,
)

data class AvailableUpdate(
    val versionName: String,
    val versionCode: Int?,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val sizeBytes: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val progress: Float) : UpdateState
    data object NeedsInstallPermission : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Aplicațiile mici care merg cu AK VOD Unify (pachetele de release). */
enum class CompanionApp(val label: String, val packageName: String, val assetPrefix: String, val hint: String) {
    SCROBBLER("AK Scrobbler", "ro.ak.vodscrobbler", "ak-scrobbler-", "vede ce rulează pe TV; fără VPN"),
    IMPORT_RO("AK Import RO", "ro.ak.vodimport.ro", "ak-import-ro-", "conturi din România; fără VPN"),
    IMPORT_US("AK Import US", "ro.ak.vodimport.us", "ak-import-us-", "conturi americane; pune-o în tunelul VPN US"),
}

data class CompanionStatus(
    val companion: CompanionApp,
    val installedCode: Long?,
    val remoteCode: Int?,
    val remoteName: String?,
    val apkUrl: String?,
    val apkName: String?,
    val sizeBytes: Long,
) {
    val installed: Boolean get() = installedCode != null

    /** Se poate instala (lipsește) sau actualiza (release-ul are un cod de versiune mai mare). */
    val actionable: Boolean
        get() = apkUrl != null && (installedCode == null || (remoteCode != null && remoteCode > installedCode))
}
