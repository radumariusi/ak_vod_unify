package ro.ak.vodunify.ui.library

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultimul ecran Lista mea, în memorie și pe disc: la deschidere apare pe loc, apoi se reîmprospătează în
 * fundal (altfel, cu sute de titluri importate, refacem la fiecare deschidere sute de citiri TMDb).
 */
@Singleton
class LibrarySnapshotStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "library_snapshot.json")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile var memory: LibraryUiState? = null
        private set

    @Volatile private var loaded = false

    suspend fun load(): LibraryUiState? {
        if (loaded) return memory
        val fromDisk = withContext(Dispatchers.IO) {
            runCatching { if (file.exists()) json.decodeFromString(LibraryUiState.serializer(), file.readText()) else null }.getOrNull()
        }
        loaded = true
        if (memory == null) memory = fromDisk
        return memory
    }

    suspend fun save(state: LibraryUiState) {
        if (state.loading) return
        memory = state
        loaded = true
        withContext(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(LibraryUiState.serializer(), state))
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                }
            }
        }
    }
}
