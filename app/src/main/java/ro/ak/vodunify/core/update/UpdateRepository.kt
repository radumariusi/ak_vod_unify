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
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
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

    suspend fun download(update: AvailableUpdate, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "update.apk")
        val request = Request.Builder().url(update.apkUrl).header("User-Agent", "AKVodUnify-Updater").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Descărcarea a eșuat (${resp.code})")
            val body = resp.body ?: throw IOException("Răspuns gol")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
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
