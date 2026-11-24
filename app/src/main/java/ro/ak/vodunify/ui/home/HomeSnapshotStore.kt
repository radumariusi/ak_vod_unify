package ro.ak.vodunify.ui.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.ak.vodunify.core.catalog.CatalogRow
import ro.ak.vodunify.core.catalog.HomeCatalog
import ro.ak.vodunify.core.catalog.MovieReleaseEvent
import ro.ak.vodunify.core.catalog.TitleReleases
import ro.ak.vodunify.core.catalog.ServiceCatalogStatus
import ro.ak.vodunify.core.catalog.ServiceShelf
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Ce arată Acasă: rândurile din catalog și cele personale, salvate ca să apară pe loc la următoarea deschidere. */
@Serializable
data class HomeSnapshot(
    val catalog: HomeCatalog? = null,
    val personal: PersonalRows? = null,
)

/**
 * Memoria filei Acasă, cât trăiește aplicația și pe disc:
 *  - ultimul ecran Acasă (catalog + rânduri personale), arătat pe loc la deschidere, apoi reîmprospătat în fundal;
 *  - datele de lansare ale titlurilor urmărite, ca o actualizare din AK Scrobbler (un minut nou văzut) să nu
 *    refacă de fiecare dată sute de cereri și decodări TMDb.
 */
@Singleton
class HomeSnapshotStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "home_snapshot.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private var written: HomeSnapshot? = null

    @Volatile var memory: HomeSnapshot? = null
        private set

    @Volatile private var loaded = false

    /** Ultimul ecran Acasă: din memorie sau, la prima deschidere după pornire, de pe disc. */
    suspend fun load(): HomeSnapshot? {
        if (loaded) return memory
        val fromDisk = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(HomeSnapshot.serializer(), file.readText()) else null }.getOrNull()
        }
        loaded = true
        if (memory == null) memory = fromDisk
        return memory
    }

    fun saveCatalog(catalog: HomeCatalog) = save { it.copy(catalog = catalog) }

    fun savePersonal(rows: PersonalRows) {
        if (rows.state == PersonalState.LOADING) return
        save { it.copy(personal = rows) }
    }

    private fun save(change: (HomeSnapshot) -> HomeSnapshot) {
        val next = change(memory ?: HomeSnapshot())
        if (next == memory) return
        memory = next
        scope.launch {
            writeLock.withLock {
                val latest = memory ?: return@withLock
                if (latest == written) return@withLock
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(HomeSnapshot.serializer(), latest))
                    if (tmp.renameTo(file)) written = latest
                }
            }
        }
    }

    // ---------- Date de lansare, ținute în memorie ----------

    private class Timed<T>(val at: Long, val value: T)

    private val releases = ConcurrentHashMap<String, Timed<TitleReleases?>>()
    private val movieReleases = ConcurrentHashMap<Long, Timed<List<MovieReleaseEvent>?>>()
    @Volatile private var popular: Timed<List<TitleReleases>>? = null
    @Volatile private var popularKey: String? = null
    @Volatile private var packages: Timed<Map<String, String>>? = null

    private fun <T> Timed<T>?.fresh(ttl: Long): Timed<T>? = this?.takeIf { System.currentTimeMillis() - it.at < ttl }

    suspend fun releases(key: String, load: suspend () -> TitleReleases?): TitleReleases? {
        releases[key].fresh(RELEASES_TTL)?.let { return it.value }
        val value = load()
        // null = eroare (rețea): se reîncearcă data viitoare.
        if (value != null) releases[key] = Timed(System.currentTimeMillis(), value)
        return value
    }

    suspend fun movieReleases(id: Long, load: suspend () -> List<MovieReleaseEvent>?): List<MovieReleaseEvent>? {
        movieReleases[id].fresh(RELEASES_TTL)?.let { return it.value }
        val value = load()
        if (value != null) movieReleases[id] = Timed(System.currentTimeMillis(), value)
        return value
    }

    suspend fun popular(key: String, load: suspend () -> List<TitleReleases>): List<TitleReleases> {
        if (popularKey == key) popular.fresh(RELEASES_TTL)?.let { return it.value }
        val value = load()
        // O listă goală e de obicei o eroare de rețea: nu o păstrăm.
        if (value.isNotEmpty()) {
            popular = Timed(System.currentTimeMillis(), value)
            popularKey = key
        }
        return value
    }

    suspend fun packages(load: suspend () -> Map<String, String>): Map<String, String> {
        packages.fresh(PACKAGES_TTL)?.let { return it.value }
        return load().also { packages = Timed(System.currentTimeMillis(), it) }
    }

    /** „Reîncearcă”: totul se cere din nou. */
    fun clearMemo() {
        releases.clear()
        movieReleases.clear()
        popular = null
        packages = null
    }

    private companion object {
        val RELEASES_TTL = TimeUnit.MINUTES.toMillis(45)
        val PACKAGES_TTL = TimeUnit.MINUTES.toMillis(5)
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** O eroare temporară lasă disponibile titlurile deja încărcate pentru aceeași bibliotecă. */
internal fun mergeSavedCatalog(current: HomeCatalog, saved: HomeCatalog?): HomeCatalog {
    if (saved == null) return current
    val oldRows = saved.rows.associateBy { it.id }
    val rows = current.rows.map { row ->
        val cached = oldRows[row.id]?.takeIf { it.items.isNotEmpty() }
        if (row.serviceFallback?.reason == ServiceCatalogStatus.UNAVAILABLE && cached != null) cached else row
    }
    val general = if (rows.none { !it.id.startsWith("service_") } && current.hero.isEmpty()) {
        saved.rows.filter(::isGeneralRow)
    } else emptyList()
    return current.copy(hero = current.hero.ifEmpty { if (general.isNotEmpty()) saved.hero else emptyList() }, rows = general + rows)
}


/** Până când verificăm inventarul, afișăm numai rândurile generale din instantaneu. */
internal fun HomeCatalog.withoutServiceRows(): HomeCatalog =
    copy(rows = rows.filter(::isGeneralRow))

private fun isGeneralRow(row: CatalogRow): Boolean =
    !row.id.startsWith("service_") && row.id != "top_movies"

/** Inventarul local hotărăște ce rânduri de servicii apar. */
internal fun HomeCatalog.withInstalledServices(shelves: List<ServiceShelf>, status: ServiceCatalogStatus): HomeCatalog {
    val saved = rows.associateBy { it.id }
    val services = shelves.map { shelf ->
        val row = saved[shelf.rowId]
        when {
            row == null -> shelf.row(status = status)
            row.items.isNotEmpty() -> shelf.row(row.items)
            row.serviceFallback?.reason == ServiceCatalogStatus.CHECKING -> shelf.row(status = status)
            else -> shelf.row(status = row.serviceFallback?.reason ?: status)
        }
    }
    return copy(rows = rows.filter(::isGeneralRow) + services)
}
