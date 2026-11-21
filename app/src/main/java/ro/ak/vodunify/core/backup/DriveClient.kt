package ro.ak.vodunify.core.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val size: String? = null,
    val createdTime: String? = null,
)

@Serializable
private data class DriveList(val files: List<DriveFile> = emptyList())

/** Google Drive API v3, doar ce trebuie pentru backup: un folder, încărcare, listă, descărcare, ștergere. */
@Singleton
class DriveClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val auth: GoogleAuth,
) {
    suspend fun folderId(): String {
        val q = "name = '$FOLDER' and mimeType = '$FOLDER_MIME' and trashed = false"
        list(q).firstOrNull()?.let { return it.id }
        val meta = buildJsonObject {
            put("name", FOLDER)
            put("mimeType", FOLDER_MIME)
        }.toString().toRequestBody(JSON)
        val body = call(Request.Builder().url("$API/files?fields=id,name").post(meta))
        return json.decodeFromString(DriveFile.serializer(), body).id
    }

    suspend fun backups(folderId: String): List<DriveFile> =
        list("'$folderId' in parents and trashed = false", orderBy = "createdTime desc")

    suspend fun upload(folderId: String, name: String, file: File): DriveFile {
        val meta = buildJsonObject {
            put("name", name)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(folderId)) })
        }.toString().toRequestBody(JSON)
        val multipart = MultipartBody.Builder().setType("multipart/related".toMediaType())
            .addPart(meta)
            .addPart(file.asRequestBody(ZIP))
            .build()
        val body = call(
            Request.Builder().url("$UPLOAD/files?uploadType=multipart&fields=id,name,size,createdTime").post(multipart),
        )
        return json.decodeFromString(DriveFile.serializer(), body)
    }

    suspend fun download(id: String, target: File) {
        val token = auth.token()
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$API/files/$id?alt=media").header("Authorization", "Bearer $token").build())
                .execute().use { resp ->
                    check(resp)
                    target.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
                }
        }
    }

    suspend fun delete(id: String) {
        call(Request.Builder().url("$API/files/$id").delete())
    }

    private suspend fun list(q: String, orderBy: String? = null): List<DriveFile> {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id,name,size,createdTime)")
            .apply { orderBy?.let { addQueryParameter("orderBy", it) } }
            .build()
        return json.decodeFromString(DriveList.serializer(), call(Request.Builder().url(url).get())).files
    }

    private suspend fun call(builder: Request.Builder): String {
        val token = auth.token()
        return withContext(Dispatchers.IO) {
            http.newCall(builder.header("Authorization", "Bearer $token").build()).execute().use { resp ->
                check(resp)
                resp.body?.string().orEmpty()
            }
        }
    }

    private fun check(resp: Response) {
        if (!resp.isSuccessful) throw IOException("Google Drive ${resp.code}: ${resp.body?.string().orEmpty().take(200)}")
    }

    private companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val FOLDER = "AK VOD Unify backup"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        val JSON = "application/json; charset=UTF-8".toMediaType()
        val ZIP = "application/zip".toMediaType()
    }
}

