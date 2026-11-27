package ro.ak.vodunify.core.calendar.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Trimite rezultatele gata fără să aștepte titlurile cu cereri lente. */
internal suspend fun <T> loadCalendarTitles(
    ids: List<Int>,
    concurrency: Int,
    onProgress: (suspend (List<T>) -> Unit)?,
    load: suspend (Int) -> List<T>,
): List<Result<List<T>>> = coroutineScope {
    val permits = Semaphore(concurrency)
    val completed = Channel<Result<List<T>>>(Channel.UNLIMITED)
    val results = ArrayList<Result<List<T>>>(ids.size)
    val entries = mutableListOf<T>()
    ids.forEach { id ->
        launch {
            val result = permits.withPermit {
                try {
                    Result.success(load(id))
                } catch (e: CancellationException) {
                    completed.close(e)
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            completed.send(result)
        }
    }
    var lastPublished = 0
    repeat(ids.size) { index ->
        val result = completed.receive()
        results.add(result)
        if (onProgress != null) {
            entries.addAll(result.getOrDefault(emptyList()))
            if (entries.size > lastPublished && index < ids.lastIndex &&
                (lastPublished == 0 || (index + 1) % concurrency == 0)) {
                onProgress(entries)
                lastPublished = entries.size
            }
        }
    }
    completed.close()
    results
}
