package ro.ak.vodunify.core.scrobble

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json

/**
 * Punctul prin care AK Scrobbler trimite ce redă televizorul. Exportat, dar protejat de permisiunea
 * „signature” `<pachet>.permission.SCROBBLE` (vezi manifestul), deci doar aplicațiile semnate cu
 * aceeași cheie pot scrie aici.
 *
 * `insert` e sincron (rulează pe un fir binder, nu pe cel principal): întoarce
 * `content://…/events/<rezultat>`, sau null dacă a fost o eroare trecătoare și trebuie reîncercat.
 */
class ScrobbleProvider : ContentProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScrobbleEntryPoint {
        fun repository(): ScrobbleRepository
        fun json(): Json
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        val payload = values?.getAsString("payload") ?: return null
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, ScrobbleEntryPoint::class.java)
        val event = try {
            entry.json().decodeFromString(ScrobbleEvent.serializer(), payload)
        } catch (e: Exception) {
            Log.w(TAG, "Eveniment invalid de la AK Scrobbler", e)
            // Nu are rost să fie retrimis.
            return uri.buildUpon().appendPath(ScrobbleResult.IGNORED.wire).build()
        }
        return try {
            val result = runBlocking { withTimeout(TIMEOUT_MS) { entry.repository().handle(event) } }
            uri.buildUpon().appendPath(result.wire).build()
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "TMDb n-a răspuns la timp", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Nu pot procesa evenimentul acum; scrobblerul îl va retrimite", e)
            null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "ScrobbleProvider"
        const val TIMEOUT_MS = 25_000L
    }
}
