package ro.ak.vodunify.core.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import ro.ak.vodunify.BuildConfig
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Verifică GitHub Releases (latest) și descarcă APK-ul. */
@Singleton
class UpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val json: Json,
) {
    suspend fun checkLatest(): AvailableUpdate? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AKVodUnify-Updater")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null // niciun release încă
            if (!resp.isSuccessful) throw IOException("GitHub a răspuns ${resp.code}")
            val release = json.decodeFromString(GhRelease.serializer(), resp.body!!.string())
            // Release-ul conține și AK Scrobbler: luăm doar APK-ul aplicației principale.
            val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            val apk = apks.firstOrNull { it.name.startsWith("ak-vod-unify", ignoreCase = true) }
                ?: apks.firstOrNull { !it.name.contains("scrobbler", ignoreCase = true) }
                ?: return@withContext null
            val remoteCode = Version.versionCodeFromAssetName(apk.name)
            val remoteName = release.tagName.removePrefix("v")
            if (!Version.isNewer(remoteCode, remoteName, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }
            AvailableUpdate(
                versionName = remoteName,
                versionCode = remoteCode,
                notes = release.body.orEmpty(),
                apkUrl = apk.downloadUrl,
                apkName = apk.name,
                sizeBytes = apk.size,
            )
        }
    }

    /**
     * Aplicațiile însoțitoare (AK Scrobbler, AK Import RO/US): versiunea instalată și cea din
     * ultimul release, ca să le poți instala / actualiza din AK VOD Unify.
     */
    suspend fun companions(): List<CompanionStatus> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AKVodUnify-Updater")
            .build()
        val release = http.newCall(request).execute().use { resp ->
            if (resp.code == 404) null
            else if (!resp.isSuccessful) throw IOException("GitHub a răspuns ${resp.code}")
            else json.decodeFromString(GhRelease.serializer(), resp.body!!.string())
        }
        CompanionApp.entries.map { c ->
            val asset = release?.assets?.firstOrNull { it.name.startsWith(c.assetPrefix, ignoreCase = true) && it.name.endsWith(".apk") }
            CompanionStatus(
                companion = c,
                installedCode = installedVersionCode(c.packageName),
                remoteCode = asset?.let { Version.versionCodeFromAssetName(it.name) },
                remoteName = release?.tagName?.removePrefix("v"),
                apkUrl = asset?.downloadUrl,
                apkName = asset?.name,
                sizeBytes = asset?.size ?: 0,
            )
        }
    }

    fun installedVersionCode(pkg: String): Long? = try {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
        null
    }

    suspend fun downloadCompanion(status: CompanionStatus, onProgress: (Float) -> Unit): File {
        val url = requireNotNull(status.apkUrl) { "Release-ul nu conține ${status.companion.label}" }
        return fetch(url, File(context.cacheDir, "companions").apply { mkdirs() }, "${status.companion.name}.apk", status.sizeBytes, onProgress)
    }

    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        return fetch(update.apkUrl, dir, "update.apk", update.sizeBytes, onProgress)
    }

    private suspend fun fetch(url: String, dir: File, name: String, size: Long, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = File(dir, name)
        val request = Request.Builder().url(url).header("User-Agent", "AKVodUnify-Updater").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Descărcarea a eșuat (${resp.code})")
            val body = resp.body ?: throw IOException("Răspuns gol")
            val total = body.contentLength().takeIf { it > 0 } ?: size
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        target
    }
}
