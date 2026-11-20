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
import ro.ak.vodunify.core.imports.HistoryImportRepository
import ro.ak.vodunify.core.imports.ImportBatch

/**
 * Punctul prin care AK Scrobbler trimite ce redă televizorul (`/events`) și AK Import RO/US
 * trimite istoricul din conturi (`/history`). Exportat, dar protejat de permisiunea
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
        fun imports(): HistoryImportRepository
        fun json(): Json
    }

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        val payload = values?.getAsString("payload") ?: return null
        val entry = EntryPointAccessors.fromApplication(ctx.applicationContext, ScrobbleEntryPoint::class.java)
        // AK Import (RO / US): loturi de istoric. Intră într-o coadă; potrivirea se face în fundal.
        if (uri.lastPathSegment == "history") {
            return try {
                val batch = entry.json().decodeFromString(ImportBatch.serializer(), payload)
                entry.imports().enqueue(batch)
                uri.buildUpon().appendPath("queued").build()
            } catch (e: Exception) {
                Log.w(TAG, "Lot de istoric invalid", e)
                null
            }
        }
        // AK Import (RO / US): ce ai văzut recent pe Netflix, pentru vizionările fără titlu.
        if (uri.lastPathSegment == "netflix_recent") {
            return try {
                val result = entry.json().decodeFromString(NetflixRecentResult.serializer(), payload)
                runBlocking { withTimeout(TIMEOUT_MS) { entry.repository().applyNetflixRecent(result) } }
                uri.buildUpon().appendPath("ok").build()
            } catch (e: Exception) {
                Log.w(TAG, "Activitate Netflix invalidă", e)
                null
            }
        }
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
