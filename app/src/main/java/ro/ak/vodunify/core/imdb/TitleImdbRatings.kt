package ro.ak.vodunify.core.imdb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ro.ak.vodunify.core.calendar.android.CalendarRepository
import ro.ak.vodunify.core.catalog.TitleKind
import ro.ak.vodunify.core.tmdb.TmdbClient
import ro.ak.vodunify.core.tmdb.TmdbExternalIds
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nota IMDb a unui titlu din TMDb, prin cheia OMDb (Setări → Calendar): id-ul IMDb din TMDb (păstrat 7 zile),
 * nota din OMDb. Notele se păstrează pe dispozitiv câteva zile, ca rândurile de postere să nu consume
 * limita zilnică OMDb (1.000 de cereri pe zi la cheia gratuită) la fiecare pornire a aplicației.
 */
@Singleton
class TitleImdbRatings @Inject constructor(
    @ApplicationContext context: Context,
    private val tmdb: TmdbClient,
    private val calendar: CalendarRepository,
) {
    private val prefs = context.getSharedPreferences("omdb_ratings", Context.MODE_PRIVATE)
    private val ids = ConcurrentHashMap<String, String>()
    private val gate = Semaphore(4)

    init {
        // Setul IMDb descărcat de versiunea anterioară nu mai e folosit.
        runCatching { java.io.File(context.filesDir, "imdb_ratings.bin").delete() }
    }

    suspend fun rating(kind: TitleKind, id: Long): Double? {
        if (!tmdb.isConfigured) return null
        val key = "${kind.path}:$id"
        val imdb = ids[key] ?: gate.withPermit {
            runCatching {
                tmdb.get("${kind.path}/$id/external_ids", emptyMap(), TmdbExternalIds.serializer(), TmdbClient.LONG_TTL).imdbId
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }?.also { ids[key] = it } ?: return null
        cached(imdb)?.let { return it.takeIf { r -> r > 0 } }
        val answer = gate.withPermit { runCatching { calendar.imdbRating(imdb) }.getOrNull() } ?: return null
        if (answer.answered) withContext(Dispatchers.IO) {
            prefs.edit().putString(imdb, "${answer.rating ?: 0.0}|${System.currentTimeMillis()}").apply()
        }
        return answer.rating
    }

    /** Nota păstrată (0 = OMDb a spus că nu are notă), dacă nu e prea veche. */
    private fun cached(imdb: String): Double? {
        val raw = prefs.getString(imdb, null) ?: return null
        val rating = raw.substringBefore('|').toDoubleOrNull() ?: return null
        val at = raw.substringAfter('|').toLongOrNull() ?: return null
        val ttl = if (rating > 0) HIT_TTL_MS else MISS_TTL_MS
        return rating.takeIf { System.currentTimeMillis() - at < ttl }
    }

    private companion object {
        val HIT_TTL_MS = TimeUnit.DAYS.toMillis(5)
        val MISS_TTL_MS = TimeUnit.DAYS.toMillis(2)
    }
}
