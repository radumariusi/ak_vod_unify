package ro.ak.vodunify.core.imdb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notele IMDb pentru postere, fără cheie și fără limită: setul oficial de date IMDb (title.ratings.tsv.gz,
 * actualizat zilnic de IMDb) se descarcă o dată pe săptămână și se păstrează local, compact.
 */
@Singleton
class ImdbRatingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val mutex = Mutex()
    @Volatile private var table: ImdbRatingsTable? = null
    @Volatile private var failedAt = 0L

    private val file: File get() = File(context.filesDir, "imdb_ratings.bin")

    /** Nota IMDb a titlului („tt…”); null până se încarcă tabelul sau dacă nu are notă. */
    suspend fun rating(imdbId: String?): Double? {
        if (imdbId.isNullOrBlank()) return null
        return ensure()?.rating(imdbId)
    }

    private suspend fun ensure(): ImdbRatingsTable? {
        table?.let { t ->
            if (!stale()) return t
        }
        return mutex.withLock {
            table?.takeIf { !stale() }?.let { return@withLock it }
            withContext(Dispatchers.IO) {
                if (table == null && file.exists()) {
                    table = runCatching { file.inputStream().use(ImdbRatingsTable::read) }.getOrNull()
                }
                val now = System.currentTimeMillis()
                if (stale() && now - failedAt > RETRY_MS) {
                    try {
                        val fresh = download()
                        if (fresh != null) table = fresh else failedAt = now
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failedAt = now
                    }
                }
                table
            }
        }
    }

    private fun stale(): Boolean = !file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MS

    private fun download(): ImdbRatingsTable? {
        val request = Request.Builder().url(URL).get().build()
        val parsed = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            GZIPInputStream(body.byteStream()).bufferedReader().use { ImdbRatingsTable.parseTsv(it) }
        }
        if (parsed.size == 0) return null
        val tmp = File(context.filesDir, "imdb_ratings.tmp")
        tmp.outputStream().use(parsed::write)
        tmp.renameTo(file)
        return parsed
    }

    private companion object {
        const val URL = "https://datasets.imdbws.com/title.ratings.tsv.gz"
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)
        val RETRY_MS = TimeUnit.HOURS.toMillis(1)
    }
}
