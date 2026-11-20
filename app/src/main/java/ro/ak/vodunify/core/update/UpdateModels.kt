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
